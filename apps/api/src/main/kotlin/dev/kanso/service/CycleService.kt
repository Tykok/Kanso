package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.repo.CycleRepository
import dev.kanso.repo.CycleRow
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/** The three the sidebar draws, and no fourth. Mirrors `cycles_state_chk`. */
enum class CycleState(override val wire: String) : Wire {
	UPCOMING("upcoming"), ACTIVE("active"), CLOSED("closed");

	companion object {
		fun from(raw: String): CycleState = parse(entries.toTypedArray(), raw)
	}
}

data class Cycle(
	val id: UUID,
	val teamId: UUID,
	val number: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	val state: CycleState,
)

/** A cycle and how much work is in it — one sidebar row. */
data class CycleSummary(val cycle: Cycle, val ticketCount: Int)

/** One bar of the burn-down. [projected] is what the drawing hatches. */
data class RemainingDay(val day: LocalDate, val open: Int, val projected: Boolean)

/**
 * Everything screen 19 draws, computed on read.
 *
 * There is no rate column anywhere and there will not be one: a rate is wrong from the
 * moment the next ticket closes, and a cached one is a number nobody can explain when it
 * disagrees with the list underneath it.
 */
data class CycleReport(
	val cycle: Cycle,
	val total: Int,
	val done: Int,
	val percent: Int,
	val byStatus: Map<TicketStatus, Int>,
	val daysLeft: Int,
	val remaining: List<RemainingDay>,
	val slipping: List<TicketDetail>,
	val tickets: List<TicketDetail>,
)

/**
 * Cycles: what a team committed to, and how much of it is going to happen.
 *
 * The five statuses the drawing plots are the whole vocabulary here — `canceled` is
 * excluded from every number. A cancelled ticket is a decision not to do the work, so
 * leaving it in the denominator would make a cycle that cut its own scope look failed,
 * and leaving it in the burn-down would draw a line that can never reach zero.
 */
@Service
class CycleService(
	private val cycles: CycleRepository,
	private val tickets: TicketRepository,
	private val details: TicketDetails,
	private val access: TicketAccess,
	private val activity: ActivityService,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID): List<CycleSummary> {
		val rows = cycles.findByTeam(teamId)
		val counts = cycles.countIn(rows.map { it.id })
		return rows.map { CycleSummary(it.toDomain(), counts[it.id] ?: 0) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): Cycle = require(id).toDomain()

	@Transactional(readOnly = true)
	fun findActive(teamId: UUID): Cycle? = cycles.findActive(teamId)?.toDomain()

	/** For `/cycles/current`, which has no sensible page to render without one. */
	@Transactional(readOnly = true)
	fun active(teamId: UUID): Cycle =
		findActive(teamId) ?: throw NotFoundException("Team $teamId has no cycle in progress")

	@Transactional(readOnly = true)
	fun byNumber(teamId: UUID, number: Int): Cycle =
		cycles.findByTeamAndNumber(teamId, number)?.toDomain()
			?: throw NotFoundException("No cycle $number in team $teamId")

	@Transactional
	fun create(
		actor: User,
		teamId: UUID,
		number: Int,
		startsOn: LocalDate,
		endsOn: LocalDate,
		state: CycleState,
	): Cycle {
		access.requireTeam(actor, teamId)
		if (endsOn.isBefore(startsOn)) {
			throw BadRequestException("A cycle cannot end ($endsOn) before it starts ($startsOn)")
		}
		if (cycles.findByTeamAndNumber(teamId, number) != null) {
			throw ConflictException("This team already has a cycle $number")
		}
		// Checked here as well as by `cycles_one_active_per_team`, because the index would
		// surface as an opaque 409 from the driver. The rule is worth a sentence: two
		// cycles in progress make `/cycles/current` answer differently depending on the
		// sort, which is the one thing that route cannot do.
		if (state == CycleState.ACTIVE) {
			cycles.findActive(teamId)?.let {
				throw ConflictException("Cycle ${it.number} is still active; close it first")
			}
		}
		return cycles.insert(UUID.randomUUID(), teamId, number, startsOn, endsOn, state.wire).toDomain()
	}

	@Transactional
	fun setState(actor: User, id: UUID, state: CycleState): Cycle {
		val cycle = require(id)
		access.requireTeam(actor, cycle.teamId)
		if (state == CycleState.ACTIVE) {
			cycles.findActive(cycle.teamId)?.takeIf { it.id != id }?.let {
				throw ConflictException("Cycle ${it.number} is still active; close it first")
			}
		}
		cycles.updateState(id, state.wire)
		// The *transition* carries the work, not the state. Setting a closed cycle closed
		// again is the same button pressed twice, and it must not walk a ticket that has
		// already moved on into a third cycle.
		if (state == CycleState.CLOSED && CycleState.from(cycle.state) != CycleState.CLOSED) {
			carryOver(actor, cycle)
		}
		return require(id).toDomain()
	}

	/**
	 * Puts tickets in this cycle, taking them out of whichever one they were in — the
	 * "move to cycle 25" button, and the bulk strip's cycle control, are the same write.
	 *
	 * Both sides are checked: the cycle's team because the cycle is a team's plan, and
	 * each ticket because moving somebody else's work into your cycle is a write to their
	 * ticket however it is spelled.
	 */
	@Transactional
	fun addTickets(actor: User, cycleId: UUID, ticketIds: List<UUID>): Cycle {
		val cycle = require(cycleId)
		access.requireTeam(actor, cycle.teamId)
		val found = tickets.findAllById(ticketIds)
		val missing = ticketIds.toSet() - found.map { it.id }.toSet()
		if (missing.isNotEmpty()) throw BadRequestException("Unknown tickets: ${missing.joinToString()}")
		found.forEach { access.require(actor, it) }
		cycles.place(ticketIds, cycleId)
		return cycle.toDomain()
	}

	@Transactional
	fun removeTickets(actor: User, cycleId: UUID, ticketIds: List<UUID>) {
		val cycle = require(cycleId)
		access.requireTeam(actor, cycle.teamId)
		tickets.findAllById(ticketIds).forEach { access.require(actor, it) }
		cycles.remove(ticketIds)
	}

	/**
	 * [today] is a parameter rather than read from the clock, so the projection can be
	 * asserted at a fixed date. Callers pass the server's day; a per-reader timezone
	 * would make "six days left" a different number for two people in one standup.
	 */
	@Transactional(readOnly = true)
	fun report(cycleId: UUID, today: LocalDate = LocalDate.now()): CycleReport {
		val cycle = require(cycleId).toDomain()
		val counted = cycles.ticketsIn(cycleId).filter { it.status != TicketStatus.CANCELED }
		val loaded = details.of(counted)

		val byStatus = COUNTED_STATUSES.associateWith { status -> counted.count { it.status == status } }
		val total = counted.size
		val done = byStatus[TicketStatus.DONE] ?: 0
		val open = loaded.filter { it.ticket.status != TicketStatus.DONE }

		val daysLeft = ChronoUnit.DAYS.between(today, cycle.endsOn).toInt().coerceAtLeast(0)
		// Inclusive of today: a cycle on its first day has measured one day, not zero, and
		// dividing by zero is the only other option.
		val measured = ChronoUnit.DAYS.between(cycle.startsOn, minOf(today, cycle.endsOn)).toInt() + 1
		val rate = if (measured <= 0) null else done.toDouble() / measured

		return CycleReport(
			cycle = cycle,
			total = total,
			done = done,
			percent = if (total == 0) 0 else (done * 100.0 / total).roundToInt(),
			byStatus = byStatus,
			daysLeft = daysLeft,
			remaining = burnDown(cycle, counted, total, today, rate, open.size),
			slipping = slipping(open, rate, daysLeft),
			tickets = loaded,
		)
	}

	// --- closing ---------------------------------------------------------------

	/**
	 * What a closed cycle does with the work that did not fit.
	 *
	 * It moves. Leaving it behind would make every closed cycle a place work goes to
	 * stop being looked at: the next cycle starts empty and reads as healthy, and the
	 * four tickets that slipped are only findable by opening a cycle nobody has a reason
	 * to open again. The burn-down already names them — `report().slipping` is the list
	 * this method acts on the moment the cycle ends.
	 *
	 * Done and cancelled stay. A finished ticket is the closed cycle's own record of
	 * what the team achieved, and moving it would rewrite that history into the next
	 * cycle's; a cancelled one is a decision not to do the work, so carrying it forward
	 * would silently re-open it.
	 *
	 * Only the team is checked, not each ticket: [setState] has already established that
	 * the actor may plan this team, and refusing to close a cycle because one ticket in
	 * it belongs elsewhere would leave the cycle stuck open with no cure the closer can
	 * apply. It is the same act either way — the tickets are the cycle's contents.
	 */
	private fun carryOver(actor: User, closing: CycleRow) {
		val unfinished = cycles.ticketsIn(closing.id).filterNot { it.status in FINISHED_STATUSES }
		// Before the destination is resolved, so a cycle that finished everything closes
		// without an empty cycle 25 appearing beside the plan the team actually made.
		if (unfinished.isEmpty()) return

		val target = cycles.findNextUpcoming(closing.teamId, closing.number) ?: plan(closing)
		cycles.carryOver(unfinished.map { it.id }, target.id)
		for (ticket in unfinished) {
			activity.record(
				ActivityEntity.TICKET, ticket.id, actor.id, ActivityKind.CARRIED_OVER,
				// The numbers because they are what the sentence reads ("carried from 24
				// into 25"), the id because the feed links to where the work went and a
				// number is only unique within a team.
				mapOf("from" to closing.number, "to" to target.number, "cycleId" to target.id.toString()),
			)
		}
	}

	/**
	 * The cycle the team has not planned yet, planned for them.
	 *
	 * Its dates are the closing cycle's own cadence: it starts the day after that one
	 * ends, and runs for the same number of days. Both halves are choices worth stating.
	 * Starting the next day leaves no gap — a day belonging to no cycle is a day whose
	 * work has nowhere to be committed, and the burn-down of the new cycle would open
	 * with days already spent. Reusing the length rather than defaulting to a fortnight
	 * means the guess is the team's own rhythm: a team on one-week cycles gets a week,
	 * and nobody has to correct a number Kanso invented. It is a *guess* either way,
	 * which is why the cycle is created `upcoming` — somebody will look at it before it
	 * starts, and moving two dates is a smaller correction than finding lost work.
	 *
	 * Its number is the next free one in the team rather than `closing.number + 1`,
	 * which `cycles_team_number_uniq` would refuse the moment a team closed a cycle out
	 * of order.
	 */
	private fun plan(closing: CycleRow): CycleRow {
		val length = ChronoUnit.DAYS.between(closing.startsOn, closing.endsOn)
		val startsOn = closing.endsOn.plusDays(1)
		return cycles.insert(
			id = UUID.randomUUID(),
			teamId = closing.teamId,
			number = (cycles.maxNumber(closing.teamId) ?: closing.number) + 1,
			startsOn = startsOn,
			endsOn = startsOn.plusDays(length),
			state = CycleState.UPCOMING.wire,
		)
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * One bar per day of the cycle. Measured days read `completed_at`; the days that have
	 * not happened yet fall by the observed rate and are marked so the chart can hatch
	 * them. The projection stops at the work that will not fit rather than at zero —
	 * drawing a line to zero would contradict the list of slipping tickets beside it.
	 */
	private fun burnDown(
		cycle: Cycle,
		counted: List<dev.kanso.domain.Ticket>,
		total: Int,
		today: LocalDate,
		rate: Double?,
		openNow: Int,
	): List<RemainingDay> {
		val closedOn = counted.mapNotNull { it.completedAt?.toLocalDate() }.sorted()
		val floor = slipCount(openNow, rate, ChronoUnit.DAYS.between(today, cycle.endsOn).toInt())
		var day = cycle.startsOn
		val bars = mutableListOf<RemainingDay>()
		while (!day.isAfter(cycle.endsOn)) {
			if (!day.isAfter(today)) {
				bars += RemainingDay(day, total - closedOn.count { !it.isAfter(day) }, projected = false)
			} else {
				val ahead = ChronoUnit.DAYS.between(today, day).toInt()
				val projectedOpen = if (rate == null) openNow else openNow - floor(rate * ahead).toInt()
				bars += RemainingDay(day, projectedOpen.coerceAtLeast(floor), projected = true)
			}
			day = day.plusDays(1)
		}
		return bars
	}

	/**
	 * The tail of the list nobody will reach. Ordered the way the team works — highest
	 * priority first, then oldest — so what slips is what was always going to be last,
	 * not whatever the database returned last.
	 */
	private fun slipping(open: List<TicketDetail>, rate: Double?, daysLeft: Int): List<TicketDetail> {
		val capacity = capacity(rate, daysLeft) ?: return emptyList()
		return open
			.sortedWith(compareByDescending<TicketDetail> { it.ticket.priority.ordinal }.thenBy { it.ticket.createdAt })
			.drop(capacity)
	}

	private fun slipCount(openNow: Int, rate: Double?, daysLeft: Int): Int {
		val capacity = capacity(rate, daysLeft.coerceAtLeast(0)) ?: return 0
		return (openNow - capacity).coerceAtLeast(0)
	}

	/**
	 * How many more tickets close before the cycle ends, floored: half a ticket is not a
	 * ticket, and rounding up would let the screen promise work nobody has done yet.
	 * `null` means the cycle has measured no days, so there is no rate — a rate of zero
	 * would declare the whole thing doomed the day before it starts.
	 */
	private fun capacity(rate: Double?, daysLeft: Int): Int? =
		if (rate == null) null else floor(rate * daysLeft).toInt()

	private fun require(id: UUID): CycleRow =
		cycles.findById(id) ?: throw NotFoundException("No cycle $id")

	private fun CycleRow.toDomain() = Cycle(
		id = id,
		teamId = teamId,
		number = number,
		startsOn = startsOn,
		endsOn = endsOn,
		state = CycleState.from(state),
	)

	companion object {
		/**
		 * Where work stops. Neither is carried into the next cycle when this one closes:
		 * one is the closed cycle's record of what got done, the other of what the team
		 * decided against. Everything else is unfinished, backlog included — a ticket
		 * nobody started is still a commitment nobody has withdrawn.
		 */
		val FINISHED_STATUSES = setOf(TicketStatus.DONE, TicketStatus.CANCELED)

		/** The five the drawing plots. `canceled` is not work, so it is not counted. */
		val COUNTED_STATUSES = listOf(
			TicketStatus.BACKLOG,
			TicketStatus.TODO,
			TicketStatus.IN_PROGRESS,
			TicketStatus.IN_REVIEW,
			TicketStatus.DONE,
		)
	}
}

package dev.kanso.service

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

/**
 * One bar of the burn-down, in both units. [projected] is what the drawing hatches.
 *
 * Two numbers rather than one because a cycle can be estimated, half-estimated or not
 * estimated at all, and the chart has to stay readable in all three: [openPoints] is the
 * work left, [open] is the rows left, and neither is derivable from the other. A response
 * carrying only points would draw a flat empty chart for a team that does not estimate.
 */
data class RemainingDay(val day: LocalDate, val open: Int, val openPoints: Int, val projected: Boolean)

/**
 * The cycle's effort, in points — and the size of what the points cannot speak for.
 *
 * A type of its own rather than three more Ints on [CycleReport], because [unestimated]
 * has to travel with the sum wherever it goes. A ticket nobody has sized is left out of
 * [total] rather than added as a zero, so a sum on its own reads as the whole cycle while
 * describing part of it — and a burn-down that silently ignores a third of the work is
 * worse than one that counts rows.
 */
data class CyclePoints(val total: Int, val done: Int, val percent: Int, val unestimated: Int)

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
	/**
	 * The same three questions asked in points. Kept beside the counts rather than
	 * replacing them: the counts are what a team that has not estimated anything reads,
	 * and [CyclePoints.unestimated] is what says which of the two to believe.
	 */
	val points: CyclePoints,
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

		// Summed, never counted as zero: a ticket nobody has sized is missing from both
		// halves of this and present in `unestimated` instead, which is the only honest way
		// to report a fraction whose numerator and denominator are both incomplete.
		val totalPoints = counted.sumOf { it.estimate ?: 0 }
		val donePoints = counted.filter { it.status == TicketStatus.DONE }.sumOf { it.estimate ?: 0 }

		val daysLeft = ChronoUnit.DAYS.between(today, cycle.endsOn).toInt().coerceAtLeast(0)
		// Inclusive of today: a cycle on its first day has measured one day, not zero, and
		// dividing by zero is the only other option.
		val measured = ChronoUnit.DAYS.between(cycle.startsOn, minOf(today, cycle.endsOn)).toInt() + 1
		val rate = if (measured <= 0) null else done.toDouble() / measured
		// The same observed rate in the other unit — points closed per day so far. Not
		// derived from `rate`: a team that closes one 13 and three 1s in a week has two
		// rates that say different things, which is the whole reason the points exist.
		val pointsRate = if (measured <= 0) null else donePoints.toDouble() / measured

		return CycleReport(
			cycle = cycle,
			total = total,
			done = done,
			percent = if (total == 0) 0 else (done * 100.0 / total).roundToInt(),
			points = CyclePoints(
				total = totalPoints,
				done = donePoints,
				percent = if (totalPoints == 0) 0 else (donePoints * 100.0 / totalPoints).roundToInt(),
				unestimated = counted.count { it.estimate == null },
			),
			byStatus = byStatus,
			daysLeft = daysLeft,
			remaining = burnDown(cycle, counted, today, rate, pointsRate),
			slipping = slipping(open, rate, daysLeft),
			tickets = loaded,
		)
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * One bar per day of the cycle, in both units at once.
	 *
	 * The two series are the same [descent] over two weights — one ticket each, or its
	 * points — rather than two pieces of arithmetic that could drift apart. Written that
	 * way because they *must* agree about which days are measured and which are hatched: a
	 * chart whose two readings disagree about where today is describes two cycles.
	 */
	private fun burnDown(
		cycle: Cycle,
		counted: List<dev.kanso.domain.Ticket>,
		today: LocalDate,
		rate: Double?,
		pointsRate: Double?,
	): List<RemainingDay> {
		val days = generateSequence(cycle.startsOn) { it.plusDays(1) }
			.takeWhile { !it.isAfter(cycle.endsOn) }
			.toList()
		val rows = descent(days, counted, today, rate) { 1 }
		// An unsized ticket weighs nothing here, and is reported by `CyclePoints.unestimated`
		// instead: giving it a 1, or the median of the scale, would be inventing the very
		// number somebody declined to give.
		val points = descent(days, counted, today, pointsRate) { it.estimate ?: 0 }
		return days.mapIndexed { index, day ->
			RemainingDay(day, rows[index], points[index], projected = day.isAfter(today))
		}
	}

	/**
	 * One unit's descent. Measured days read `completed_at`; the days that have not
	 * happened yet fall by the observed rate and are marked so the chart can hatch them.
	 * The projection stops at the work that will not fit rather than at zero — drawing a
	 * line to zero would contradict the list of slipping tickets beside it.
	 */
	private fun descent(
		days: List<LocalDate>,
		counted: List<dev.kanso.domain.Ticket>,
		today: LocalDate,
		rate: Double?,
		weight: (dev.kanso.domain.Ticket) -> Int,
	): List<Int> {
		val total = counted.sumOf(weight)
		val closed = counted.mapNotNull { ticket -> ticket.completedAt?.let { it.toLocalDate() to weight(ticket) } }
		val openNow = counted.filter { it.status != TicketStatus.DONE }.sumOf(weight)
		val floorAt = slipCount(openNow, rate, ChronoUnit.DAYS.between(today, days.last()).toInt())
		return days.map { day ->
			if (!day.isAfter(today)) {
				total - closed.filter { !it.first.isAfter(day) }.sumOf { it.second }
			} else {
				val ahead = ChronoUnit.DAYS.between(today, day).toInt()
				val projectedOpen = if (rate == null) openNow else openNow - floor(rate * ahead).toInt()
				projectedOpen.coerceAtLeast(floorAt)
			}
		}
	}

	/**
	 * The tail of the list nobody will reach. Ordered the way the team works — highest
	 * priority first, then oldest — so what slips is what was always going to be last,
	 * not whatever the database returned last.
	 *
	 * Counted in tickets and not in points, deliberately, and it is the one number on this
	 * screen that is. An unsized ticket has to be able to slip — it is real work somebody
	 * committed to — and a points capacity has nothing to weigh it with, so it would
	 * silently promise every unestimated ticket in the cycle. The points answer "how much
	 * is left" in the burn-down beside it; this list answers "which ones", and that
	 * question is about rows.
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

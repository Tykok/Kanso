package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.CycleRepository
import dev.kanso.repo.CycleRow
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.WeekFields
import java.util.UUID

/**
 * The four numbers the personal strip prints, for the caller and nobody else.
 *
 * All four are counts of *rows*, not sums of points, and that is the same ruling
 * `WorkloadService` makes about the same join table: a ticket two people share is on both
 * their plates whole, because "how many things am I holding" is not a question a half
 * answers. The points below are split, for the opposite and equally deliberate reason —
 * see [MyFinishedWeek].
 *
 * [finishedThisWeek] is the last of [MyStats.weeks] read again rather than counted a
 * second time. Two counts of one thing can drift apart; one cannot.
 */
data class MyWorkStrip(
	val open: Int,
	val overdue: Int,
	val blocked: Int,
	val finishedThisWeek: Int,
)

/**
 * One ISO week of what somebody finished — one bar of the chart.
 *
 * The bucket is named three ways because the screen needs all three and must not derive
 * any of them: [startsOn] is what the axis prints and what makes two weeks comparable,
 * and [isoYear] with [isoWeek] are what a tooltip says ("W36"). Deriving the week number
 * in the browser is precisely what the spec forbids — a boundary computed from a local
 * clock and a count computed in Postgres disagree twice a year, and the disagreement
 * shows up as a bar in the wrong place, which no screenshot reveals.
 *
 * [points] is a Double and is a *share*: a ticket with two assignees contributes half of
 * itself to each. `VelocityService.delivered` states the rule and the reason — a delivery
 * happened once, so crediting an 8 whole to both owners would report sixteen points the
 * team never shipped — and this file follows it rather than choosing again, because the
 * two numbers sit on the same screen and a reader will divide one by the other.
 *
 * [unestimated] is the part [points] cannot speak for. An unsized ticket is absent from
 * the sum rather than added as a zero: a bar that shrank for the week somebody forgot to
 * estimate would be a fine for the forgetting.
 *
 * A week nothing closed in is a bucket of zeros, never a missing entry. Zero is a
 * measurement — that week was quiet — and the chart has to be able to draw the quiet.
 */
data class MyFinishedWeek(
	val isoYear: Int,
	val isoWeek: Int,
	/** The Monday the ISO week begins on. */
	val startsOn: LocalDate,
	val finished: Int,
	val points: Double,
	val unestimated: Int,
)

/**
 * What the caller signed up for in one cycle in progress, against what they have closed.
 *
 * The one figure on this screen that can be read *during* a cycle. Velocity, by
 * construction, only answers after one has closed — `VelocityService` refuses the running
 * cycle outright — so a person three days into a fortnight has nothing else to look at.
 *
 * Both halves are split between assignees by the same rule, which is what makes the
 * fraction mean anything: a whole commitment over a halved delivery would be a ratio
 * describing two different people.
 *
 * One of these per team, not one in total. A cycle is one team's calendar with one team's
 * dates, and adding somebody's commitment in two teams would produce a number measured
 * against two fortnights at once — the same reason `/api/me/velocity` insists on a
 * `teamId` rather than picking one. Here the list is the answer instead of a parameter,
 * because a personal home has no team selected and the honest shape of "your commitment"
 * for somebody in two teams is two rows.
 */
data class MyCommitment(
	val teamId: UUID,
	val teamName: String,
	val teamKey: String,
	val cycleId: UUID,
	val cycleNumber: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	/** Their tickets in the cycle, counted whole. */
	val committed: Int,
	val finished: Int,
	val committedPoints: Double,
	val finishedPoints: Double,
	/** How many of [committed] nobody sized — absent from both point totals, never a zero. */
	val unestimated: Int,
)

/**
 * A finished ticket, as the Done tab lists it.
 *
 * [completedAt] is here and deliberately **not** on the shared ticket DTO. One screen
 * needs the date; widening the row every list in the app already fetches, to serve one
 * chart, is how a DTO becomes a junk drawer.
 *
 * [identifier] is resolved rather than left as a team key and a number for the client to
 * join, and is null only for a ticket whose team has gone away underneath the read —
 * `TicketDetail` types it as absent for a draft, and the query behind this list excludes
 * drafts.
 */
data class MyFinishedTicket(
	val id: UUID,
	val identifier: String?,
	val title: String,
	val completedAt: OffsetDateTime,
	val estimate: Int?,
)

/**
 * Everything `/me` draws about the caller in one answer.
 *
 * [openUnestimated] sits beside a velocity rather than in [strip], because it is not a
 * count of work — it is the reason a measured velocity understates. It is about open work
 * only: a finished ticket nobody sized is reported by the week it closed in, and it is
 * too late to size it anyway.
 */
data class MyStats(
	val strip: MyWorkStrip,
	/** Twelve, oldest first, zeros included. */
	val weeks: List<MyFinishedWeek>,
	val commitments: List<MyCommitment>,
	val openUnestimated: Int,
	/** Newest completion first, capped at [MyStatsService.RECENT_TICKETS]. */
	val recentlyFinished: List<MyFinishedTicket>,
)

/**
 * One person's own numbers, for the personal home.
 *
 * Everything here is derived on read, from `tickets.completed_at`, `ticket_assignees`,
 * `ticket_dependencies` and `ticket_cycles`. There is no stored count and there will not
 * be one, for the reason `VelocityService` gives about a stored rate: a cached "open"
 * would be wrong from the moment somebody closes a ticket, wrong again when somebody
 * resizes one, and unexplainable when it disagrees with the list the screen draws
 * underneath it.
 *
 * It answers about exactly the [User] it is handed and never widens: every read below is
 * bounded by that person's rows in `ticket_assignees`, and a ticket that has a team is
 * readable by anybody in the instance already (`TicketAccess.mayRead`), so nothing here
 * discloses more than the caller's own list does. The rule about *who may ask* lives in
 * `MyStatsController`, which is the same narrow answer `VelocityController` settled on:
 * you may read your own.
 *
 * [now] is a parameter rather than read from the clock inside, exactly as
 * `CycleService.report`'s `today` is: the twelve week boundaries and the overdue test are
 * both decided by it, and a test that could not pin it would measure something different
 * every Monday.
 */
@Service
class MyStatsService(
	private val tickets: TicketRepository,
	private val dependencies: DependencyRepository,
	private val cycles: CycleRepository,
	private val teams: TeamRepository,
	private val details: TicketDetails,
	private val statusCategories: StatusCategories,
) {

	@Transactional(readOnly = true)
	fun forPerson(person: User, now: OffsetDateTime = OffsetDateTime.now()): MyStats {
		val open = tickets.search(
			assigneeId = person.id,
			categories = WorkloadService.OPEN_CATEGORIES,
			limit = WorkloadService.SCAN_LIMIT,
		)
		val window = weekStarts(now)
		val closed = finished(person, window, now)

		val weeks = window.map { monday -> week(monday, closed.filter { it.week == monday }) }

		return MyStats(
			strip = MyWorkStrip(
				open = open.size,
				overdue = open.count { overdue(it, now) },
				blocked = blocked(open),
				// The chart's last bar, not a second count of it.
				finishedThisWeek = weeks.last().finished,
			),
			weeks = weeks,
			commitments = commitments(person, open, closed),
			openUnestimated = open.count { it.estimate == null },
			recentlyFinished = recent(closed),
		)
	}

	// --- the twelve weeks ------------------------------------------------------

	/**
	 * The twelve Mondays, oldest first — the order the chart draws in, so the client never
	 * reverses a list to render it.
	 */
	private fun weekStarts(now: OffsetDateTime): List<LocalDate> {
		val thisWeek = mondayOf(now.toLocalDate())
		return (WEEKS - 1 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
	}

	/**
	 * Their completed tickets that fall inside the window, newest first, each carrying the
	 * week it belongs to and the caller's share of its points.
	 *
	 * The database is asked for a day more than the window holds. `completed_at` is an
	 * instant and the bucket is decided from its own offset, the way `VelocityService`
	 * reads a completion date, so a row completed just after midnight in a positive offset
	 * belongs to a Monday that begins before that Monday does in UTC. A day of slack costs
	 * a handful of rows the fold below discards and removes the whole class of off-by-one
	 * at the left edge.
	 */
	private fun finished(person: User, window: List<LocalDate>, now: OffsetDateTime): List<Finished> {
		val since = window.first().minusDays(1).atStartOfDay().atOffset(now.offset)
		val rows = tickets.findCompletedSince(person.id, since, WorkloadService.SCAN_LIMIT)
		if (rows.isEmpty()) return emptyList()

		val weeks = window.toSet()
		val assignees = tickets.assigneeIdsFor(rows.map { it.id })
		return rows.mapNotNull { ticket ->
			val at = ticket.completedAt ?: return@mapNotNull null
			val week = mondayOf(at.toLocalDate())
			if (week !in weeks) return@mapNotNull null
			val owners = assignees[ticket.id]?.size ?: 1
			Finished(
				ticket = ticket,
				completedAt = at,
				week = week,
				points = ticket.estimate?.let { it.toDouble() / owners },
			)
		}
	}

	private fun week(monday: LocalDate, ofWeek: List<Finished>) = MyFinishedWeek(
		isoYear = monday.get(WeekFields.ISO.weekBasedYear()),
		isoWeek = monday.get(WeekFields.ISO.weekOfWeekBasedYear()),
		startsOn = monday,
		finished = ofWeek.size,
		points = ofWeek.sumOf { it.points ?: 0.0 },
		unestimated = ofWeek.count { it.points == null },
	)

	/**
	 * The head of the same list the bars are drawn from, so the two cannot describe
	 * different weeks. Capped, and the cap is invisible on purpose: [MyStats.weeks] sums
	 * to the whole window, so a screen showing twenty rows under a chart of forty can say
	 * so without a second field claiming to be a total.
	 */
	private fun recent(closed: List<Finished>): List<MyFinishedTicket> {
		val head = closed.take(RECENT_TICKETS)
		return details.of(head.map { it.ticket }).mapIndexed { index, detail ->
			MyFinishedTicket(
				id = detail.ticket.id,
				identifier = detail.identifier,
				title = detail.ticket.title,
				completedAt = head[index].completedAt,
				estimate = detail.ticket.estimate,
			)
		}
	}

	// --- the strip -------------------------------------------------------------

	/**
	 * A deadline that has passed, and a day-granularity date passes at the end of its day.
	 *
	 * `KansoInstant` carries that distinction for exactly this: a ticket due "3 September"
	 * is not late at nine in the morning on the third, and treating its stored midnight as
	 * the deadline would put an alarm on the screen of somebody who still has the whole
	 * day. A date somebody gave a *time* to is late at that time, to the minute — rounding
	 * that one up to the end of the day would forgive an afternoon.
	 */
	private fun overdue(ticket: Ticket, now: OffsetDateTime): Boolean {
		val due = ticket.due ?: return false
		return if (due.hasTime) due.at.isBefore(now)
		else due.at.toLocalDate().isBefore(now.toLocalDate())
	}

	/**
	 * Their open tickets that something upstream is still holding.
	 *
	 * `ticket_dependencies` is finish-to-start, so only the arrows pointing *into* their
	 * tickets can block one: being somebody's predecessor is being waited on, not being
	 * blocked, and counting both directions would report the person holding the queue up as
	 * the person stuck in it.
	 *
	 * "Unfinished" is `CycleService.FINISHED_CATEGORIES` inverted, which is the categories
	 * rather than the names, and includes cancelled among the things that no longer hold
	 * anybody: a cancelled predecessor is a decision not to do the work, so reading it as a
	 * block would leave the successor waiting for something nobody will ever finish. The
	 * day a seventh status means "finished", this follows without anybody remembering the
	 * line is here.
	 *
	 * A predecessor that is missing from the read is one in the trash, and it blocks
	 * nothing — a deleted ticket is not work anybody is waiting for.
	 */
	private fun blocked(open: List<Ticket>): Int {
		if (open.isEmpty()) return 0
		val ids = open.mapTo(mutableSetOf()) { it.id }
		val incoming = dependencies.edgesTouching(ids)
			.filter { it.successorId in ids }
			.groupBy({ it.successorId }, { it.predecessorId })
		if (incoming.isEmpty()) return 0

		val upstream = tickets.findAllById(incoming.values.flatten().toSet()).associateBy { it.id }
		// The predecessors' categories, not the successors': what blocks somebody is the
		// state of the ticket they are waiting on, resolved against *its* team — which may
		// not be theirs, since a dependency crosses teams freely.
		val categories = statusCategories.of(upstream.values)
		return open.count { ticket ->
			incoming[ticket.id].orEmpty().any { id ->
				upstream[id]?.let { categories[it] !in CycleService.FINISHED_CATEGORIES } ?: false
			}
		}
	}

	// --- the commitment --------------------------------------------------------

	/**
	 * One row per cycle in progress that they hold work in.
	 *
	 * The candidate teams come from their own tickets rather than from `team_members`, and
	 * that is the narrower and the truer question: a commitment is about work somebody is
	 * carrying, and being on a team's member list is not what puts a ticket on your plate —
	 * nor is being off it what takes it away. It also costs one `findActive` per team that
	 * has any of their work rather than per team they belong to.
	 *
	 * A cycle they hold nothing in is absent rather than present with zeros. Zero committed
	 * points would read as somebody who signed up for nothing, and a cycle with none of
	 * their work in it says nothing about them at all.
	 */
	private fun commitments(person: User, open: List<Ticket>, closed: List<Finished>): List<MyCommitment> {
		val teamIds = (open.mapNotNull { it.teamId } + closed.mapNotNull { it.ticket.teamId }).toSet()
		if (teamIds.isEmpty()) return emptyList()

		val active = teamIds.mapNotNull { cycles.findActive(it) }
		if (active.isEmpty()) return emptyList()

		val byId = teams.findAllById(active.map { it.teamId }).associateBy { it.id }
		return active
			.mapNotNull { row -> byId[row.teamId]?.let { commitment(person, row, it) } }
			// By team name, for the reason `VelocityService.forTeam` sorts people by theirs:
			// ordering somebody's own teams by how much they committed to each would turn a
			// personal home into a league table of one.
			.sortedBy { it.teamName }
	}

	/**
	 * Their share of one running cycle.
	 *
	 * Cancelled work is out of every number, which is the ruling `CycleService.report`
	 * already makes about the same membership: a cancelled ticket is a decision not to do
	 * the work, so leaving it in the commitment would report a team that cut its own scope
	 * as a person who is behind.
	 *
	 * The membership read is `CycleRepository.ticketsIn`, the same one the cycle screen's
	 * own arithmetic runs on. Deliberately the same: these two numbers are read side by
	 * side and one of them being narrower than the other is worse than both sharing a
	 * fault, which would then be fixable in one place.
	 */
	private fun commitment(person: User, cycle: CycleRow, team: Team): MyCommitment? {
		val inCycle = cycles.ticketsIn(cycle.id)
		val categories = statusCategories.of(inCycle)
		val counted = inCycle.filter { categories[it] != StatusCategory.CANCELED }
		if (counted.isEmpty()) return null

		val assignees = tickets.assigneeIdsFor(counted.map { it.id })
		val mine = counted.filter { person.id in assignees[it.id].orEmpty() }
		if (mine.isEmpty()) return null

		val done = mine.filter { categories[it] == StatusCategory.COMPLETED }
		// Their share, by the rule `VelocityService.delivered` sets: the same 8 halved on
		// two plates. Unsized tickets contribute nothing and are tallied instead.
		fun share(ticket: Ticket): Double =
			ticket.estimate?.let { it.toDouble() / assignees.getValue(ticket.id).size } ?: 0.0

		return MyCommitment(
			teamId = team.id,
			teamName = team.name,
			teamKey = team.key,
			cycleId = cycle.id,
			cycleNumber = cycle.number,
			startsOn = cycle.startsOn,
			endsOn = cycle.endsOn,
			committed = mine.size,
			finished = done.size,
			committedPoints = mine.sumOf { share(it) },
			finishedPoints = done.sumOf { share(it) },
			unestimated = mine.count { it.estimate == null },
		)
	}

	// --- helpers ---------------------------------------------------------------

	/**
	 * The Monday the ISO week containing [day] begins on.
	 *
	 * `WeekFields.ISO` rather than the locale's, and rather than `with(DayOfWeek.MONDAY)`:
	 * the locale's week starts on Sunday in some of them, which would put a Sunday's work
	 * in the following bar, and `with(DayOfWeek.MONDAY)` walks to the Monday of the
	 * *locale's* week, which is the same bug spelled differently.
	 */
	private fun mondayOf(day: LocalDate): LocalDate = day.with(WeekFields.ISO.dayOfWeek(), 1)

	/** One finished ticket of theirs, folded once so nothing below re-derives its week. */
	private data class Finished(
		val ticket: Ticket,
		val completedAt: OffsetDateTime,
		val week: LocalDate,
		/** Their share of its points, or null where nobody sized it. */
		val points: Double?,
	)

	companion object {
		/**
		 * Twelve weeks. A quarter is the span somebody can recognise their own recent work
		 * in: shorter and one holiday is the whole chart, longer and the bars are too thin
		 * to compare and describe a person who has since changed teams.
		 */
		const val WEEKS = 12

		/**
		 * How many finished tickets travel with the chart. A list somebody scrolls is a
		 * different feature — the ticket list, with a filter — and this one is the
		 * "what did I just finish" that explains the last two bars.
		 */
		const val RECENT_TICKETS = 20
	}
}

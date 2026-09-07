package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.domain.DefaultStatus
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * How long the middle ticket took, and how much of the sample could not say.
 *
 * [medianHours] and not a mean, which is the one arithmetic choice on this type. A single
 * ticket that sat in review over a holiday is enough to drag a mean past every ticket in
 * the sample, so a mean cycle time is a number that describes an outlier and claims to
 * describe the work. The median is the ticket in the middle and moves only when the middle
 * moves. Null — never `0` — when nothing in the sample could be measured: zero hours is a
 * claim that work is instantaneous.
 *
 * [unmeasured] travels with the median for the reason `unestimated` travels with every
 * points total in this package: a sample that quietly drops half of itself reads as the
 * whole of it. It counts the delivered tickets whose start `activity` never recorded —
 * dragged from `todo` straight to `done`, or created finished — plus the handful whose
 * recorded start is somehow later than their completion, which the product cannot produce
 * but an import can. Both are "we cannot say", and the screen says how many.
 */
data class CycleTime(
	val medianHours: Double?,
	/** Tickets the median actually stands on. */
	val measured: Int,
	val unmeasured: Int,
)

/** One point of the trend: what a closed cycle's tickets took, cycle by cycle. */
data class CycleTimePoint(val cycle: Cycle, val cycleTime: CycleTime)

/**
 * Work in flight right now, and how long it has been in flight.
 *
 * [load] is the started part of the plate `OpenLoad` already draws, and the overlap is
 * deliberate: summing `byStatus[in_progress]` and `byStatus[in_review]` on the client would
 * be a second spelling of "in flight" free to disagree with `StatusCategory` the day a
 * seventh status means it. Gathered once here so the ages below and the counts above
 * describe the same tickets.
 *
 * The ages are what this type exists for. A WIP *count* is already derivable from the plate;
 * how long the oldest thing has been open is not, and it is the half that tells somebody
 * something they can act on — "five things in flight" is a policy question, "and one of them
 * has been in flight three weeks" is a ticket to go and look at.
 *
 * [oldestAgeHours] beside [medianAgeHours] rather than instead of it, because they answer
 * two different questions and a screen showing only one of them misleads either way: a
 * median alone hides the ticket that is stuck, and a maximum alone makes a healthy board
 * with one straggler look like a fire.
 */
data class Wip(
	val load: LoadSlice,
	val medianAgeHours: Double?,
	val oldestAgeHours: Double?,
	/** In flight with no recorded start — absent from both ages, never zero. */
	val unmeasured: Int,
)

/**
 * KAN-23, computed on read: cycle time, work in flight, and the trend across closed cycles.
 *
 * [trend] is oldest first, like `Progress.delivered`, because a trend is read left to right
 * in time and the wire is already in the order the chart draws it.
 */
data class Insights(
	/**
	 * Pooled over every ticket the closed cycles delivered, not the mean of [trend]'s
	 * medians. A median is a fact about a sample, and averaging six medians would weigh a
	 * cycle that delivered one ticket the same as one that delivered thirty.
	 */
	val cycleTime: CycleTime,
	val trend: List<CycleTimePoint>,
	val wip: Wip,
)

/**
 * Cycle time and WIP — **a query, not a new datum**, which is what KAN-23 said in as many
 * words and is why there is no migration behind this file.
 *
 * `activity` has timestamped every `status_changed` since V8, so the instant work started on
 * a ticket is already on disk. Nothing here is stored and nothing is collected: the same
 * house rule `VelocityService` and `ProgressService` state for their own numbers, and it
 * holds harder here. A stored cycle time would be wrong the moment somebody reopened a
 * ticket, and a stored WIP age would be wrong every hour.
 *
 * **One clock rule, written once and used by both numbers: the clock starts the first time
 * work started, and never restarts.** `ActivityRepository.firstEnteredAt` takes the `MIN`,
 * so a ticket parked back in `todo` and picked up again keeps its original start. That makes
 * both numbers pessimistic on purpose. Cycle time is the number that ought to get *worse*
 * when work is churned, and taking the latest start would let a ticket bounced four times
 * report the twenty minutes of its final touch — a metric that improves when the process
 * degrades. `VelocityService` makes the same call in the other direction for the same
 * reason: it under-reports rather than over-reports, because the safe direction is always
 * the one that does not flatter.
 *
 * **Where the clock starts is read off the category, never off a status name.** Any
 * transition into [StatusCategory.STARTED] starts it, which is also the whole answer to
 * tickets that skipped statuses: a ticket taken `todo` → `in_review` without ever being
 * `in_progress` started when it reached review, and a seventh started status would be picked
 * up here without this file being edited.
 *
 * **Where it stops is `completedAt`, and not an activity row.** That column is what
 * `VelocityService` already calls "delivered", so a ticket counted in cycle 4's bar has its
 * cycle time measured to the instant that bar counted it. An end read from `activity`
 * instead could put the two in different cycles, and a median that disagreed with the chart
 * above it is a number nobody could check. `TicketService` clears `completedAt` on the way
 * out of a completed status and stamps it fresh on the way back in, so a reopened ticket
 * measures from its first start to its *last* completion — the whole saga, rework included.
 *
 * **In hours, and never divided by a working day.** The pace on this screen is per working
 * day and this number is not, deliberately: elapsed time is what somebody waiting for a
 * ticket experienced, and a weekend the ticket sat through really did happen to them.
 * Excluding weekends would also need the per-person calendar `VelocityService` refuses to
 * guess at — public holidays are per country and time off is per person — and an hour is the
 * one unit that needs no calendar at all. The client turns it into days for reading.
 */
@Service
class CycleTimeService(
	private val velocity: VelocityService,
	private val activity: ActivityRepository,
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
) {

	/**
	 * [assigneeId] null is no filter at all: the team's tickets rather than one person's —
	 * the same convention `ProgressService.load` uses one file up.
	 *
	 * A ticket's cycle time is not split between its assignees, which is the opposite of
	 * what `VelocityService` does with the same join table and is right for the same reason
	 * it is right there. Points are a quantity two people share; an elapsed hour is not
	 * divisible, and half of a ticket's four days is not two days of anything.
	 */
	@Transactional(readOnly = true)
	fun forCycles(cycles: List<Cycle>, teamId: UUID, assigneeId: UUID?, now: OffsetDateTime = OffsetDateTime.now()): Insights {
		// Cycle membership is per cycle, so `finishedIn` is one read each. Everything after
		// it is read **once for the whole page** rather than once per bar: one assignee join
		// and one pass over `activity` for every ticket the closed cycles delivered. That is
		// not only cheaper, it is what makes the headline and the trend provably the same
		// sample — both are folded out of one list of spans rather than from two reads free
		// to disagree.
		val theirs = mine(cycles.map { cycle -> cycle to velocity.finishedIn(cycle) }, assigneeId)
		val startedAt = activity.firstEnteredAt(
			theirs.flatMap { it.second }.map { it.id },
			IN_FLIGHT_WIRE,
		)
		val spans = theirs.map { (cycle, delivered) -> cycle to spans(delivered, startedAt) }

		return Insights(
			// Pooled from the very spans the points below are drawn from, and not the mean of
			// their medians. See `Insights.cycleTime`.
			cycleTime = cycleTimeOf(
				hours = spans.flatMap { it.second.hours },
				unmeasured = spans.sumOf { it.second.unmeasured },
			),
			trend = spans
				.map { (cycle, span) -> CycleTimePoint(cycle, cycleTimeOf(span.hours, span.unmeasured)) }
				.reversed(),
			wip = wip(teamId, assigneeId, now),
		)
	}

	/**
	 * The elapsed hours of each delivered ticket that has both ends, and a count of the rest.
	 *
	 * Kept as raw spans rather than reduced to a median here, which is the whole point of the
	 * split: the pooled figure is the median of every span and each trend point is the median
	 * of one cycle's, and both come off this one list. A version returning a [CycleTime] would
	 * force the caller either to read `activity` a second time for the pooled number or to
	 * average six medians — the two mistakes this shape rules out.
	 */
	private fun spans(delivered: List<Ticket>, startedAt: Map<UUID, OffsetDateTime>): Spans {
		val hours = delivered.mapNotNull { ticket ->
			val from = startedAt[ticket.id] ?: return@mapNotNull null
			val to = ticket.completedAt ?: return@mapNotNull null
			// Negative is unreachable through the product and reachable through an import,
			// and a negative hour in a median is worse than a gap in the sample.
			elapsedHours(from, to).takeIf { it >= 0 }
		}
		return Spans(hours, unmeasured = delivered.size - hours.size)
	}

	private fun cycleTimeOf(hours: List<Double>, unmeasured: Int) =
		CycleTime(medianHours = median(hours), measured = hours.size, unmeasured = unmeasured)

	/**
	 * What is in somebody's hands right now, and since when.
	 *
	 * Scoped to the team *and its sub-teams*, which is the scope `ProgressService.load` and
	 * `WorkloadService` already gather — a plate cut one way on one half of a screen and
	 * another way on the other half is a screen that cannot be added up.
	 */
	private fun wip(teamId: UUID, assigneeId: UUID?, now: OffsetDateTime): Wip {
		val inFlight = tickets.search(
			teamIds = teams.descendantIds(teamId),
			statuses = IN_FLIGHT_STATUSES,
			assigneeId = assigneeId,
			// The same cap and the same argument `ProgressService` gives: an uncapped scan
			// is how one page takes the instance down.
			limit = WorkloadService.SCAN_LIMIT,
		)
		val startedAt = activity.firstEnteredAt(inFlight.map { it.id }, IN_FLIGHT_WIRE)
		val ages = inFlight.mapNotNull { ticket ->
			startedAt[ticket.id]?.let { elapsedHours(it, now) }?.takeIf { it >= 0 }
		}
		return Wip(
			load = LoadSlice(
				tickets = inFlight.size,
				points = inFlight.sumOf { it.estimate ?: 0 },
				unestimated = inFlight.count { it.estimate == null },
			),
			medianAgeHours = median(ages),
			oldestAgeHours = ages.maxOrNull(),
			unmeasured = inFlight.size - ages.size,
		)
	}

	/**
	 * Minutes and then divided, rather than `toHours`, which truncates.
	 *
	 * A ticket opened and closed inside an afternoon is not a ticket that took no time, and
	 * a whole sample of same-day tickets truncated to zero would report a median of nought
	 * hours — the one number on this screen that would read as a boast.
	 */
	private fun elapsedHours(from: OffsetDateTime, to: OffsetDateTime): Double =
		Duration.between(from, to).toMinutes() / 60.0

	/**
	 * One person's share of every cycle's delivered set, or all of it when nobody was named.
	 *
	 * Takes the whole grouped list rather than one cycle's at a time so the assignee join
	 * runs once for the page instead of once per bar.
	 */
	private fun mine(
		delivered: List<Pair<Cycle, List<Ticket>>>,
		assigneeId: UUID?,
	): List<Pair<Cycle, List<Ticket>>> {
		if (assigneeId == null) return delivered
		val assignees = tickets.assigneeIdsFor(delivered.flatMap { it.second }.map { it.id })
		return delivered.map { (cycle, all) ->
			cycle to all.filter { assigneeId in assignees[it.id].orEmpty() }
		}
	}

	/** The two halves of a sample: what could be measured, and how much could not. */
	private data class Spans(val hours: List<Double>, val unmeasured: Int)

	/** Null on an empty sample, and the mean of the two middles on an even one. */
	private fun median(values: List<Double>): Double? {
		if (values.isEmpty()) return null
		val sorted = values.sorted()
		val middle = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
	}

	companion object {
		/**
		 * In flight means somebody is holding it, which is [StatusCategory.STARTED] — the
		 * same reading `DefaultStatus.category` gives for putting `in_review` here, and the
		 * same reason: a reviewer is work in flight.
		 *
		 * Off the category rather than spelled as two names, so this follows the vocabulary
		 * without anybody remembering the line is here. `WorkloadService.OPEN_STATUSES` is
		 * deliberately wider — a `todo` ticket is a load somebody will pick up, and it is
		 * not yet work in progress.
		 */
		val IN_FLIGHT_STATUSES = DefaultStatus.entries.filter { it.category == StatusCategory.STARTED }

		/** The same list as the `payload ->> 'to'` filter reads it. */
		val IN_FLIGHT_WIRE = IN_FLIGHT_STATUSES.map { it.wire }
	}
}

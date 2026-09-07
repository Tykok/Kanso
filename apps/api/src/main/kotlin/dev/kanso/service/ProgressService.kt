package dev.kanso.service

import dev.kanso.domain.Project
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * One bar of the delivered-points chart.
 *
 * [points] is a Double for the reason `VelocityCycle.points` is: a ticket with two
 * assignees gives half of itself to each, so a person's share of a cycle is not an
 * integer. Rounding it here would make the bars disagree with the mean drawn above them.
 *
 * [countedTowardsVelocity] is what stops the chart and the headline number from reading as
 * two contradictory claims. The chart is drawn over [ProgressService.CHART_CYCLES] cycles
 * and the velocity is measured over [VelocityService.DEFAULT_CYCLES] of them, so some bars
 * are history the number is not standing on — and a chart that did not say which would
 * invite the reader to average six bars by eye and get a different answer. False on every
 * bar when a declared velocity is the one in force, which is correct: it was measured over
 * nothing.
 */
data class DeliveredCycle(
	val cycle: Cycle,
	val points: Double,
	val workingDays: Int,
	/** Their finished tickets in this cycle that nobody sized — absent from [points], never zero. */
	val unestimated: Int,
	val countedTowardsVelocity: Boolean,
)

/**
 * One cut of an open plate: how many things, how heavy the sized part is, and how much of
 * it the weight cannot speak for.
 *
 * One type for both cuts — by status and by project — rather than two shapes that would
 * drift. [unestimated] travels with [points] here for the same reason it does everywhere
 * else in this package: a sum that quietly leaves out half a plate reads as the whole of
 * it.
 */
data class LoadSlice(val tickets: Int, val points: Int, val unestimated: Int)

/** [project] is null for the tickets that belong to none — a pile, not a project. */
data class ProjectLoad(val project: Project?, val load: LoadSlice)

/**
 * What this person is carrying right now, and what that is worth in days.
 *
 * [workingDays] is [LoadSlice.points] divided by the velocity in force, and it is null
 * exactly when there is no velocity to divide by — never zero, which would read as an
 * empty plate. It is *not* nulled when the plate has no points: an empty plate genuinely
 * is zero days, and [LoadSlice.unestimated] is what says how much of a non-empty one the
 * division could not see.
 */
data class OpenLoad(val load: LoadSlice, val byStatus: Map<DefaultStatus, LoadSlice>, val byProject: List<ProjectLoad>, val workingDays: Double?)

/**
 * Everything screen 40 draws about one person, computed on read.
 *
 * [person] is carried rather than assumed to be the caller. It is the one field that makes
 * this shape answerable for somebody else, which is what KAN-41 will ask of it.
 */
data class Progress(
	val person: User,
	val velocity: EffectiveVelocity,
	/**
	 * Oldest first, which is the opposite of what `VelocityService` returns.
	 *
	 * A trend is read left to right in time, so the wire is already in the order the chart
	 * draws it. Reversing on the client would mean every future caller — the team view
	 * included — remembering to, and one that forgot would draw a descent as an ascent.
	 */
	val delivered: List<DeliveredCycle>,
	val load: OpenLoad,
	/**
	 * KAN-23, and the field KAN-40 left a hole for rather than filling with a stub.
	 *
	 * On this shape rather than behind a route of its own: the subject, the team and the
	 * closed cycles are already resolved here, and a second endpoint would be a second copy
	 * of `ProgressAccess`'s three branches guarding the same figures about the same person.
	 */
	val insights: Insights,
)

/**
 * The same screen aimed at a team, and **aggregates only, by construction**.
 *
 * There is no list of people on this shape. That is the ticket's rule and it is enforced
 * here rather than in the controller, because a field on a response is a field somebody
 * draws: ranking people by points delivered is the one chart in this feature that changes
 * what people do with their tickets rather than describing what they did, and a response
 * carrying the rows would be one `sort` away from it whatever the screen currently draws.
 * [TeamPace] makes the same refusal one level down.
 *
 * [load] is the team's whole open plate cut by status and by project — the same [OpenLoad]
 * the personal page draws, gathered without an assignee filter. Cut *by person* it already
 * exists, on the workload screen, open to every reader; it is not repeated here, so nothing
 * on this response pairs a person with a number.
 */
data class TeamProgress(
	val team: Team,
	val pace: TeamPace,
	/** Oldest first, like [Progress.delivered]. */
	val delivered: List<DeliveredCycle>,
	val load: OpenLoad,
	/**
	 * The trend the ticket asks for **per team**, and the one figure on this shape that is
	 * about tickets rather than about points.
	 *
	 * It names no person, so it does not reopen the ranking this type refuses: a median over
	 * a team's delivered tickets has no row to sort. That is worth stating because cycle time
	 * is the metric most often turned into one, and the reason it cannot be here is the same
	 * reason nothing else can — there is no per-person field to put it on.
	 */
	val insights: Insights,
)

/**
 * One person's progress: what they delivered, at what pace, and what they are still holding.
 *
 * **Nothing here knows who is asking.** [forPerson] takes the subject as an argument and
 * reads no security context, which is deliberate and is the whole of the seam this file
 * leaves for the other-person view: that ticket adds a route, a permission rule and
 * nothing else. Baking "the current user" in at this level is what would force a second
 * copy of the arithmetic the day a second caller appeared — the mistake `VelocityService`
 * already refused to make, for the same reason.
 *
 * **Computed on read, and nothing is stored.** Same rule as the cycle report and the
 * velocity underneath it: a stored count of open points is wrong the moment somebody drags
 * a ticket, and a stored delivered-per-cycle is wrong again when somebody resizes a ticket
 * that closed last month. There is no migration behind this file.
 *
 * **Scoped to one team, like every other screen that draws a cycle.** A cycle is one
 * team's calendar, so the velocity is per team — and comparing a plate gathered across the
 * whole instance against one team's pace would divide the wrong numerator by the wrong
 * denominator. So the load is the subject's open tickets in this team *and its sub-teams*,
 * which is the same scope `WorkloadService` gathers, and the page names the team it is
 * about.
 */
@Service
class ProgressService(
	private val effective: EffectiveVelocityService,
	private val velocity: VelocityService,
	private val cycleTime: CycleTimeService,
	private val tickets: TicketRepository,
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
) {

	@Transactional(readOnly = true)
	fun forPerson(subject: User, teamId: UUID): Progress {
		// Two reads of the closed cycles rather than one, and the duplication is the cheaper
		// of the two mistakes available. The arbitration is defined over three cycles by
		// rule; the chart wants six because two bars are not a trend. Asking for six and
		// averaging the newest three here would put a second copy of "the mean of the rates,
		// not the pooled total" in this file, free to disagree with the first the day it
		// moves. The overlap costs three extra cycle-deliveries on one person's page.
		val inForce = effective.forPerson(subject, teamId)
		val history = velocity.forPerson(subject, teamId, over = CHART_CYCLES)
		return Progress(
			person = subject,
			velocity = inForce,
			// A declared velocity was measured over nothing, so no bar is one the number
			// stands on. Said here rather than inside `bars`, which knows about cycles and
			// deliberately nothing about the arbitration above them.
			delivered = bars(
				history.cycles,
				measuredOver = if (inForce.source == VelocitySource.MEASURED) inForce.measuredCycles else 0,
			),
			load = load(subject.id, teamId, inForce.perWorkingDay),
			// The very cycles the bars are drawn from, handed over rather than re-read, so
			// the median and the bar above it are measured over one set of tickets.
			insights = cycleTime.forCycles(history.cycles.map { it.cycle }, teamId, subject.id),
		)
	}

	/**
	 * The same screen for a whole team.
	 *
	 * Two reads of the closed cycles again, and the same arbitration between them as
	 * [forPerson]: the pace is the mean over [VelocityService.DEFAULT_CYCLES] and the chart
	 * is drawn over [CHART_CYCLES], so the extra bars are marked rather than hidden. There
	 * is no declared velocity to weigh a team's measurement against, so `measuredOver` is
	 * simply how many cycles the mean found — including the case where that mean is zero,
	 * which for a team is a measurement worth drawing dark rather than an absence.
	 */
	@Transactional(readOnly = true)
	fun forTeam(team: Team): TeamProgress {
		val inForce = velocity.paceForTeam(team.id)
		val history = velocity.paceForTeam(team.id, over = CHART_CYCLES)
		return TeamProgress(
			team = team,
			pace = inForce,
			delivered = bars(history.cycles, measuredOver = inForce.cycles.size),
			// No assignee, so the plate is the team's whole open board — the scope
			// `WorkloadService` gathers, sub-teams included.
			load = load(assigneeId = null, teamId = team.id, perWorkingDay = inForce.perWorkingDay),
			// No assignee here either, so the median is over everything the team delivered —
			// including the tickets nobody was assigned, which a person's page cannot count.
			insights = cycleTime.forCycles(history.cycles.map { it.cycle }, team.id, assigneeId = null),
		)
	}

	/**
	 * The bars, in time order, each knowing whether the headline number stands on it.
	 *
	 * The index is the test because both lists come off the same `cycles.closed(teamId)` in
	 * the same order: the newest [measuredOver] of them are exactly the ones the mean was
	 * taken over. Matching on cycle id instead would be a set lookup that says the same
	 * thing while hiding that it depends on the order.
	 *
	 * Nothing in here knows whose cycles these are, which is why the team view draws its
	 * chart through it rather than through a copy — the two differ in what was measured, not
	 * in how a measurement becomes a chart.
	 */
	private fun bars(newestFirst: List<VelocityCycle>, measuredOver: Int): List<DeliveredCycle> =
		newestFirst
			.mapIndexed { at, measured ->
				DeliveredCycle(
					cycle = measured.cycle,
					points = measured.points,
					workingDays = measured.workingDays,
					unestimated = measured.unestimated,
					countedTowardsVelocity = at < measuredOver,
				)
			}
			.reversed()

	/** [assigneeId] null is no filter at all: the team's plate rather than one person's. */
	private fun load(assigneeId: UUID?, teamId: UUID, perWorkingDay: Double?): OpenLoad {
		val open = tickets.search(
			teamIds = teams.descendantIds(teamId),
			statuses = WorkloadService.OPEN_STATUSES,
			assigneeId = assigneeId,
			// The same cap and the same argument: a person holding more open tickets than
			// this has a bigger problem than an off-by-some chart, and an uncapped scan is
			// how one page takes the instance down.
			limit = WorkloadService.SCAN_LIMIT,
		)
		val whole = slice(open)
		return OpenLoad(
			load = whole,
			// Every open status present, zeros included, so the chart's legend is the same
			// list every time somebody opens the page rather than a shape that changes with
			// the plate. Keyed off the category, as `WorkloadService` keys its own row.
			byStatus = WorkloadService.OPEN_STATUSES.associateWith { status ->
				slice(open.filter { it.status == status })
			},
			byProject = byProject(open),
			// Zero is not a pace anything can be divided by — it is what somebody who has
			// never delivered looks like from here — and dividing by it would send an
			// infinity to the client. `EffectiveVelocityService` already refuses a measured
			// zero; this guards the declared one, which nothing stops being written as 0.
			workingDays = perWorkingDay?.takeIf { it > 0 }?.let { whole.points / it },
		)
	}

	private fun byProject(open: List<Ticket>): List<ProjectLoad> {
		val grouped = open.groupBy { it.projectId }
		val named = projects.findAllById(grouped.keys.filterNotNull()).associateBy { it.id }
		return grouped
			.map { (projectId, carried) -> ProjectLoad(projectId?.let { named[it] }, slice(carried)) }
			// Heaviest first, and the no-project pile last however big it is: the question is
			// which piece of work this plate is mostly made of, and "not filed anywhere" is
			// the footnote. Points before count, because points are what the days above are
			// divided from — a project of three 13s outranks one of eight 1s.
			.sortedWith(
				compareBy<ProjectLoad> { it.project == null }
					.thenByDescending { it.load.points }
					.thenByDescending { it.load.tickets }
					.thenBy { it.project?.name.orEmpty() },
			)
	}

	private fun slice(carried: List<Ticket>) = LoadSlice(
		tickets = carried.size,
		points = carried.sumOf { it.estimate ?: 0 },
		unestimated = carried.count { it.estimate == null },
	)

	companion object {
		/**
		 * Six closed cycles on the chart, against the three the mean is taken over.
		 *
		 * Three bars is a mean with a shape drawn round it, not a trend — and the page's
		 * whole argument is that a personal number means something only against the same
		 * person over time. Six is roughly a quarter at a fortnightly cadence: long enough
		 * to see a direction, short enough that it is still this team. The bars beyond the
		 * measured three are marked rather than hidden, so the reader can see the number's
		 * window without losing the history around it.
		 */
		const val CHART_CYCLES = 6
	}
}

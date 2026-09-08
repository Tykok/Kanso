package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.CycleRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.util.UUID

/**
 * One closed cycle's evidence about one person.
 *
 * [points] is a Double and the only fractional number in this file: a ticket with two
 * assignees contributes half of itself to each of them, so a person's share of an 8 is a
 * 4 and a person's share of a 5 is not an integer at all. Rounding it here would make the
 * three cycles underneath a mean disagree with the mean.
 *
 * [workingDays] travels with it rather than being recomputed by the reader, because it is
 * the half of the fraction that explains why two cycles of the same points read
 * differently — and because a cycle whose dates a team changed after the fact would
 * otherwise be re-divided by a length that is no longer the one this number used.
 */
data class VelocityCycle(
	val cycle: Cycle,
	val points: Double,
	val workingDays: Int,
	val perWorkingDay: Double,
	/** Their finished tickets in this cycle that nobody sized — absent from [points], never zero. */
	val unestimated: Int,
)

/**
 * What one person delivered, per working day, over the cycles that can say.
 *
 * [perWorkingDay] is null and not zero when [cycles] is empty. The distinction is the
 * whole point of the type: zero is a measurement — this person closed nothing across
 * three finished cycles — and null is the absence of one. A team that has never closed a
 * cycle would otherwise read as a team of people who deliver nothing, and the declared
 * velocity that stands in until a team has history has no way to know when to stand down.
 */
data class PersonVelocity(
	val person: User,
	val perWorkingDay: Double?,
	/** Newest first. Its size is how much history the mean is standing on. */
	val cycles: List<VelocityCycle>,
	val unestimated: Int,
)

data class TeamVelocity(val cycles: List<Cycle>, val rows: List<PersonVelocity>)

/**
 * A whole team's pace, and the closed cycles it was taken over.
 *
 * [VelocityCycle] reused for the rows rather than a team-shaped twin of it, because that
 * type never knew whose cycle it was describing — it carries a cycle, a haul, a length and
 * a rate, and all four mean the same thing one height up.
 *
 * [perWorkingDay] is null when [cycles] is empty, and the distinction is the one
 * [PersonVelocity] makes: zero is a team that closed nothing across finished cycles, null
 * is a team with no finished cycle to ask. There is no `source` field and no arbitration
 * behind this number — a declared velocity is one person's estimate of themselves, and
 * nobody declares a team's.
 */
data class TeamPace(
	val perWorkingDay: Double?,
	/** Newest first, like [PersonVelocity.cycles]. */
	val cycles: List<VelocityCycle>,
)

/**
 * Velocity: what a person actually delivered, in points per working day.
 *
 * Three choices make the number mean anything, and each of them is a refusal.
 *
 * *Per working day, not per cycle.* Kanso lets a team pick any two dates for a cycle, so
 * "12 points" is a different claim after a fortnight than after three days, and the two
 * cannot be averaged. Dividing by the working days the cycle contained is what makes a
 * team's cycles addable — and what makes one person's number comparable to their own from
 * last quarter, when the team was running weeks instead of fortnights.
 *
 * *Closed cycles only.* The cycle in progress is a partial mean: it falls every morning
 * that nothing closes and jumps whenever something does. A velocity that changes during
 * the day is not a velocity, it is a burn-down — and there is already one of those on
 * screen 19.
 *
 * *Computed on read.* The rule `V10__cycles_and_views.sql` states for the cycle screen
 * holds here for the same reason: a stored rate is wrong from the moment the next ticket
 * closes, is wrong again when somebody resizes a ticket that closed last month, and is a
 * number nobody can explain when it disagrees with the cycles listed underneath it. There
 * is no cached column and there will not be one.
 *
 * There is no controller *here*, and the reason has not changed: a route exposing one
 * person's output to anyone who can guess a user id is not a thing to add before somebody
 * has decided who may read it. `VelocityController` is the narrowest answer that unblocks
 * a screen — you may read your own — and it reaches this file through
 * `EffectiveVelocityService`, which is also where the rule arbitrating this number against
 * a declared one lives. Nothing in this file knows that a declared velocity exists.
 */
@Service
class VelocityService(
	private val cycles: CycleService,
	private val membership: CycleRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val statusCategories: StatusCategories,
) {

	/**
	 * Everyone the measured cycles saw finish something, and how fast.
	 *
	 * People with nothing in those cycles are absent rather than present with a zero: a
	 * zero is a claim about somebody who was there, and this read cannot tell a person who
	 * delivered nothing from a person who had not joined yet. There is no unassigned
	 * bucket either, unlike the workload chart — an unassigned ticket is still a load
	 * somebody will pick up, but nobody's velocity.
	 */
	@Transactional(readOnly = true)
	fun forTeam(teamId: UUID, over: Int = DEFAULT_CYCLES): TeamVelocity {
		val measured = measure(teamId, over)
		val people = users.findAllById(measured.flatMap { it.second.keys }.toSet())
		return TeamVelocity(
			cycles = measured.map { it.first },
			// By name. Sorting people by their output would turn a diagnostic into a
			// league table, and the one number this screen has is the one number that
			// should never be read as a ranking.
			rows = people.sortedBy { it.displayName }.map { row(it, measured) },
		)
	}

	/**
	 * One person's, including the null that says the cycles cannot answer.
	 *
	 * [teamId] rather than "their teams": a cycle is one team's plan, and a person who
	 * works across two teams has two paces measured against two different calendars.
	 * Averaging those would divide their work by days counted twice.
	 */
	@Transactional(readOnly = true)
	fun forPerson(person: User, teamId: UUID, over: Int = DEFAULT_CYCLES): PersonVelocity =
		row(person, measure(teamId, over))

	/**
	 * The team's own pace — and it is **not** the sum of its members' paces.
	 *
	 * Two differences, both deliberate. It counts a delivered ticket nobody was assigned,
	 * which [forPerson] cannot: an unowned ticket has no person to credit, but a team that
	 * shipped it shipped it. And it never splits an estimate between assignees, because
	 * there is nobody to split it between — the split in [delivered] exists so that an 8
	 * done by two people is not reported as sixteen points, and at team level the 8 is
	 * simply an 8.
	 *
	 * So this is the same question [CycleService.report] answers for one cycle in points,
	 * asked over the last [over] closed ones and divided by their working days. It shares
	 * every primitive with the per-person read — [measurable] picks the cycles, [finishedIn]
	 * decides what counts as delivered — so the two can disagree about a team's history
	 * only by disagreeing about those, which is the point of them being one function each.
	 *
	 * There is no per-person breakdown on this shape and there is not going to be one.
	 * [TeamVelocity] already carries the rows and deliberately never reached a controller;
	 * this type is what a team screen can be handed without handing it a league table.
	 */
	@Transactional(readOnly = true)
	fun paceForTeam(teamId: UUID, over: Int = DEFAULT_CYCLES): TeamPace {
		val perCycle = measurable(teamId, over).map { cycle ->
			val finished = finishedIn(cycle)
			val days = workingDays(cycle)
			val points = finished.sumOf { it.estimate ?: 0 }.toDouble()
			VelocityCycle(
				cycle = cycle,
				points = points,
				workingDays = days,
				perWorkingDay = points / days,
				unestimated = finished.count { it.estimate == null },
			)
		}
		return TeamPace(
			// The mean of the rates, for the reason `row` takes the mean of the rates: each
			// closed cycle is one observation of how this team works, and pooling would let a
			// long quiet fortnight outvote two short busy ones on length alone.
			perWorkingDay = if (perCycle.isEmpty()) null else perCycle.map { it.perWorkingDay }.average(),
			cycles = perCycle,
		)
	}

	// --- the measurement -------------------------------------------------------

	/**
	 * The cycles that can be measured, newest first, paired with what each says.
	 *
	 * Filtered before [over] is applied, not after: a cycle with no working days is not a
	 * slow cycle, it is an undivisible one, and letting it consume one of the three slots
	 * would quietly narrow the sample that the answer is standing on.
	 */
	private fun measure(teamId: UUID, over: Int): List<Pair<Cycle, Map<UUID, Delivered>>> =
		measurable(teamId, over).map { it to delivered(it) }

	/** The cycles themselves, for the reader that wants no attribution — see [paceForTeam]. */
	private fun measurable(teamId: UUID, over: Int): List<Cycle> =
		cycles.closed(teamId)
			.filter { workingDays(it) > 0 }
			.take(over)

	private fun row(person: User, measured: List<Pair<Cycle, Map<UUID, Delivered>>>): PersonVelocity {
		val perCycle = measured.map { (cycle, byPerson) ->
			val theirs = byPerson[person.id] ?: Delivered()
			val days = workingDays(cycle)
			VelocityCycle(
				cycle = cycle,
				points = theirs.points,
				workingDays = days,
				perWorkingDay = theirs.points / days,
				unestimated = theirs.unestimated,
			)
		}
		return PersonVelocity(
			person = person,
			// The mean of the rates, not the pooled total over the pooled days: each cycle
			// is one observation of how this person works, and pooling would let a long
			// quiet cycle outvote two short busy ones for no reason other than its length.
			// Empty means unmeasurable, which is the one case that answers null.
			perWorkingDay = if (perCycle.isEmpty()) null else perCycle.map { it.perWorkingDay }.average(),
			cycles = perCycle,
			unestimated = perCycle.sumOf { it.unestimated },
		)
	}

	/**
	 * What each person finished in this cycle, their share of it.
	 *
	 * Split equally between a ticket's assignees, which is the opposite of what the
	 * workload chart does with the same join table, and deliberately. A load is a thing
	 * two people are both carrying, so an 8 belongs whole on both their plates; a delivery
	 * happened once, so an 8 credited whole to both of them would report sixteen points
	 * the team never shipped and inflate two velocities at once. Equally rather than by
	 * some guess at who did more: nothing here knows that, and inventing it would be a
	 * worse lie than the even split.
	 *
	 * A ticket nobody was assigned is skipped. It was delivered, and the cycle report
	 * counts it, but this file answers "how fast does this person work" and an unowned
	 * ticket has no person to attribute it to.
	 */
	private fun delivered(cycle: Cycle): Map<UUID, Delivered> {
		val finished = finishedIn(cycle)
		if (finished.isEmpty()) return emptyMap()

		val assignees = tickets.assigneeIdsFor(finished.map { it.id })
		val byPerson = mutableMapOf<UUID, Delivered>()
		for (ticket in finished) {
			val owners = assignees[ticket.id].orEmpty()
			if (owners.isEmpty()) continue
			val share = ticket.estimate?.let { it.toDouble() / owners.size }
			for (owner in owners) {
				val soFar = byPerson[owner] ?: Delivered()
				// Left out of the sum and tallied instead, never added as a zero: a person
				// who ships work nobody sized is not a person who ships nothing, and a
				// velocity that fell for it would be a fine for forgetting to estimate.
				byPerson[owner] = if (share == null) {
					soFar.copy(unestimated = soFar.unestimated + 1)
				} else {
					soFar.copy(points = soFar.points + share)
				}
			}
		}
		return byPerson
	}

	/**
	 * Two conditions, and both are load-bearing.
	 *
	 * The ticket is in this cycle — `ticket_cycles` is keyed on the ticket, so membership
	 * names exactly one cycle, while nothing stops a team's date ranges from overlapping
	 * and a completion date alone could therefore name two. And it closed between the
	 * cycle's own two dates, which is what "delivered during that cycle" means: a ticket
	 * dragged into a finished cycle months later did not happen in those days, and
	 * crediting it there would move a number that has already been read.
	 *
	 * The cost of the second condition is that a cycle nobody closed on time loses the
	 * work done past its end date. That is the direction to fail in — a velocity that
	 * under-reports is a team's own record-keeping showing through, while one that counts
	 * work twice is a number that cannot be corrected by anybody who notices.
	 *
	 * The category rather than the name of a status: "finished" is a meaning, and the day
	 * a seventh status means it, this has to follow without anybody remembering the line
	 * is here.
	 */
	/**
	 * What this cycle delivered, before anybody asks who delivered it.
	 *
	 * One function so that "delivered during this cycle" is one rule. The per-person read
	 * and the team read differ in what they do with the list, never in what is on it.
	 *
	 * **Public for `CycleTimeService`, which is a third reader of the same rule.** Cycle
	 * time is measured over the tickets a closed cycle delivered, and the two numbers on
	 * that screen disagreeing about which tickets those were is the one failure neither
	 * could be debugged from — a median over a set the bar above it did not count. So it
	 * reads this rather than a fourth copy of the two conditions documented above.
	 */
	fun finishedIn(cycle: Cycle): List<Ticket> =
		membership.ticketsIn(cycle.id).let { inCycle ->
			val categories = statusCategories.of(inCycle)
			inCycle.filter { it.reachedDoneDuring(cycle, categories) }
		}

	private fun Ticket.reachedDoneDuring(cycle: Cycle, categories: Categories): Boolean {
		if (categories[this] != StatusCategory.COMPLETED) return false
		val on = completedAt?.toLocalDate() ?: return false
		return !on.isBefore(cycle.startsOn) && !on.isAfter(cycle.endsOn)
	}

	/**
	 * The cycle's length in days somebody was expected to work.
	 *
	 * Weekends only. There is no holiday calendar and this ticket does not add one: public
	 * holidays are per country and time off is per person, so a table of them is a feature
	 * with its own screen, not a constant to guess at. The weekend is the part every team
	 * running this already agrees on, and getting it right is what stops a cycle with a
	 * bank holiday weekend in it from reading as a slow fortnight.
	 *
	 * Zero is possible — a two-day cycle over a Saturday and Sunday — and is why [measure]
	 * drops such a cycle rather than dividing by it.
	 */
	private fun workingDays(cycle: Cycle): Int =
		generateSequence(cycle.startsOn) { it.plusDays(1) }
			.takeWhile { !it.isAfter(cycle.endsOn) }
			.count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

	/** One person's haul from one cycle: the part that was sized, and the part that was not. */
	private data class Delivered(val points: Double = 0.0, val unestimated: Int = 0)

	companion object {
		/**
		 * Three cycles. Long enough that one interrupted fortnight does not become the
		 * answer, short enough that the number still describes how the team works now
		 * rather than how it worked two quarters and one reorganisation ago.
		 */
		const val DEFAULT_CYCLES = 3
	}
}

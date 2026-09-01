package dev.kanso.service

import dev.kanso.domain.User
import dev.kanso.settings.PreferencesService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Which of the two numbers is the one Kanso plans with.
 *
 * Named rather than inferred. A caller handed a rate and a pair of nullable inputs would
 * have to re-derive the rule to caption it, every caller would derive it slightly
 * differently, and the day the threshold moves the captions would stay where they were.
 */
enum class VelocitySource {
	/** The person's own estimate of themselves. Not enough history to overrule it yet. */
	DECLARED,

	/** Measured from closed cycles. Whatever was declared is now a reference, not an input. */
	MEASURED,

	/** Neither. Nothing to plan with, and the screen has to say so rather than draw a blank. */
	NONE,
}

/**
 * One person's pace, the rule already applied, and both inputs still visible.
 *
 * [perWorkingDay] is the number to divide points by, and null exactly when [source] is
 * [VelocitySource.NONE] — the same null-is-not-zero distinction `VelocityService` makes,
 * carried one level up.
 *
 * [declared] and [measured] are both here whatever won, because the screen shows the
 * loser beside the winner. A person who declared 2 and measures 0.8 is looking at the
 * most useful thing this feature produces, and it only exists if the losing number
 * survives the arbitration instead of being dropped by it.
 *
 * [measuredCycles] is how much history [measured] stands on, and [cyclesUntilMeasured] is
 * what turns that into a sentence: "two more cycles and Kanso will use the measured one".
 * A count, not a boolean, because "not yet" without "how much longer" is the caption
 * nobody can act on.
 */
data class EffectiveVelocity(
	val person: User,
	val perWorkingDay: Double?,
	val source: VelocitySource,
	val declared: Double?,
	val measured: Double?,
	val measuredCycles: Int,
	val cyclesUntilMeasured: Int,
)

/**
 * The arbitration between a declared velocity and a measured one.
 *
 * The field on `user_preferences` is not the feature. This is. Two numbers answering the
 * same question and disagreeing is a tool that lies, so exactly one of them is in force at
 * any moment, the rule that picks it is written once, here, and every reader is told which
 * one won rather than working it out.
 *
 * **The declared value is a seed.** Under [MEASURED_FROM] measurable closed cycles it is
 * what Kanso plans with; from there the measured number takes over for good and the
 * declared one becomes a reference shown beside it. Two cycles rather than one because one
 * cycle is an anecdote — a fortnight with a week of holiday in it, or the fortnight
 * somebody closed nine small tickets — and replacing a person's own considered estimate
 * with a single observation is a downgrade dressed as a measurement. Rather than three,
 * which is what `VelocityService` averages over, because waiting six weeks to use evidence
 * that already exists leaves the seed in force long after it stopped being the best answer.
 * So the mean is over three cycles and the switch happens at two: the number keeps
 * improving after it takes over.
 *
 * **The declared value is never overwritten from the measured one.** Not on a schedule,
 * not the day they diverge, not "helpfully" at the moment of the switch. A lasting gap
 * between what somebody thought they delivered and what they delivered is information, and
 * it is the only output of this feature that a person could not have got another way.
 * Writing the measurement back would delete the finding and leave a column that agrees
 * with the measurement by construction and therefore says nothing. Nothing in this file
 * writes anything at all — it is `@Transactional(readOnly = true)` throughout, which is
 * the cheapest way to make that structural rather than a rule someone has to remember.
 *
 * **Computed on read**, like everything else here. There is no cached "effective velocity"
 * column, and the arbitration is re-run on every read because its input — how many closed
 * cycles exist — changes without anybody touching this person.
 */
@Service
class EffectiveVelocityService(
	private val velocity: VelocityService,
	private val preferences: PreferencesService,
) {

	/**
	 * One person's, in one team.
	 *
	 * [teamId] for the same reason `VelocityService.forPerson` takes one: a cycle is one
	 * team's plan, so somebody who works across two teams is measured twice against two
	 * calendars. The declared value is not per team — it is one person's estimate of
	 * themselves — so the same seed can be in force in one team and already overruled in
	 * another, which is correct: the team with the history is the one that knows better.
	 */
	@Transactional(readOnly = true)
	fun forPerson(person: User, teamId: UUID): EffectiveVelocity =
		arbitrate(velocity.forPerson(person, teamId), preferences.get(person.id).declaredVelocity)

	/**
	 * The same rule for several people at once, for the ticket-duration read.
	 *
	 * One `measure` of the team instead of one per assignee: [VelocityService.forTeam]
	 * already walks the closed cycles once, and a ticket with four assignees would
	 * otherwise run that walk four times over identical rows.
	 *
	 * [teamId] is nullable here and not on [forPerson], because a ticket no team has
	 * claimed still has assignees and still wants a duration. There is no calendar to
	 * measure against, so nothing can be measured and every declared value stands —
	 * which is the same rule, reached through its first branch rather than special-cased.
	 */
	@Transactional(readOnly = true)
	fun forPeople(people: List<User>, teamId: UUID?): List<EffectiveVelocity> {
		if (people.isEmpty()) return emptyList()
		val measured = teamId
			?.let { velocity.forTeam(it).rows.associateBy { row -> row.person.id } }
			.orEmpty()
		return people.map { person ->
			arbitrate(
				// Absent from the rows means the measured cycles never saw them finish
				// anything — which `VelocityService` deliberately reports as an absence
				// rather than a zero, so it has to be rebuilt as one here too.
				measured[person.id] ?: PersonVelocity(person, null, emptyList(), 0),
				preferences.get(person.id).declaredVelocity,
			)
		}
	}

	/**
	 * The rule itself, and the only place it is written down.
	 *
	 * The third branch is the one that is not obvious. Below the threshold with nothing
	 * declared there is no arbitration to perform — the threshold exists to decide which of
	 * *two* numbers wins, and here there is one. A single thin cycle is weak evidence, but
	 * it is evidence, and refusing to show it would tell somebody with real closed work
	 * that Kanso knows nothing about them. [measuredCycles] travels out with the answer so
	 * the sentence can say how thin it is.
	 */
	private fun arbitrate(measured: PersonVelocity, declared: Double?): EffectiveVelocity {
		// A rate of zero is not a measurement anything can be planned with: it divides into an
		// infinite duration, and it is precisely what somebody who had not joined yet looks
		// like from here. `VelocityService.forTeam` makes the same call by leaving those people
		// out of its rows rather than claiming they deliver nothing — this is that rule reached
		// from the other entry point, and without it the same person would be "measured at
		// zero" on their settings screen and "declared" in a ticket's duration.
		//
		// A quiet fortnight among busy ones is untouched by this: it averages above zero and is
		// measured normally. Silence across every cycle is the absence.
		val rate = measured.perWorkingDay?.takeIf { it > 0 }
		val cycles = if (rate == null) 0 else measured.cycles.size
		val source = when {
			cycles >= MEASURED_FROM -> VelocitySource.MEASURED
			declared != null -> VelocitySource.DECLARED
			rate != null -> VelocitySource.MEASURED
			else -> VelocitySource.NONE
		}
		return EffectiveVelocity(
			person = measured.person,
			perWorkingDay = when (source) {
				VelocitySource.DECLARED -> declared
				VelocitySource.MEASURED -> rate
				VelocitySource.NONE -> null
			},
			source = source,
			declared = declared,
			measured = rate,
			measuredCycles = cycles,
			cyclesUntilMeasured = (MEASURED_FROM - cycles).coerceAtLeast(0),
		)
	}

	companion object {
		/**
		 * Closed cycles at which the measurement overrules the declaration. See the class
		 * comment for why this is two and `VelocityService.DEFAULT_CYCLES` is three.
		 */
		const val MEASURED_FROM = 2
	}
}

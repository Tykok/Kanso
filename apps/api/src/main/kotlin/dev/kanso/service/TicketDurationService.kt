package dev.kanso.service

import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How long a ticket is likely to take, or why Kanso will not say.
 *
 * A sealed interface rather than one type with nullable fields, because the three ways of
 * not knowing are three different things to tell somebody and a caller holding
 * `lowWorkingDays == null` cannot tell them apart. An empty field reads as a bug; each of
 * these reads as a sentence, and the compiler is what stops a screen from forgetting one.
 */
sealed interface TicketDuration {

	/**
	 * A range, and never a bare date.
	 *
	 * A single number computed off a mean of three cycles would be believed to the day,
	 * which none of the data underneath it supports: the mean is three observations, the
	 * band around each of them is unknown, and the ticket has not been started. So the
	 * answer arrives already widened, and there is no field anywhere carrying the point
	 * estimate — a caller that could reach it would print it.
	 *
	 * [withoutVelocity] is the part of the assignee list this range could not account for,
	 * carried out with the number the way `unestimated` travels with a points total. An
	 * assignee nobody can measure contributes nothing to the sum, which makes the ticket
	 * look slower than it is; the count is what lets the screen say so instead of quietly
	 * being wrong.
	 */
	data class Estimated(
		val lowWorkingDays: Int,
		val highWorkingDays: Int,
		val points: Int,
		val assignees: Int,
		val withoutVelocity: Int,
	) : TicketDuration

	/** Nobody has sized it. Points are the numerator; there is no division to do. */
	data object NoEstimate : TicketDuration

	/** Nobody is on it, so there is no pace to divide by — not a slow ticket, an unowned one. */
	data object NoAssignee : TicketDuration

	/** Somebody is on it, and nothing is known about how fast any of them work. */
	data object NoVelocity : TicketDuration
}

/**
 * `points ÷ the assignees' velocity = working days`. The promise the two previous tickets
 * exist to keep.
 *
 * **Several assignees: the velocities add.** Strictly this is false. Two people on one
 * task do not finish it in half the time — they split it, then spend some of what they
 * saved agreeing on the split — and every real model of that has a coordination term in
 * it. There is no honest value for that term here: it depends on the work, the pair and
 * the week. Any number invented for it would be precision Kanso cannot back, arriving in
 * a range that already claims to be uncertain. Adding is the only rule that can be
 * explained in one line to the person reading the estimate, and a rule somebody can check
 * against their own intuition is worth more than a model they have to trust. It errs
 * optimistic, which the width of the range is there to absorb.
 *
 * **Three refusals, each distinguishable from the others.** No points, no assignee, no
 * measurable pace. All three return a named absence rather than an empty range, because a
 * blank field on a ticket reads as something that failed to load, and a planner who cannot
 * tell "nobody sized this" from "nobody is on this" fixes the wrong thing.
 *
 * **No team median for the unassigned case.** It was the alternative, and it is worse than
 * the refusal: a median over a team where half the people have no velocity is a number
 * about nobody, it moves when somebody unrelated closes a cycle, and "we dated this using
 * somebody else's pace" is not a sentence a plan should rest on. Assigning the ticket is
 * both the fix and the thing the empty state should be nudging towards.
 *
 * Computed on read, like every other rate here. Nothing is stored, and the range moves the
 * moment the ticket is resized, reassigned, or an assignee's cycle closes.
 */
@Service
class TicketDurationService(
	private val tickets: TicketService,
	private val velocity: EffectiveVelocityService,
	private val users: UserRepository,
) {

	@Transactional(readOnly = true)
	fun forTicket(id: UUID): TicketDuration = of(tickets.get(id))

	fun of(detail: TicketDetail): TicketDuration {
		// Checked before the assignees, so a ticket that is missing both is reported as
		// unsized. Sizing it is the cheaper of the two gestures and the one that is nobody
		// else's decision.
		val points = detail.ticket.estimate ?: return TicketDuration.NoEstimate
		if (detail.assigneeIds.isEmpty()) return TicketDuration.NoAssignee

		val people = users.findAllById(detail.assigneeIds.toSet())
		val rates = velocity.forPeople(people, detail.ticket.teamId).map { it.perWorkingDay }
		val combined = rates.filterNotNull().sum()
		// Every assignee unmeasurable. Not zero days and not infinite ones — unknown, which
		// is a third thing and has its own sentence.
		if (combined <= 0) return TicketDuration.NoVelocity

		val days = points / combined
		return TicketDuration.Estimated(
			lowWorkingDays = floor(days * (1 - BAND)).toInt().coerceAtLeast(1),
			highWorkingDays = ceil(days * (1 + BAND)).toInt().coerceAtLeast(1),
			points = points,
			assignees = people.size,
			withoutVelocity = rates.count { it == null },
		)
	}

	companion object {
		/**
		 * How far either side of the point estimate the range reaches.
		 *
		 * A convention, stated as one, and not a measurement. The honest alternative — the
		 * spread of the three cycles the mean came from — is a range over three samples: it
		 * collapses to nothing when a team is steady for a quarter and blows out when one
		 * fortnight had a holiday in it, so it would read as precision on the weeks it
		 * happened to be narrow. A fixed band claims only what it is: nobody should read
		 * this to the day. A quarter, because it turns four days into "3 to 5" — wide
		 * enough that the number is visibly an estimate, narrow enough to still decide
		 * anything with.
		 */
		const val BAND = 0.25
	}
}

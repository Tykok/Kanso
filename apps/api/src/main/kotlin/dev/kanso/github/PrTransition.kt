package dev.kanso.github

import dev.kanso.domain.TicketStatus
import java.time.OffsetDateTime

/**
 * Why a ticket did or did not move. The refusals are values rather than a bare `null`
 * because every one of them is a sentence somebody will eventually ask for.
 */
sealed interface TransitionDecision {

	/** Move it. */
	data class Move(val to: TicketStatus) : TransitionDecision

	/** The link displays but does not act — a bare mention. Guard three. */
	data object NotAClosingLink : TransitionDecision

	/** It is already there, or further on. Guard one. */
	data object NotBackwards : TransitionDecision

	/** Somebody moved it by hand after this event happened. Guard two. */
	data object NotOverAPerson : TransitionDecision

	/** Cancelling is a decision, and a merge is not evidence against it. */
	data object Canceled : TransitionDecision
}

/**
 * The two transitions and the three guards, as one pure function.
 *
 * Pure for the same reason [PrLinkParser] is: these are the rules that decide whether
 * somebody's ticket moves without them asking, so they have to be assertable as a table of
 * states rather than through a webhook delivery. No repository, no clock — **the caller
 * passes the event's own timestamp**, which is guard two's whole point.
 *
 * Two transitions, not five. `converted_to_draft` sending a ticket back to In progress and
 * `closed` sending it back would both be defensible, and both are rejected together: they
 * are the automatic *backward* moves, and an automatic backward move is the origin of every
 * "why did my ticket change" conversation. Forward is inferable; backward is a judgement.
 */
object PrTransition {

	/**
	 * The ranking guard one compares on. Derived here rather than stored on
	 * [TicketStatus], because it is this feature's opinion about progress and not the
	 * status's own — `StatusCategory` is the enum's answer to a different question, and
	 * giving `TicketStatus` a rank would invite everything else to sort by it.
	 *
	 * `CANCELED` is deliberately **absent** rather than ranked low or high. It is outside
	 * the ordering: a cancelled ticket is not "behind" done, it is off the board, and any
	 * number here would make some automatic move to or from it look reasonable.
	 */
	private val RANK = mapOf(
		TicketStatus.BACKLOG to 0,
		TicketStatus.TODO to 1,
		TicketStatus.IN_PROGRESS to 2,
		TicketStatus.IN_REVIEW to 3,
		TicketStatus.DONE to 4,
	)

	/**
	 * @param closes whether the link says this pull request may move the ticket at all.
	 * @param current where the ticket is now.
	 * @param target where the event wants it — `IN_REVIEW` for a pull request ready for
	 *   review, `DONE` for a merge.
	 * @param lastHumanStatusChangeAt when a *person* last moved this ticket's status, or
	 *   null if only automation ever has.
	 * @param eventAt the timestamp on the GitHub event, **not** `now()`.
	 */
	fun decide(
		closes: Boolean,
		current: TicketStatus,
		target: TicketStatus,
		lastHumanStatusChangeAt: OffsetDateTime?,
		eventAt: OffsetDateTime,
	): TransitionDecision {
		// Guard three, first because it is the cheapest and the most common refusal: most
		// links on a busy repository are mentions.
		if (!closes) return TransitionDecision.NotAClosingLink

		// Cancelling is a decision, and it is never touched by automation in *either*
		// direction — a merge does not un-cancel a ticket somebody cancelled, and nothing
		// here ever cancels one. Checked before the ranking because `CANCELED` has no rank
		// and a lookup would have to invent one.
		if (current == TicketStatus.CANCELED) return TransitionDecision.Canceled

		// Guard one. `<=` and not `<`: equal rank means it is already there, and re-moving a
		// ticket to the status it already holds would write an activity row saying nothing.
		val from = RANK[current] ?: return TransitionDecision.Canceled
		val to = RANK[target] ?: return TransitionDecision.NotBackwards
		if (to <= from) return TransitionDecision.NotBackwards

		// Guard two, last because it is the only one that needs a query to answer.
		//
		// The comparison is against the *event's* timestamp rather than `now()`, and that is
		// the subtle half. A delivery retried an hour later must not win a race it lost when
		// it was first sent: with `now()`, a redelivery of an old `ready_for_review` would
		// look newer than the person who moved the ticket in between, and pull it backwards.
		//
		// `!isBefore` rather than `isAfter`, so a hand move recorded in the same instant as
		// the event is resolved in the person's favour. A tie is not evidence that automation
		// was right.
		if (lastHumanStatusChangeAt != null && !lastHumanStatusChangeAt.isBefore(eventAt)) {
			return TransitionDecision.NotOverAPerson
		}

		return TransitionDecision.Move(target)
	}
}

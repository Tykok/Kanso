package dev.kanso.github

import dev.kanso.domain.StatusCategory
import java.time.OffsetDateTime

/**
 * Why a ticket did or did not move. The refusals are values rather than a bare `null`
 * because every one of them is a sentence somebody will eventually ask for.
 */
sealed interface TransitionDecision {

	/** Move it. */
	data class Move(val to: String) : TransitionDecision

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
	 * The ranking guard one compares on — **the team's own order**, since `KAN-90`.
	 *
	 * It used to be a map written out here, over `DefaultStatus`, on the argument that
	 * progress is this feature's opinion and not the status's own. That argument was right
	 * and its conclusion has moved: `team_statuses.position` is the order the team put its
	 * statuses in, which is a better answer to "is this forward" than anything this file
	 * could guess, and it is the same order every grouped page already stacks by. What the
	 * old docstring was guarding against — inviting everything else to sort by a rank
	 * bolted onto the enum — is not in play, because nothing is bolted on.
	 *
	 * The CANCELED statuses are deliberately **absent** from the ranking rather than
	 * placed low or high. They are outside the ordering: a cancelled ticket is not
	 * "behind" done, it is off the board, and any number for them would make some
	 * automatic move to or from one look reasonable.
	 */
	private fun rankOf(catalogue: Map<String, StatusCategory>): Map<String, Int> =
		catalogue.entries
			.filter { it.value != StatusCategory.CANCELED }
			.mapIndexed { rank, entry -> entry.key to rank }
			.toMap()

	/**
	 * @param closes whether the link says this pull request may move the ticket at all.
	 * @param current where the ticket is now.
	 * @param target where the event wants it — the ticket's team's first `STARTED` status
	 *   for a pull request ready for review, its first `COMPLETED` one for a merge.
	 * @param catalogue the ticket's team's statuses, in the team's order — the ranking
	 *   guard one reads, and where the CANCELED ones are named.
	 * @param lastHumanStatusChangeAt when a *person* last moved this ticket's status, or
	 *   null if only automation ever has.
	 * @param eventAt the timestamp on the GitHub event, **not** `now()`.
	 */
	fun decide(
		closes: Boolean,
		current: String,
		target: String,
		catalogue: Map<String, StatusCategory>,
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
		if (catalogue[current] == StatusCategory.CANCELED) return TransitionDecision.Canceled

		// Guard one. `<=` and not `<`: equal rank means it is already there, and re-moving a
		// ticket to the status it already holds would write an activity row saying nothing.
		val rank = rankOf(catalogue)
		val from = rank[current] ?: return TransitionDecision.Canceled
		val to = rank[target] ?: return TransitionDecision.NotBackwards
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

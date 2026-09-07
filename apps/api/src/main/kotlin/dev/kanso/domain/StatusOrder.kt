package dev.kanso.domain

/**
 * The two orders the server has an opinion about — one of them since `KAN-28`.
 *
 * A grouped page is ordered by bucket before it is ordered by the view's sort, so the page
 * boundary is cut against this sequence — which is what lets the client lay a page of rows
 * over a list of counts and know the partition is contiguous. That makes it the one
 * ordering that cannot live on one side of the wire alone.
 *
 * It is deliberately a second copy of `WORKFLOW_ORDER` in the web app's
 * `lib/status-order.ts`, and the house already accepts that shape for a closed vocabulary:
 * `TICKET_STATUSES` and `EFFORT_POINTS` are literals on both sides for the same reason.
 * The reason here is narrower than "it is closed". An ordering is a rendering decision,
 * not data: sending it would put a fetch between a chart and knowing how to draw itself,
 * and would let a server deploy silently restack a screen. What crosses instead is the
 * agreement, pinned as the same written sequence by `domain/StatusOrderTest.kt` here and
 * `lib/status-order.test.ts` there — so the copy that drifts fails a test on the side that
 * drifted, rather than producing a page whose headers no longer match its rows.
 *
 * The client's other two orders — the proportion bar's and the workload bar's — are not
 * here and must not be. The server never asks them: they order segments inside a picture
 * drawn from counts it has already sent, so a copy of them here would be a second thing to
 * keep in step for no answer it could give.
 */
object StatusOrder {

	/**
	 * The six a new team is seeded with, downwards as the work flows.
	 *
	 * This was "the order every grouped view reads" until `KAN-28`, and the sentence is
	 * worth keeping because the change is easy to miss: a team can now reorder its own
	 * list, so a grouped page ranks by `team_statuses.position` and this constant no
	 * longer decides anything a reader sees. What it still decides is where a *new* team
	 * starts — `V41`'s `seed_team_statuses` holds the same six in SQL, because a migration
	 * is a fact about a moment and must not move when this file does.
	 *
	 * Written out rather than folded over `DefaultStatus.entries`, and never derived from
	 * [StatusCategory]. The order of a closed vocabulary is a product decision, and taking
	 * it from the enum's declaration would make reordering the enum — or adding a value in
	 * the obvious place — silently restack every grouped view and move every saved view's
	 * page boundary. `StatusOrderTest` asserts this names every status exactly once, so a
	 * seventh one is a decision somebody has to make rather than a default they inherit.
	 */
	val WORKFLOW: List<DefaultStatus> = listOf(
		DefaultStatus.BACKLOG,
		DefaultStatus.TODO,
		DefaultStatus.IN_PROGRESS,
		DefaultStatus.IN_REVIEW,
		DefaultStatus.DONE,
		DefaultStatus.CANCELED,
	)

	/**
	 * The order a scope spanning teams reads in — and the one ordering left that is a
	 * constant on both sides of the wire.
	 *
	 * [WORKFLOW] stopped being that when a team could reorder its list. This took the job
	 * and can hold it for the reason the docstring above gives for the pattern: the five
	 * categories are closed, so the sequence is a rendering decision rather than data, and
	 * sending it would put a fetch between a chart and knowing how to draw itself. The
	 * copy is `CATEGORY_ORDER` in the web app's `lib/status-order.ts`, pinned equal by a
	 * test on each side.
	 *
	 * Why a scope of two teams groups by this rather than by either team's words: the
	 * header of a list holding two vocabularies has to be the fact both of them agree on.
	 * `Todo` and `Qualifié` are one bucket, and the bucket is called `unstarted`.
	 */
	val CATEGORY_ORDER: List<StatusCategory> = listOf(
		StatusCategory.BACKLOG,
		StatusCategory.UNSTARTED,
		StatusCategory.STARTED,
		StatusCategory.COMPLETED,
		StatusCategory.CANCELED,
	)

	/** One-based, for the same reason [rankOf] is: this is rendered as a SQL `CASE` too. */
	fun rankOfCategory(category: StatusCategory): Int = CATEGORY_ORDER.indexOf(category) + 1

	/**
	 * Where [status] sits, one-based — and where anything [WORKFLOW] does not name sits,
	 * which is after all of it.
	 *
	 * One-based and not zero-based because the rank is rendered into SQL as a `CASE`, and
	 * the unranked branch has to be a number larger than every ranked one rather than the
	 * `NULL` a missing `WHEN` would produce: `NULL` sorts first in Postgres by default, so
	 * an unplaced status would go to the *top* of a grouped list.
	 */
	fun rankOf(status: DefaultStatus): Int =
		WORKFLOW.indexOf(status).let { if (it == -1) UNPLACED else it + 1 }

	/** The rank of a status [WORKFLOW] has not been told about. Last, and stable. */
	val UNPLACED: Int get() = WORKFLOW.size + 1
}

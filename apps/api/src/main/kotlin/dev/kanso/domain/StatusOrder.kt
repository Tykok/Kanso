package dev.kanso.domain

/**
 * The one status order the server has an opinion about.
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
	 * Downwards as the work flows: what is waiting at the top, what is finished at the
	 * bottom.
	 *
	 * Written out rather than folded over `TicketStatus.entries`, and never derived from
	 * [StatusCategory]. The order of a closed vocabulary is a product decision, and taking
	 * it from the enum's declaration would make reordering the enum — or adding a value in
	 * the obvious place — silently restack every grouped view and move every saved view's
	 * page boundary. `StatusOrderTest` asserts this names every status exactly once, so a
	 * seventh one is a decision somebody has to make rather than a default they inherit.
	 */
	val WORKFLOW: List<TicketStatus> = listOf(
		TicketStatus.BACKLOG,
		TicketStatus.TODO,
		TicketStatus.IN_PROGRESS,
		TicketStatus.IN_REVIEW,
		TicketStatus.DONE,
		TicketStatus.CANCELED,
	)

	/**
	 * Where [status] sits, one-based — and where anything [WORKFLOW] does not name sits,
	 * which is after all of it.
	 *
	 * One-based and not zero-based because the rank is rendered into SQL as a `CASE`, and
	 * the unranked branch has to be a number larger than every ranked one rather than the
	 * `NULL` a missing `WHEN` would produce: `NULL` sorts first in Postgres by default, so
	 * an unplaced status would go to the *top* of a grouped list.
	 */
	fun rankOf(status: TicketStatus): Int =
		WORKFLOW.indexOf(status).let { if (it == -1) UNPLACED else it + 1 }

	/** The rank of a status [WORKFLOW] has not been told about. Last, and stable. */
	val UNPLACED: Int get() = WORKFLOW.size + 1
}

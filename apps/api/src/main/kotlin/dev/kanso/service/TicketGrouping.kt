package dev.kanso.service

import dev.kanso.domain.StatusGrouping
import dev.kanso.repo.TeamStatusRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.TicketQueryRepository
import dev.kanso.repo.TicketScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One bucket on screen: the value its rows share, how many share it, and the rows of
 * them this page reached.
 *
 * [count] is the whole match and [tickets] is a page of it, and the two are allowed to
 * disagree — that is the shape's entire purpose. `Todo · 29` above twenty loaded rows is
 * a true header; `Todo · 20` is the lie the client-side bucketing could not help telling.
 *
 * [key] is the empty string for the rows that have none — nobody assigned, no project —
 * and for the single bucket `none` grouping answers with, which is the same spelling the
 * web app's `organise/grouping.ts` has always used for it.
 */
data class TicketGroup(val key: String, val count: Int, val tickets: List<TicketDetail>)

/**
 * Grouping, assembled once for the two doors that ask for it — the main list and a saved
 * view. A collaborator rather than a method on either, for the reason [TicketDetails]
 * beside it gives: the second copy is the one that drifts.
 *
 * Two queries, never one per bucket. The counts come from a `GROUP BY` over the whole
 * match and the rows from one ordinary page — and because the page is ordered by bucket
 * before it is ordered by the view's sort, laying the one over the other is a partition,
 * not a search. A query per bucket would be an unbounded fan-out the moment somebody
 * groups fifty people's work by assignee.
 *
 * What it deliberately does *not* do is name the buckets. A label is `A. Okonkwo` or
 * `Design system`, and those live in tables this has no business joining for a list of
 * ids the caller is already holding — the web app resolves every one of them today for
 * its chips. The server owns which buckets exist and how many rows are in them; the
 * screen owns what they are called.
 */
@Service
class TicketGroups(
	private val query: TicketQueryRepository,
	private val details: TicketDetails,
	private val statusCategories: StatusCategories,
) {

	@Transactional(readOnly = true)
	fun of(
		scope: TicketScope,
		filters: TicketFilters,
		groupBy: ViewGroupBy,
		sortBy: ViewSortBy,
		limit: Int,
		offset: Long = 0,
	): List<TicketGroup> {
		val grouping = statusGrouping(scope)
		val counts = query.groupCounts(scope, filters, groupBy, grouping)
		if (counts.isEmpty()) return emptyList()
		val page = details.of(query.matching(scope, filters, groupBy, sortBy, limit, offset, grouping))
		val rows = page.groupBy { keyOf(it, groupBy, grouping) }
		// Every bucket the question has, in the database's order — including the ones this
		// page never reached, which come back with their count and no rows. Dropping those
		// would make the answer's shape depend on how far the reader had scrolled, and a
		// header that appears only once its rows are loaded cannot be scrolled towards.
		return counts.map { TicketGroup(it.key ?: "", it.count, rows[it.key].orEmpty()) }
	}

	/**
	 * Which buckets this scope has — `KAN-28`.
	 *
	 * One team reads its own words in its own order, because that is what the list on its
	 * screen says. Anything wider reads categories: a scope holding two teams holds two
	 * vocabularies, and a header has to be the fact they agree on rather than whichever
	 * team's word came back first. `null` team ids is the widest scope of all — every team
	 * in the instance — and lands in the same branch.
	 */
	private fun statusGrouping(scope: TicketScope): StatusGrouping =
		statusCategories.groupingFor(scope.teamIds)

	/**
	 * Which bucket a row landed in, read back off the row.
	 *
	 * It has to agree with `TicketQueryRepository.groupKey`, which is the same question
	 * asked in SQL — for status that agreement is the [StatusGrouping] both are handed,
	 * and the one place that is not obvious is `assignee`: the key there is
	 * the *first* of the ticket's people, and "first" means the order
	 * `TicketRepository.assigneeIdsFor` sorts them in — the column's own — which is the
	 * order the `MIN` on the other side picks from.
	 */
	private fun keyOf(detail: TicketDetail, groupBy: ViewGroupBy, statuses: StatusGrouping): String? = when (groupBy) {
		// Through the same mapping the `CASE` used, or a category-bucketed page would lay
		// rows keyed `todo` over a bucket counted as `unstarted` and draw every one of
		// them under no header at all.
		ViewGroupBy.STATUS -> statuses.bucketOf[detail.ticket.status.wire] ?: detail.ticket.status.wire
		ViewGroupBy.PRIORITY -> detail.ticket.priority.wire
		ViewGroupBy.PROJECT -> detail.ticket.projectId?.toString()
		ViewGroupBy.ASSIGNEE -> detail.assigneeIds.firstOrNull()?.toString()
		ViewGroupBy.NONE -> null
	}
}

package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketCycles
import dev.kanso.db.TicketLabels
import dev.kanso.db.Tickets
import dev.kanso.db.toTicket
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A saved view's question, parsed. Every field is a chip on screen 21.
 *
 * `statusesExcluded` exists because the drawing's second chip is `Statut ≠ Done`: "not
 * done" is the useful shape and expressing it as the five statuses that are not `done`
 * would silently stop meaning that the day a sixth is added.
 */
data class SavedViewFilters(
	val statuses: List<TicketStatus> = emptyList(),
	val statusesExcluded: List<TicketStatus> = emptyList(),
	val priorities: List<TicketPriority> = emptyList(),
	val projectIds: List<UUID> = emptyList(),
	val assigneeIds: List<UUID> = emptyList(),
	val unassigned: Boolean = false,
	val cycleIds: List<UUID> = emptyList(),
	/** The drawing's `Étiquette synchro`, by id — see `SavedViewService.SERVED_FILTERS`. */
	val labelIds: List<UUID> = emptyList(),
	/** "Blocked for 3 days", from the drawing's own list of saved views. */
	val openedMoreThanDaysAgo: Int? = null,
)

/**
 * The one query a saved view runs.
 *
 * Separate from [TicketRepository] rather than a widening of its `search`: `search` takes
 * eight parameters that every controller already passes, and adding six more optional
 * ones would make a method nobody can read at the call site. The predicate here is built
 * the same way `search` builds its own — `buildList` then `compoundAnd` — so the two are
 * recognisably the same idiom.
 */
@Repository
class ViewTicketRepository {

	fun matching(
		teamIds: Collection<UUID>,
		filters: SavedViewFilters,
		sortBy: dev.kanso.service.ViewSortBy,
		limit: Int,
	): List<Ticket> {
		if (teamIds.isEmpty()) return emptyList()
		return Tickets.selectAll().where(predicate(teamIds, filters))
			.orderBy(*order(sortBy))
			.limit(limit)
			.map { it.toTicket() }
	}

	fun count(teamIds: Collection<UUID>, filters: SavedViewFilters): Int {
		if (teamIds.isEmpty()) return 0
		return Tickets.selectAll().where(predicate(teamIds, filters)).count().toInt()
	}

	private fun predicate(teamIds: Collection<UUID>, filters: SavedViewFilters): Op<Boolean> {
		val conditions = buildList {
			// An archived ticket is out of every saved view. A view is a working list, and
			// the archive is where things go to stop being on one.
			add(Tickets.archived eq false)
			add(Tickets.teamId inList teamIds)
			if (filters.statuses.isNotEmpty()) add(Tickets.status inList filters.statuses.map { it.wire })
			if (filters.statusesExcluded.isNotEmpty()) {
				add(Tickets.status notInList filters.statusesExcluded.map { it.wire })
			}
			if (filters.priorities.isNotEmpty()) add(Tickets.priority inList filters.priorities.map { it.wire })
			if (filters.projectIds.isNotEmpty()) add(Tickets.projectId inList filters.projectIds)
			if (filters.assigneeIds.isNotEmpty()) {
				add(
					Tickets.id inSubQuery TicketAssignees.select(TicketAssignees.ticketId)
						.where { TicketAssignees.userId inList filters.assigneeIds }
				)
			}
			// "Sans assigné" in the drawing's sidebar. Not the complement of an assignee
			// filter — both can be asked at once, and the answer is then empty, which is
			// the honest reading of a contradictory question.
			if (filters.unassigned) {
				add(Tickets.id notInSubQuery TicketAssignees.select(TicketAssignees.ticketId))
			}
			if (filters.cycleIds.isNotEmpty()) {
				add(
					Tickets.id inSubQuery TicketCycles.select(TicketCycles.ticketId)
						.where { TicketCycles.cycleId inList filters.cycleIds }
				)
			}
			// Any of the labels asked for, not all of them: one chip is one question, and
			// `Étiquette synchro, design system` reads as "either", the same way the
			// priority chip's two values do.
			if (filters.labelIds.isNotEmpty()) {
				add(
					Tickets.id inSubQuery TicketLabels.select(TicketLabels.ticketId)
						.where { TicketLabels.labelId inList filters.labelIds }
				)
			}
			// Age is measured from creation, not from `updated_at`: "blocked for 3 days" is
			// about how long the thing has been open, and a rename would reset the other one.
			filters.openedMoreThanDaysAgo?.let {
				add(Tickets.createdAt less OffsetDateTime.now().minusDays(it.toLong()))
			}
		}
		return conditions.compoundAnd()
	}

	/**
	 * The tie-break is always `number DESC`, whatever the sort: two tickets of equal
	 * priority have to come back in a stable order or the list reshuffles under the
	 * cursor on every refetch, which is what makes `⇧↑↓` unusable.
	 */
	private fun order(sortBy: dev.kanso.service.ViewSortBy): Array<Pair<Expression<*>, SortOrder>> =
		when (sortBy) {
			dev.kanso.service.ViewSortBy.PRIORITY -> arrayOf(
				// `priority` is a text column of a closed vocabulary, so alphabetical order
				// is meaningless here; the CASE puts urgent first, as the screen does.
				priorityRank to SortOrder.ASC,
				Tickets.number to SortOrder.DESC,
			)
			dev.kanso.service.ViewSortBy.UPDATED -> arrayOf(
				Tickets.updatedAt to SortOrder.DESC,
				Tickets.number to SortOrder.DESC,
			)
			dev.kanso.service.ViewSortBy.CREATED -> arrayOf(
				Tickets.createdAt to SortOrder.DESC,
				Tickets.number to SortOrder.DESC,
			)
			dev.kanso.service.ViewSortBy.TITLE -> arrayOf(
				Tickets.title.lowerCase() to SortOrder.ASC,
				Tickets.number to SortOrder.DESC,
			)
		}

	/**
	 * Urgent first, as the screen reads downwards. Written out rather than folded over
	 * `TicketPriority.entries`: the ordering of a closed vocabulary is a product decision,
	 * and deriving it from an enum's declaration order would make reordering the enum
	 * silently reorder every saved view.
	 */
	private val priorityRank: Expression<Int> = Case()
		.When(Tickets.priority eq TicketPriority.URGENT.wire, intLiteral(1))
		.When(Tickets.priority eq TicketPriority.HIGH.wire, intLiteral(2))
		.When(Tickets.priority eq TicketPriority.MEDIUM.wire, intLiteral(3))
		.When(Tickets.priority eq TicketPriority.LOW.wire, intLiteral(4))
		.When(Tickets.priority eq TicketPriority.NONE.wire, intLiteral(5))
		// `priority` is nullable in the schema even though the domain defaults it, so the
		// unranked case has to land somewhere rather than sorting as NULL.
		.Else(intLiteral(6))
}

package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketCycles
import dev.kanso.db.TicketLabels
import dev.kanso.db.Tickets
import dev.kanso.db.TrashEntries
import dev.kanso.db.toTicket
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.trash.TrashKind
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What a ticket query is bounded by before the first chip is read.
 *
 * Separate from [TicketFilters] rather than three more fields on it: a chip is a question
 * the reader asked and can take back off, and none of these are. They are the walls of
 * the room the question is asked in, they come from the route rather than from the filter
 * vocabulary, and [dev.kanso.service.TicketFilterVocabulary] must never accept them as
 * filter names — `?archived=true` on the list would otherwise read as a served facet.
 *
 * [teamIds] null and [teamIds] empty are different questions: null is "every team", which
 * is what `GET /api/tickets` with no `teamId` means, and empty is "no team at all", which
 * answers with nothing. Collapsing them would make a scope that resolved to nothing
 * silently show the whole instance.
 */
data class TicketScope(
	val teamIds: Collection<UUID>? = null,
	val includeArchived: Boolean = false,
)

/**
 * The question, parsed. Every field is a chip on screen 21 — and, since the two filtering
 * paths were merged, every field is also a query parameter of `GET /api/tickets`.
 *
 * `statusesExcluded` exists because the drawing's second chip is `Statut ≠ Done`: "not
 * done" is the useful shape and expressing it as the five statuses that are not `done`
 * would silently stop meaning that the day a sixth is added.
 */
data class TicketFilters(
	val statuses: List<TicketStatus> = emptyList(),
	val statusesExcluded: List<TicketStatus> = emptyList(),
	val priorities: List<TicketPriority> = emptyList(),
	val projectIds: List<UUID> = emptyList(),
	val assigneeIds: List<UUID> = emptyList(),
	val unassigned: Boolean = false,
	val cycleIds: List<UUID> = emptyList(),
	/** The drawing's `Étiquette synchro`, by id — see `TicketFilterVocabulary.SERVED`. */
	val labelIds: List<UUID> = emptyList(),
	/** "Blocked for 3 days", from the drawing's own list of saved views. */
	val openedMoreThanDaysAgo: Int? = null,
	/**
	 * "Not estimated" — the chip a planning session is run off, and the reason the
	 * estimate is nullable at all. Its own flag rather than a bound of [estimateMin] and
	 * [estimateMax]: null is not a small number, it is the absence of one.
	 */
	val unestimated: Boolean = false,
	/** Bounds on the points, inclusive. Both may be asked at once. */
	val estimateMin: Int? = null,
	val estimateMax: Int? = null,
)

/**
 * The one query that answers "which tickets".
 *
 * It used to be two. `TicketRepository.search` served the main list with four filters and
 * this one served a saved view with nine, both building their predicate the same way —
 * `buildList` then `compoundAnd` — from two independently written sets of clauses. The
 * old note here argued the split was worth it, because folding six optional parameters
 * into `search` would make a call nobody can read at the call site. That argument was
 * about the *parameter list*, and it stopped applying the moment the filters became one
 * named object: [TicketFilters] is one argument whatever it holds, so the wide query has
 * the narrow one's call shape and there is nothing left to buy by writing the predicate
 * twice.
 *
 * What the split cost while it lasted is the better argument for closing it. The two
 * predicates had already drifted: this one never excluded the trash, so a deleted ticket
 * stayed in every saved view and in every sidebar count, while the list it was deleted
 * from had dropped it. One predicate cannot disagree with itself.
 */
@Repository
class TicketQueryRepository {

	fun matching(
		scope: TicketScope,
		filters: TicketFilters,
		sortBy: dev.kanso.service.ViewSortBy = dev.kanso.service.ViewSortBy.UPDATED,
		limit: Int,
		offset: Long = 0,
	): List<Ticket> {
		if (scope.teamIds?.isEmpty() == true) return emptyList()
		return Tickets.selectAll().where(predicate(scope, filters))
			.orderBy(*order(sortBy))
			.limit(limit).offset(offset)
			.map { it.toTicket() }
	}

	fun count(scope: TicketScope, filters: TicketFilters): Int {
		if (scope.teamIds?.isEmpty() == true) return 0
		return Tickets.selectAll().where(predicate(scope, filters)).count().toInt()
	}

	/**
	 * A row is soft-deleted exactly when `trash_entries` names it — there is no `deleted`
	 * column to keep in step with that, which is `V11`'s whole argument. Duplicated from
	 * [TicketRepository] on purpose: that copy is `private` to a class full of row readers
	 * that each decide for themselves, and exporting it would invite a caller to forget.
	 */
	private val trashed
		get() = TrashEntries.select(TrashEntries.entityId)
			.where { TrashEntries.entityType eq TrashKind.TICKET.wire }

	private fun predicate(scope: TicketScope, filters: TicketFilters): Op<Boolean> {
		val conditions = buildList {
			// Unconditional, and not behind `includeArchived`: deleted is not archived, and
			// showing the archived work must not also surface what is in the trash.
			add(Tickets.id notInSubQuery trashed)
			// An archived ticket is out of every saved view — `SavedViewService` never asks
			// for one — and out of the list until the Archives tab asks. A view is a working
			// list, and the archive is where things go to stop being on one.
			if (!scope.includeArchived) add(Tickets.archived eq false)
			scope.teamIds?.let { add(Tickets.teamId inList it) }
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
			// "Sans estimation", the counterpart of `unassigned` above and asked the same way:
			// both can be combined with a bound, and the answer is then empty, which is the
			// honest reading of "unsized and bigger than a 3".
			if (filters.unestimated) add(Tickets.estimate.isNull())
			// A bound never matches an unsized ticket, and this is on purpose rather than by
			// accident of SQL's null comparison: a ticket nobody has estimated is not known
			// to be small, so neither `≥ 5` nor `≤ 5` may claim it. The `unestimated` chip is
			// how it is asked for.
			filters.estimateMin?.let { add(Tickets.estimate greaterEq it.toShort()) }
			filters.estimateMax?.let { add(Tickets.estimate lessEq it.toShort()) }
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
	 *
	 * It is also what makes `limit`/`offset` paging mean anything, which the main list
	 * needs and a saved view does not: two rows tied on `updated_at` and ordered by
	 * nothing else can land on both page 1 and page 2, or on neither.
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

package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketCycles
import dev.kanso.db.TicketLabels
import dev.kanso.db.Tickets
import dev.kanso.db.TrashEntries
import dev.kanso.db.toTicket
import dev.kanso.domain.StatusOrder
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
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
	val statuses: List<DefaultStatus> = emptyList(),
	val statusesExcluded: List<DefaultStatus> = emptyList(),
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
 * One bucket of a grouped answer, counted over everything that matches rather than over
 * a page of it. [key] is null for the rows that have none — no project, nobody assigned —
 * and for the single bucket `none` grouping answers with.
 */
data class TicketGroupCount(val key: String?, val count: Int)

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

	/**
	 * [groupBy] is not a second shape, it is the first key of the ordering.
	 *
	 * A grouped list is rows stacked by bucket, and a page of one has to be *contiguous*
	 * in its buckets or the boundary between page 1 and page 2 puts half of `Todo` under
	 * `Done`. Ordering by the group before the sort is what makes the flat page and the
	 * grouped answer the same rows: the caller can bucket the page it holds and know that
	 * nothing from a bucket it has already closed is still to come.
	 */
	fun matching(
		scope: TicketScope,
		filters: TicketFilters,
		groupBy: dev.kanso.service.ViewGroupBy = dev.kanso.service.ViewGroupBy.NONE,
		sortBy: dev.kanso.service.ViewSortBy = dev.kanso.service.ViewSortBy.UPDATED,
		limit: Int,
		offset: Long = 0,
	): List<Ticket> {
		if (scope.teamIds?.isEmpty() == true) return emptyList()
		return Tickets.selectAll().where(predicate(scope, filters))
			.orderBy(*(groupOrder(groupBy) + order(sortBy)))
			.limit(limit).offset(offset)
			.map { it.toTicket() }
	}

	fun count(scope: TicketScope, filters: TicketFilters): Int {
		if (scope.teamIds?.isEmpty() == true) return 0
		return Tickets.selectAll().where(predicate(scope, filters)).count().toInt()
	}

	/**
	 * Every bucket the question has, counted by the database over the whole match.
	 *
	 * This is the half that could not be done on the client at all. Bucketing the page in
	 * JavaScript answers `Todo · 12` when twelve is how many of the two hundred rows that
	 * were sent happen to be todo — the header reads as a fact about the team and is a
	 * fact about a fetch. One `GROUP BY` costs one round trip and is true regardless of
	 * how little of the answer the caller asked to carry.
	 *
	 * Buckets with nothing in them are absent rather than zero: an empty bucket is a
	 * header drawn over nothing, and the set of possible statuses is not the set of
	 * statuses this question found.
	 */
	fun groupCounts(
		scope: TicketScope,
		filters: TicketFilters,
		groupBy: dev.kanso.service.ViewGroupBy,
	): List<TicketGroupCount> {
		if (scope.teamIds?.isEmpty() == true) return emptyList()
		// `none` is a real choice on the control and it means one bucket, not zero — the
		// same reading the client has always given it.
		val key = groupKey(groupBy)
			?: return count(scope, filters).let { if (it == 0) emptyList() else listOf(TicketGroupCount(null, it)) }
		val tally = Tickets.id.count()
		return Tickets.select(key, tally).where(predicate(scope, filters))
			.groupBy(key)
			.orderBy(*groupOrder(groupBy))
			.map { TicketGroupCount(it[key]?.toString(), it[tally].toInt()) }
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
			// A ticket with no team is in none of these lists, and this is the one place that
			// has to be said. Every list on every screen runs this predicate, and each of
			// them is a room a team owns — a board, a saved view, a triage queue, a cycle, a
			// timeline, a workload. A draft nobody has filed is in none of those rooms, and
			// it is private to whoever wrote it, so leaking it into the unscoped list would
			// be both a wrong answer and a disclosure. `TicketRepository.findDrafts` is where
			// they are, scoped to their author.
			//
			// Not folded into the clause below: `teamIds` null means "every team", and every
			// team is still not the same set as every row.
			add(Tickets.teamId.isNotNull())
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
	 * Which bucket a row falls in, as an expression the database can both group and order
	 * by — so the counts and the rows agree on the answer by construction rather than by
	 * two implementations happening to match.
	 *
	 * Null for `none`, which has no key: the caller reads that as "one bucket".
	 */
	private fun groupKey(groupBy: dev.kanso.service.ViewGroupBy): Expression<*>? = when (groupBy) {
		dev.kanso.service.ViewGroupBy.STATUS -> Tickets.status
		dev.kanso.service.ViewGroupBy.PRIORITY -> Tickets.priority
		dev.kanso.service.ViewGroupBy.PROJECT -> Tickets.projectId
		dev.kanso.service.ViewGroupBy.ASSIGNEE -> firstAssignee
		dev.kanso.service.ViewGroupBy.NONE -> null
	}

	/**
	 * How the buckets themselves are stacked — a different question from how the rows
	 * inside one are, and answered by different things.
	 *
	 * `status` and `priority` are closed vocabularies with a reading order the screen has
	 * always drawn: waiting at the top, finished at the bottom. It is written out as a
	 * list for the reason [priorityRank] gives about its own — the order of a closed
	 * vocabulary is a product decision, and deriving it from the enum's declaration or
	 * from `StatusCategory` would make an unrelated edit silently restack every grouped
	 * view.
	 *
	 * The status half of that list moved to [StatusOrder], which is where the argument for
	 * it still being a second copy of the web app's `WORKFLOW_ORDER` is now written; this
	 * file renders it, and has no opinion left about what the order is.
	 *
	 * `project` and `assignee` have no such order, and this query cannot invent one: a
	 * project's name and a person's name live in other tables, and sorting people by
	 * their id is not sorting them by anything a reader can see. So the buckets come back
	 * ordered by the key — arbitrary, but *total and stable*, which is the property paging
	 * actually needs. Whoever holds the names may relabel the headers; nothing may reorder
	 * the rows, because the page boundary was cut against this order.
	 *
	 * The nameless bucket sorts last wherever there is one. It is the leftovers — no
	 * project, nobody assigned — and a bucket with no name at the top of the list reads
	 * as a group whose name failed to load.
	 */
	private fun groupOrder(
		groupBy: dev.kanso.service.ViewGroupBy,
	): Array<Pair<Expression<*>, SortOrder>> = when (groupBy) {
		dev.kanso.service.ViewGroupBy.STATUS -> arrayOf(statusRank to SortOrder.ASC)
		dev.kanso.service.ViewGroupBy.PRIORITY -> arrayOf(priorityRank to SortOrder.ASC)
		dev.kanso.service.ViewGroupBy.PROJECT -> arrayOf(Tickets.projectId to SortOrder.ASC_NULLS_LAST)
		dev.kanso.service.ViewGroupBy.ASSIGNEE -> arrayOf(firstAssignee to SortOrder.ASC_NULLS_LAST)
		dev.kanso.service.ViewGroupBy.NONE -> emptyArray()
	}

	/**
	 * The one assignee a ticket is filed under, chosen by the database so that it is the
	 * same one every time.
	 *
	 * A ticket with two owners appears once, under one of them, because a row drawn twice
	 * in a grouped list is a row somebody will count twice — that much the client already
	 * decided. What it could not decide was *which*: it took `assigneeIds[0]`, and that
	 * list came back from an unordered `SELECT`, so a ticket could change groups between
	 * two refetches of the same question. `MIN` makes the choice a property of the data.
	 *
	 * Cast to text because Postgres 16 has no `min(uuid)`. It is not a lossy comparison:
	 * a uuid compares as its sixteen bytes and its canonical text form is those bytes in
	 * lowercase hex, so the two orders are the same one — which is what lets
	 * `TicketRepository.assigneeIdsFor`, ordered by the column itself, hand back a list
	 * whose head is this same person.
	 */
	private val firstAssignee: Expression<String?> = wrapAsExpression(
		TicketAssignees
			.select(Min(TicketAssignees.userId.castTo(TextColumnType()), TextColumnType()))
			.where { TicketAssignees.ticketId eq Tickets.id }
	)

	/**
	 * [StatusOrder.WORKFLOW], rendered into SQL — and rendered *from* it rather than
	 * spelled out a second time here, so this file has an opinion about how to write a
	 * `CASE` and none at all about what order statuses read in.
	 *
	 * The `Else` is a real branch and it has to be a number: a status the list has not
	 * been told about must sort after every one it has, and a missing `WHEN` would yield
	 * `NULL`, which Postgres sorts *first*.
	 */
	private val statusRank: Expression<Int> = StatusOrder.WORKFLOW
		.fold(CaseWhen<Int>()) { case, status ->
			case.When(Tickets.status eq status.wire, intLiteral(StatusOrder.rankOf(status)))
		}
		.Else(intLiteral(StatusOrder.UNPLACED))

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

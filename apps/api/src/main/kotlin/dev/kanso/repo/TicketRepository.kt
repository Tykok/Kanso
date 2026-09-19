package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketDocs
import dev.kanso.db.TeamStatuses
import dev.kanso.db.Tickets
import dev.kanso.db.TrashEntries
import dev.kanso.db.toTicket
import dev.kanso.trash.TrashKind
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.SyncState
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketRepository(
	/**
	 * The one predicate. Injected rather than reimplemented: [search] below is a call shape
	 * over it, and the day a tenth filter lands it lands in one place.
	 */
	private val query: TicketQueryRepository,
	/**
	 * The catalogue, for the one domain rule [insert] owns — see `completed_at` below.
	 *
	 * A repository and not `StatusCategories`, deliberately: that one is a service, and a
	 * repository reaching up into the service layer to answer a question about its own
	 * write would invert the layering for one line. What it needs from there is the
	 * fallback chain, which is `StatusCategories.resolve` — module-internal, and the only
	 * copy of that chain in the codebase.
	 */
	private val teamStatuses: TeamStatusRepository,
) {

	/**
	 * A row here is soft-deleted exactly when `trash_entries` names it — there is no
	 * `deleted` column to keep in step with that, which is `V11`'s whole argument.
	 *
	 * Every query below that answers "which tickets are there" excludes the trash;
	 * [findById] deliberately does not (see its own note). Kept as one expression so no
	 * caller has to remember the entity type string.
	 */
	private val trashed
		get() = TrashEntries.select(TrashEntries.entityId)
			.where { TrashEntries.entityType eq TrashKind.TICKET.wire }

	/**
	 * The row, whatever state it is in — **including** one in the trash.
	 *
	 * This is a row reader, not a scope query, and two callers depend on it staying one:
	 * `NotionOutboundHandler.plan` looks a ticket up to build the push that archives its Notion page,
	 * which happens precisely because it was just thrown away, and `TicketService` loads
	 * the row before deciding whether the caller may see it. Filtering here would make the
	 * mirror silently keep a live page for every deleted ticket. `TicketService.get` is
	 * where a trashed ticket becomes a 404.
	 */
	fun findById(id: UUID): Ticket? =
		Tickets.selectAll().where { Tickets.id eq id }.singleOrNull()?.toTicket()

	/** Whole rows for a set of ids — what the scheduler loads a dependency graph with. */
	fun findAllById(ids: Collection<UUID>): List<Ticket> =
		if (ids.isEmpty()) emptyList()
		else Tickets.selectAll()
			.where { (Tickets.id inList ids) and (Tickets.id notInSubQuery trashed) }
			.map { it.toTicket() }

	/** The trash's own read: only the rows among [ids] that are actually in it. */
	fun findTrashed(ids: Collection<UUID>): List<Ticket> =
		if (ids.isEmpty()) emptyList()
		else Tickets.selectAll()
			.where { (Tickets.id inList ids) and (Tickets.id inSubQuery trashed) }
			.map { it.toTicket() }

	/**
	 * The Archives tab. Archived and *not* in the trash: the two tabs of screen 26 are
	 * disjoint, and a ticket somebody archived and then threw away belongs to the one with
	 * the countdown on it.
	 */
	fun findArchived(limit: Int): List<Ticket> =
		Tickets.selectAll()
			.where { (Tickets.archived eq true) and (Tickets.id notInSubQuery trashed) }
			.orderBy(Tickets.updatedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toTicket() }

	fun findByNotionPageId(pageId: String): Ticket? =
		Tickets.selectAll().where { Tickets.notionPageId eq pageId }.singleOrNull()?.toTicket()

	fun findByTeamAndNumber(teamId: UUID, number: Int): Ticket? =
		Tickets.selectAll().where { (Tickets.teamId eq teamId) and (Tickets.number eq number) }
			.singleOrNull()?.toTicket()

	/**
	 * The narrow call shape, over the one predicate in [TicketQueryRepository].
	 *
	 * It used to build its own, four filters wide, while the saved view's ran to nine. Six
	 * callers here want exactly these four and say so in one readable line — a scheduler
	 * loading a team's open work has no use for `openedForDays` — so the shape survives the
	 * merge and only the clauses underneath it went away. Two call shapes over one query is
	 * not duplication; two predicates was.
	 */
	fun search(
		teamIds: Collection<UUID>? = null,
		projectId: UUID? = null,
		statuses: Collection<DefaultStatus> = emptyList(),
		/** What the callers asking for a *meaning* pass — see `TicketFilters.categories`. */
		categories: Collection<StatusCategory> = emptyList(),
		assigneeId: UUID? = null,
		includeArchived: Boolean = false,
		limit: Int = 200,
		offset: Long = 0,
		/** The timeline column's ordering; null leaves [ViewSortBy.UPDATED] in place. */
		dateOrder: dev.kanso.service.TimelineSort? = null,
	): List<Ticket> = query.matching(
		scope = TicketScope(teamIds = teamIds, includeArchived = includeArchived),
		filters = TicketFilters(
			statuses = statuses.toList(),
			categories = categories.toList(),
			projectIds = listOfNotNull(projectId),
			assigneeIds = listOfNotNull(assigneeId),
		),
		// What this method has always ordered by. It gains `number DESC` behind it, which
		// no caller can lose by: the order was previously undefined between two rows saved
		// in the same transaction, and `offset` paging over an undefined order can show one
		// row twice and another not at all.
		sortBy = dev.kanso.service.ViewSortBy.UPDATED,
		limit = limit,
		offset = offset,
		dateOrder = dateOrder,
	)

	/**
	 * Every unarchived ticket in these projects, whatever team owns it.
	 *
	 * [search] cannot express it: its `projectId` filter is one id, and widening that
	 * parameter would change the meaning of a call every controller already makes.
	 */
	fun findByProjectIds(projectIds: Collection<UUID>, limit: Int): List<Ticket> =
		if (projectIds.isEmpty()) emptyList()
		else Tickets.selectAll()
			.where {
				(Tickets.projectId inList projectIds) and
					(Tickets.archived eq false) and
					(Tickets.id notInSubQuery trashed)
			}
			.orderBy(Tickets.updatedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toTicket() }

	/**
	 * [teamId] and [number] are null together — a draft, with no counter having spoken for
	 * it yet. [createdBy] is who to ask about it while that lasts.
	 */
	fun insert(
		id: UUID,
		number: Int?,
		teamId: UUID?,
		createdBy: UUID?,
		title: String,
		description: String?,
		status: String,
		priority: TicketPriority,
		estimate: Int?,
		start: KansoInstant?,
		due: KansoInstant?,
		projectId: UUID?,
	): Ticket {
		val now = OffsetDateTime.now()
		Tickets.insert {
			it[Tickets.id] = id
			it[Tickets.number] = number
			it[Tickets.teamId] = teamId
			it[Tickets.createdBy] = createdBy
			it[Tickets.title] = title
			it[Tickets.description] = description
			it[Tickets.status] = status
			it[Tickets.priority] = priority.wire
			// No default and no zero: a ticket arrives unsized unless somebody said a
			// number, and that absence is what every later average has to be able to see.
			it[Tickets.estimate] = estimate?.toShort()
			it[Tickets.startAt] = start?.at
			it[Tickets.startHasTime] = start?.hasTime ?: false
			it[Tickets.dueAt] = due?.at
			it[Tickets.dueHasTime] = due?.hasTime ?: false
			// A ticket can be created already done — logging work that is finished is a
			// normal thing to do. Leaving this null would hide it from the project bounds,
			// which fall back on completion dates precisely when nobody planned anything.
			it[Tickets.completedAt] = if (categoryOf(teamId, status) == StatusCategory.COMPLETED) now else null
			it[Tickets.projectId] = projectId
			it[archived] = false
			// `pending` means "queued for Notion", and the badge on every row says so. A
			// ticket with no team is never enqueued — it has no identifier and no team
			// relation, so there is no page to write — and leaving it `pending` would be a
			// row claiming to be waiting for a push nothing will ever make. `disabled` is
			// the state `SyncBadge` draws nothing for, which is the honest picture until a
			// team arrives and `TicketService.patch` turns the mirror back on.
			it[syncState] = if (teamId == null) SyncState.DISABLED.wire else SyncState.PENDING.wire
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun update(
		id: UUID,
		teamId: UUID?,
		title: String,
		description: String?,
		status: String,
		priority: TicketPriority,
		estimate: Int?,
		start: KansoInstant?,
		due: KansoInstant?,
		completedAt: OffsetDateTime?,
		projectId: UUID?,
		archived: Boolean,
	): Ticket? {
		val changed = Tickets.update({ Tickets.id eq id }) {
			it[Tickets.teamId] = teamId
			it[Tickets.title] = title
			it[Tickets.description] = description
			it[Tickets.status] = status
			it[Tickets.priority] = priority.wire
			it[Tickets.estimate] = estimate?.toShort()
			it[Tickets.startAt] = start?.at
			it[Tickets.startHasTime] = start?.hasTime ?: false
			it[Tickets.dueAt] = due?.at
			it[Tickets.dueHasTime] = due?.hasTime ?: false
			it[Tickets.completedAt] = completedAt
			it[Tickets.projectId] = projectId
			it[Tickets.archived] = archived
		}
		return if (changed == 0) null else findById(id)
	}

	/**
	 * Writes only the two bounds. The cascade moves dates and nothing else, so a full
	 * row update here would make it capable of clobbering a concurrent edit to a field
	 * it has no business touching.
	 *
	 * The granularity flags are left as they are: shifting a day-granularity ticket
	 * keeps it a day, and a chain of floating dates does not sprout times because
	 * something upstream slipped.
	 *
	 * A null bound means "this ticket never had one" and is left untouched, so a
	 * milestone stays a milestone rather than gaining an invented start whose
	 * granularity flag was never set for it.
	 */
	fun reschedule(id: UUID, start: OffsetDateTime?, end: OffsetDateTime?) {
		if (start == null && end == null) return
		Tickets.update({ Tickets.id eq id }) {
			if (start != null) it[startAt] = start
			if (end != null) it[dueAt] = end
		}
	}

	fun delete(id: UUID): Boolean = Tickets.deleteWhere { Tickets.id eq id } > 0

	/**
	 * One flag, for the trash's middle exit. [update] would write the whole row, which is
	 * more than "archive this instead" asks for and enough to clobber a concurrent edit.
	 */
	fun setArchived(id: UUID, archived: Boolean): Boolean =
		Tickets.update({ Tickets.id eq id }) { it[Tickets.archived] = archived } > 0

	// --- bulk reads ----------------------------------------------------------

	/**
	 * Whether anything in this instance has been moved past where the composer leaves it.
	 *
	 * Screen 08's fourth question — "est-ce que quelque chose a avancé ?" — and the whole
	 * of what the sidebar's checklist used to buy a 200-row ticket list to answer.
	 *
	 * `limit(1).empty()` rather than a count, the shape [UserRepository.hasOwner] already
	 * uses: the answer is a boolean, so the first matching row is the entire answer and
	 * Postgres stops reading there.
	 *
	 * Archived rows count, deliberately. The question is whether work has *ever* moved,
	 * and archiving the ticket somebody dragged into `in_progress` does not un-drag it —
	 * a step that unticked itself when a team was tidied up would be the checklist
	 * breaking the promise `first-session.ts` makes for all four of them.
	 */
	fun anyMovedAlong(): Boolean =
		!Tickets.select(Tickets.id)
			.where {
				// The categories, through each row's own team — `KAN-90`. Spelled as an
				// `EXISTS` for the reason `TicketFilters.categories` gives: this question
				// has no team scope at all, so a list of keys would mean reading every
				// catalogue in the instance to build an `IN` that grows with the teams.
				exists(
					TeamStatuses.selectAll().where {
						(TeamStatuses.teamId eq Tickets.teamId) and
							(TeamStatuses.key eq Tickets.status) and
							(TeamStatuses.category inList MOVED_ALONG_CATEGORIES.map { it.wire })
					}
				)
			}
			.limit(1).empty()

	/**
	 * Every ticket of [teamId] sitting in [key], archived ones included — `KAN-90`.
	 *
	 * Archived counts, and that is the point: `tickets_status_fk` does not care whether a
	 * row is on a board, so a removal that skipped archived rows would be refused by the
	 * database after the service had already told the team it worked.
	 */
	fun withStatus(teamId: UUID, key: String): List<Ticket> =
		Tickets.selectAll()
			.where { (Tickets.teamId eq teamId) and (Tickets.status eq key) }
			.map { it.toTicket() }

	/**
	 * Moves one ticket's status and nothing else — the write behind a status's removal.
	 *
	 * Not `update`, which takes every column and would need the whole row read back first;
	 * and deliberately not through `TicketService.patch`, which is where a status move
	 * normally belongs. `TeamStatusService` explains why: `TicketService` validates a
	 * status against the catalogue, so the two services needing each other is a Spring
	 * context that does not start.
	 *
	 * `updated_at` moves with it, because the row did change and a mirror that pushes on
	 * `updated_at` would otherwise never learn about it.
	 */
	fun moveStatus(id: UUID, key: String): Boolean =
		Tickets.update({ Tickets.id eq id }) {
			it[Tickets.status] = key
			it[updatedAt] = OffsetDateTime.now()
		} > 0

	/** Archived tickets count: they still need a decision when their team goes away. */
	fun countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int {
		if (teamIds.isEmpty()) return 0
		val where =
			if (includeArchived) Tickets.teamId inList teamIds
			else (Tickets.teamId inList teamIds) and (Tickets.archived eq false)
		return Tickets.selectAll().where(where).count().toInt()
	}

	fun countByProject(projectId: UUID, includeArchived: Boolean = true): Int {
		val where =
			if (includeArchived) Tickets.projectId eq projectId
			else (Tickets.projectId eq projectId) and (Tickets.archived eq false)
		return Tickets.selectAll().where(where).count().toInt()
	}

	/**
	 * The drafts, newest first — tickets no team has claimed.
	 *
	 * [authorId] null is the instance admin's view, which is every draft: `created_by` is
	 * null on every row written before `V20` and on any whose author has since been deleted,
	 * and somebody has to be able to reach those. Everyone else sees only their own, which
	 * is the whole of the access rule for a ticket with no team to decide it.
	 */
	fun findDrafts(authorId: UUID?, limit: Int): List<Ticket> {
		val mine = if (authorId == null) Op.TRUE else (Tickets.createdBy eq authorId)
		return Tickets.selectAll()
			.where { Tickets.teamId.isNull() and mine and (Tickets.id notInSubQuery trashed) }
			.orderBy(Tickets.createdAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toTicket() }
	}

	fun idsByTeam(teamId: UUID): List<UUID> =
		Tickets.select(Tickets.id).where { Tickets.teamId eq teamId }
			.orderBy(Tickets.number to SortOrder.ASC)
			.map { it[Tickets.id] }

	/**
	 * A move renames the ticket for good: `UNIQUE (team_id, number)` leaves no choice.
	 *
	 * Also the first naming, for a draft arriving from no team at all — the write is the
	 * same one, and the pair is set together so `tickets_team_number_together_chk` is never
	 * momentarily false.
	 */
	/**
	 * The three columns a team boundary moves, in one statement — `KAN-90` added the third.
	 *
	 * [status] has to be written here rather than by the `update` that follows, and this is
	 * not a tidiness argument: `tickets_status_fk` is a composite key onto
	 * `(team_id, key)` and it is **not deferred**, so a statement that set `team_id` while
	 * `status` still held the source team's word would be refused by Postgres — measured,
	 * as `insert or update on table "tickets" violates foreign key constraint
	 * "tickets_status_fk"`. The caller rebases first and hands the answer in.
	 *
	 * `number` was already here for `UNIQUE (team_id, number)`, which is the same shape of
	 * reason: crossing into another team is one transition over three columns, not three.
	 */
	fun moveToTeam(ticketId: UUID, teamId: UUID, number: Int, status: String): Boolean =
		Tickets.update({ Tickets.id eq ticketId }) {
			it[Tickets.teamId] = teamId
			it[Tickets.number] = number
			it[Tickets.status] = status
		} > 0

	/** Returns only the rows that actually changed — the others need no mirror push. */
	fun setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Tickets.select(Tickets.id)
			.where { (Tickets.teamId inList teamIds) and (Tickets.archived neq archived) }
			.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.archived] = archived }
		return ids
	}

	/**
	 * Returns what it removed. `tickets.team_id` is ON DELETE CASCADE, so dropping the
	 * team would take these with it in Postgres while leaving the Notion page behind —
	 * the mirror outliving the source of truth. Removing them here means one sync job
	 * each.
	 */
	fun deleteByTeams(teamIds: Collection<UUID>): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Tickets.select(Tickets.id).where { Tickets.teamId inList teamIds }.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.deleteWhere { Tickets.id inList ids }
		return ids
	}

	/**
	 * Which of [ids] belong to one of [teamIds] — what a team's disposition has to forget.
	 *
	 * Unfiltered on the trash, unlike everything else that answers "which tickets are
	 * there": the caller's [ids] *are* the trash, and the point of asking is that
	 * `team_id`'s cascade is about to take them.
	 */
	fun idsWithinTeams(ids: Collection<UUID>, teamIds: Collection<UUID>): List<UUID> =
		if (ids.isEmpty() || teamIds.isEmpty()) emptyList()
		else Tickets.select(Tickets.id)
			.where { (Tickets.id inList ids) and (Tickets.teamId inList teamIds) }
			.map { it[Tickets.id] }

	/**
	 * What one person finished since [since], newest completion first.
	 *
	 * [TicketQueryRepository] cannot express it: `completed_at` is not a facet, and it must
	 * never become one — a chip nobody can take off is not a filter, and every list in the
	 * app already runs that predicate. This is a range read over one column for one screen.
	 *
	 * `completed_at IS NOT NULL` is the whole of "finished", with no status clause beside
	 * it. `TicketService.patch` is the only writer: it stamps the column when a ticket
	 * enters the completed category and clears it when it leaves, so a second condition on
	 * the status would be a chance for the two to disagree rather than a safeguard.
	 *
	 * Archived rows are **included**, unlike every scope query above. Archiving is filing,
	 * not undoing — a ticket somebody finished in June and tidied away in July is still
	 * work they finished in June, and dropping it would make a personal history shrink as
	 * its owner cleaned up. The trash is still excluded: a deleted ticket is gone.
	 */
	fun findCompletedSince(assigneeId: UUID, since: OffsetDateTime, limit: Int): List<Ticket> =
		Tickets.selectAll()
			.where {
				(Tickets.completedAt greaterEq since) and
					// A ticket with no team is a draft, which is in no list on any screen —
					// `TicketQueryRepository` argues this at length — and has no identifier to
					// print in one either.
					Tickets.teamId.isNotNull() and
					(
						Tickets.id inSubQuery TicketAssignees.select(TicketAssignees.ticketId)
							.where { TicketAssignees.userId eq assigneeId }
						) and
					(Tickets.id notInSubQuery trashed)
			}
			.orderBy(Tickets.completedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toTicket() }

	fun idsByProject(projectId: UUID): List<UUID> =
		Tickets.select(Tickets.id).where { Tickets.projectId eq projectId }.map { it[Tickets.id] }

	/**
	 * Keeping a ticket whose project does not travel with it costs it only the
	 * grouping — the by-ticket counterpart of [clearProject], for the disposition that
	 * sends a team's projects and its tickets to two different places.
	 */
	fun clearProjectFor(ticketIds: Collection<UUID>): List<UUID> {
		if (ticketIds.isEmpty()) return emptyList()
		val ids = Tickets.select(Tickets.id)
			.where { (Tickets.id inList ticketIds) and Tickets.projectId.isNotNull() }
			.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.projectId] = null }
		return ids
	}

	/** Every ticket of this project that sits outside [teamId] — what a move would orphan. */
	fun countByProjectOutsideTeam(projectId: UUID, teamId: UUID): Int =
		Tickets.selectAll()
			.where { (Tickets.projectId eq projectId) and (Tickets.teamId neq teamId) }
			.count().toInt()

	/** Keeping a ticket whose project goes away costs it only the grouping. */
	fun clearProject(projectId: UUID): List<UUID> {
		val ids = idsByProject(projectId)
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.projectId] = null }
		return ids
	}

	fun setArchivedByProject(projectId: UUID, archived: Boolean): List<UUID> {
		val ids = Tickets.select(Tickets.id)
			.where { (Tickets.projectId eq projectId) and (Tickets.archived neq archived) }
			.map { it[Tickets.id] }
		if (ids.isNotEmpty()) Tickets.update({ Tickets.id inList ids }) { it[Tickets.archived] = archived }
		return ids
	}

	fun deleteByProject(projectId: UUID): List<UUID> {
		val ids = idsByProject(projectId)
		if (ids.isNotEmpty()) Tickets.deleteWhere { Tickets.id inList ids }
		return ids
	}

	// --- assignees -----------------------------------------------------------

	fun assigneeIds(ticketId: UUID): List<UUID> =
		TicketAssignees.select(TicketAssignees.userId).where { TicketAssignees.ticketId eq ticketId }
			.map { it[TicketAssignees.userId] }

	/**
	 * Ordered by the column, which is what makes the head of each list mean something.
	 * Grouping by assignee files a ticket with two owners under the first of them, and
	 * unordered this was whichever row Postgres happened to hand back — so a ticket could
	 * change group between two refetches of the same question. See
	 * `TicketQueryRepository.firstAssignee`, which picks the same person with a `MIN`.
	 */
	fun assigneeIdsFor(ticketIds: Collection<UUID>): Map<UUID, List<UUID>> =
		if (ticketIds.isEmpty()) emptyMap()
		else TicketAssignees.selectAll().where { TicketAssignees.ticketId inList ticketIds }
			.orderBy(TicketAssignees.userId)
			.groupBy({ it[TicketAssignees.ticketId] }, { it[TicketAssignees.userId] })

	fun setAssignees(ticketId: UUID, userIds: Collection<UUID>) {
		TicketAssignees.deleteWhere { TicketAssignees.ticketId eq ticketId }
		if (userIds.isNotEmpty()) {
			TicketAssignees.batchInsert(userIds.distinct()) { userId ->
				this[TicketAssignees.ticketId] = ticketId
				this[TicketAssignees.userId] = userId
			}
		}
	}

	// --- docs ----------------------------------------------------------------

	fun docIds(ticketId: UUID): List<UUID> =
		TicketDocs.select(TicketDocs.docId).where { TicketDocs.ticketId eq ticketId }
			.map { it[TicketDocs.docId] }

	fun docIdsFor(ticketIds: Collection<UUID>): Map<UUID, List<UUID>> =
		if (ticketIds.isEmpty()) emptyMap()
		else TicketDocs.selectAll().where { TicketDocs.ticketId inList ticketIds }
			.groupBy({ it[TicketDocs.ticketId] }, { it[TicketDocs.docId] })

	fun setDocs(ticketId: UUID, docIds: Collection<UUID>) {
		TicketDocs.deleteWhere { TicketDocs.ticketId eq ticketId }
		if (docIds.isNotEmpty()) {
			TicketDocs.batchInsert(docIds.distinct()) { docId ->
				this[TicketDocs.ticketId] = ticketId
				this[TicketDocs.docId] = docId
			}
		}
	}

	// --- sub-tickets ---------------------------------------------------------

	fun setParent(id: UUID, parentId: UUID?): Boolean =
		Tickets.update({ Tickets.id eq id }) { it[Tickets.parentId] = parentId } > 0

	/**
	 * The children of each of [parentIds], keyed by parent.
	 *
	 * One query for a whole screen rather than one per parent: the list draws every
	 * parent in the page at once, and the alternative is a query per row on the most-used
	 * screen in the product.
	 */
	fun childrenOf(parentIds: Collection<UUID>): Map<UUID, List<Ticket>> {
		if (parentIds.isEmpty()) return emptyMap()
		return Tickets.selectAll()
			.where { Tickets.parentId inList parentIds }
			.groupBy({ it[Tickets.parentId]!! }, { it.toTicket() })
	}

	/**
	 * Whether [id] has any children at all — asked by the depth rule, which needs the
	 * answer and not the rows.
	 */
	fun hasChildren(id: UUID): Boolean =
		Tickets.selectAll().where { Tickets.parentId eq id }.limit(1).any()

	// --- mirror bookkeeping --------------------------------------------------

	fun markSynced(id: UUID, notionPageId: String, notionLastEdited: OffsetDateTime?) {
		Tickets.update({ Tickets.id eq id }) {
			it[Tickets.notionPageId] = notionPageId
			it[syncState] = SyncState.SYNCED.wire
			it[notionSyncedAt] = OffsetDateTime.now()
			it[notionLastEditedTime] = notionLastEdited
		}
	}

	fun markSyncState(id: UUID, state: SyncState) {
		Tickets.update({ Tickets.id eq id }) { it[syncState] = state.wire }
	}

	fun recordNotionEdit(id: UUID, lastEdited: OffsetDateTime?) {
		Tickets.update({ Tickets.id eq id }) { it[notionLastEditedTime] = lastEdited }
	}

	/**
	 * What [status] means for [teamId] — the fallback chain, reached from the repository.
	 *
	 * One query, and only on a write: [insert] is a single row, so the N+1 that
	 * `StatusCategories.of` exists to prevent cannot arise here.
	 */
	private fun categoryOf(teamId: UUID?, status: String): StatusCategory =
		dev.kanso.service.StatusCategories.resolve(
			teamId?.let { id -> teamStatuses.forTeam(id).associate { it.key to it.category } },
			status,
		)

	/**
	 * `internal` rather than private: `MovedAlongTest` pins which categories count as
	 * movement, and that assertion is the point of the constant — a test that copied the
	 * list instead would pass while the probe drifted.
	 */
	internal companion object {
		/**
		 * Neither "we might" nor "we will" — the two categories
		 * `PublicRoadmapService.NOT_STARTED_STATUSES` calls a first step, read the other
		 * way round.
		 *
		 * Filtered off [DefaultStatus.category] rather than spelled as four names, for the
		 * reason that property exists: a seventh status is then classified once, where the
		 * mapping is the definition, instead of being silently absent from this list.
		 *
		 * `canceled` is in here, which reads odd and is right: somebody decided about that
		 * ticket, and deciding not to do it is the step this asks about having happened.
		 */
		val MOVED_ALONG_CATEGORIES = StatusCategory.entries
			.filter { it != StatusCategory.BACKLOG && it != StatusCategory.UNSTARTED }
	}
}

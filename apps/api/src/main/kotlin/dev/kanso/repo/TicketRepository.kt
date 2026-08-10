package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketDocs
import dev.kanso.db.Tickets
import dev.kanso.db.toTicket
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.SyncState
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketRepository {

	fun findById(id: UUID): Ticket? =
		Tickets.selectAll().where { Tickets.id eq id }.singleOrNull()?.toTicket()

	/** Whole rows for a set of ids — what the scheduler loads a dependency graph with. */
	fun findAllById(ids: Collection<UUID>): List<Ticket> =
		if (ids.isEmpty()) emptyList()
		else Tickets.selectAll().where { Tickets.id inList ids }.map { it.toTicket() }

	fun findByNotionPageId(pageId: String): Ticket? =
		Tickets.selectAll().where { Tickets.notionPageId eq pageId }.singleOrNull()?.toTicket()

	fun findByTeamAndNumber(teamId: UUID, number: Int): Ticket? =
		Tickets.selectAll().where { (Tickets.teamId eq teamId) and (Tickets.number eq number) }
			.singleOrNull()?.toTicket()

	fun search(
		teamIds: Collection<UUID>? = null,
		projectId: UUID? = null,
		statuses: Collection<TicketStatus> = emptyList(),
		assigneeId: UUID? = null,
		includeArchived: Boolean = false,
		limit: Int = 200,
		offset: Long = 0,
	): List<Ticket> {
		val conditions = buildList {
			if (!includeArchived) add(Tickets.archived eq false)
			if (teamIds != null) add(Tickets.teamId inList teamIds)
			if (projectId != null) add(Tickets.projectId eq projectId)
			if (statuses.isNotEmpty()) add(Tickets.status inList statuses.map { it.wire })
			if (assigneeId != null) {
				add(
					Tickets.id inSubQuery TicketAssignees
						.select(TicketAssignees.ticketId)
						.where { TicketAssignees.userId eq assigneeId }
				)
			}
		}
		val where = if (conditions.isEmpty()) Op.TRUE else conditions.compoundAnd()
		return Tickets.selectAll().where(where)
			.orderBy(Tickets.updatedAt to SortOrder.DESC)
			.limit(limit).offset(offset)
			.map { it.toTicket() }
	}

	fun insert(
		id: UUID,
		number: Int,
		teamId: UUID,
		title: String,
		description: String?,
		status: TicketStatus,
		priority: TicketPriority,
		start: KansoInstant?,
		due: KansoInstant?,
		projectId: UUID?,
	): Ticket {
		val now = OffsetDateTime.now()
		Tickets.insert {
			it[Tickets.id] = id
			it[Tickets.number] = number
			it[Tickets.teamId] = teamId
			it[Tickets.title] = title
			it[Tickets.description] = description
			it[Tickets.status] = status.wire
			it[Tickets.priority] = priority.wire
			it[Tickets.startAt] = start?.at
			it[Tickets.startHasTime] = start?.hasTime ?: false
			it[Tickets.dueAt] = due?.at
			it[Tickets.dueHasTime] = due?.hasTime ?: false
			// A ticket can be created already done — logging work that is finished is a
			// normal thing to do. Leaving this null would hide it from the project bounds,
			// which fall back on completion dates precisely when nobody planned anything.
			it[Tickets.completedAt] = if (status == TicketStatus.DONE) now else null
			it[Tickets.projectId] = projectId
			it[archived] = false
			it[syncState] = SyncState.PENDING.wire
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun update(
		id: UUID,
		teamId: UUID,
		title: String,
		description: String?,
		status: TicketStatus,
		priority: TicketPriority,
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
			it[Tickets.status] = status.wire
			it[Tickets.priority] = priority.wire
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
	 */
	fun reschedule(id: UUID, start: OffsetDateTime, end: OffsetDateTime) {
		Tickets.update({ Tickets.id eq id }) {
			it[startAt] = start
			it[dueAt] = end
		}
	}

	fun delete(id: UUID): Boolean = Tickets.deleteWhere { Tickets.id eq id } > 0

	// --- bulk reads ----------------------------------------------------------

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

	fun idsByTeam(teamId: UUID): List<UUID> =
		Tickets.select(Tickets.id).where { Tickets.teamId eq teamId }
			.orderBy(Tickets.number to SortOrder.ASC)
			.map { it[Tickets.id] }

	/** A move renames the ticket for good: `UNIQUE (team_id, number)` leaves no choice. */
	fun moveToTeam(ticketId: UUID, teamId: UUID, number: Int): Boolean =
		Tickets.update({ Tickets.id eq ticketId }) {
			it[Tickets.teamId] = teamId
			it[Tickets.number] = number
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

	fun assigneeIdsFor(ticketIds: Collection<UUID>): Map<UUID, List<UUID>> =
		if (ticketIds.isEmpty()) emptyMap()
		else TicketAssignees.selectAll().where { TicketAssignees.ticketId inList ticketIds }
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
}

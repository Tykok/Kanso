package dev.kanso.repo

import dev.kanso.db.TicketAssignees
import dev.kanso.db.TicketDocs
import dev.kanso.db.Tickets
import dev.kanso.db.toTicket
import dev.kanso.domain.SyncState
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketRepository {

	fun findById(id: UUID): Ticket? =
		Tickets.selectAll().where { Tickets.id eq id }.singleOrNull()?.toTicket()

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
		startDate: LocalDate?,
		dueDate: LocalDate?,
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
			it[Tickets.startDate] = startDate
			it[Tickets.dueDate] = dueDate
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
		startDate: LocalDate?,
		dueDate: LocalDate?,
		projectId: UUID?,
		archived: Boolean,
	): Ticket? {
		val changed = Tickets.update({ Tickets.id eq id }) {
			it[Tickets.teamId] = teamId
			it[Tickets.title] = title
			it[Tickets.description] = description
			it[Tickets.status] = status.wire
			it[Tickets.priority] = priority.wire
			it[Tickets.startDate] = startDate
			it[Tickets.dueDate] = dueDate
			it[Tickets.projectId] = projectId
			it[Tickets.archived] = archived
		}
		return if (changed == 0) null else findById(id)
	}

	fun delete(id: UUID): Boolean = Tickets.deleteWhere { Tickets.id eq id } > 0

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

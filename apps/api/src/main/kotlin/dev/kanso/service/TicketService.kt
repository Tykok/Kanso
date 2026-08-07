package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DocRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.deletePayload
import dev.kanso.sync.SyncOperation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** A ticket with its team key (for `KAN-142`) and its relations already loaded. */
data class TicketDetail(
	val ticket: Ticket,
	val teamKey: String,
	val assigneeIds: List<UUID>,
	val docIds: List<UUID>,
) {
	val identifier: String get() = "$teamKey-${ticket.number}"
}

/**
 * Fields a PATCH may change. `null` means "leave alone"; to actually clear a
 * nullable column, name it in [unset]. JSON cannot otherwise distinguish an
 * absent key from an explicit null, and silently ignoring `"dueDate": null`
 * would make clearing a date impossible.
 */
data class TicketPatch(
	val title: String? = null,
	val description: String? = null,
	val status: TicketStatus? = null,
	val priority: TicketPriority? = null,
	val startDate: LocalDate? = null,
	val dueDate: LocalDate? = null,
	val projectId: UUID? = null,
	val teamId: UUID? = null,
	val archived: Boolean? = null,
	val assigneeIds: List<UUID>? = null,
	val docIds: List<UUID>? = null,
	val unset: Set<String> = emptySet(),
)

@Service
class TicketService(
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {

	@Transactional(readOnly = true)
	fun search(
		teamId: UUID?,
		includeDescendants: Boolean,
		projectId: UUID?,
		statuses: List<TicketStatus>,
		assigneeId: UUID?,
		includeArchived: Boolean,
		limit: Int,
		offset: Long,
	): List<TicketDetail> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		val found = tickets.search(teamIds, projectId, statuses, assigneeId, includeArchived, limit, offset)
		return decorate(found)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): TicketDetail {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		return decorate(listOf(ticket)).single()
	}

	@Transactional(readOnly = true)
	fun getByIdentifier(teamKey: String, number: Int): TicketDetail {
		val team = teams.findByKey(teamKey.uppercase())
			?: throw NotFoundException("No team with key $teamKey")
		val ticket = tickets.findByTeamAndNumber(team.id, number)
			?: throw NotFoundException("No ticket $teamKey-$number")
		return TicketDetail(ticket, team.key, tickets.assigneeIds(ticket.id), tickets.docIds(ticket.id))
	}

	@Transactional
	fun create(
		teamId: UUID,
		title: String,
		description: String?,
		status: TicketStatus,
		priority: TicketPriority,
		startDate: LocalDate?,
		dueDate: LocalDate?,
		projectId: UUID?,
		assigneeIds: List<UUID>,
		docIds: List<UUID>,
	): TicketDetail {
		val team = teams.findById(teamId) ?: throw BadRequestException("No team $teamId")
		validateDates(startDate, dueDate)
		projectId?.let { requireProject(it) }
		requireUsers(assigneeIds)
		requireDocs(docIds)

		// Allocated inside this transaction: the row lock on the team serialises
		// concurrent creates, so two people pressing "c" at once get 41 and 42.
		val number = teams.nextTicketNumber(teamId)
		val ticket = tickets.insert(
			id = UUID.randomUUID(),
			number = number,
			teamId = teamId,
			title = title,
			description = description,
			status = status,
			priority = priority,
			startDate = startDate,
			dueDate = dueDate,
			projectId = projectId,
		)
		tickets.setAssignees(ticket.id, assigneeIds)
		tickets.setDocs(ticket.id, docIds)

		syncJobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.CREATED, ticket.id, teamId, projectId))
		return TicketDetail(ticket, team.key, assigneeIds, docIds)
	}

	/**
	 * The hot path: a status change from the keyboard. Loads, merges, writes the
	 * full row, then queues one mirror push.
	 */
	@Transactional
	fun patch(id: UUID, patch: TicketPatch): TicketDetail {
		val current = tickets.findById(id) ?: throw NotFoundException("No ticket $id")

		val teamId = patch.teamId ?: current.teamId
		if (patch.teamId != null && teams.findById(patch.teamId) == null) {
			throw BadRequestException("No team ${patch.teamId}")
		}
		val projectId = when {
			"projectId" in patch.unset -> null
			patch.projectId != null -> patch.projectId.also { requireProject(it) }
			else -> current.projectId
		}
		val startDate = if ("startDate" in patch.unset) null else patch.startDate ?: current.startDate
		val dueDate = if ("dueDate" in patch.unset) null else patch.dueDate ?: current.dueDate
		validateDates(startDate, dueDate)

		patch.assigneeIds?.let { requireUsers(it) }
		patch.docIds?.let { requireDocs(it) }

		val updated = tickets.update(
			id = id,
			teamId = teamId,
			title = patch.title ?: current.title,
			description = if ("description" in patch.unset) null else patch.description ?: current.description,
			status = patch.status ?: current.status,
			priority = patch.priority ?: current.priority,
			startDate = startDate,
			dueDate = dueDate,
			projectId = projectId,
			archived = patch.archived ?: current.archived,
		) ?: throw NotFoundException("No ticket $id")

		patch.assigneeIds?.let { tickets.setAssignees(id, it) }
		patch.docIds?.let { tickets.setDocs(id, it) }

		syncJobs.enqueue(
			SyncEntityType.TICKET,
			id,
			if (updated.archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, updated.teamId, updated.projectId))
		return decorate(listOf(updated)).single()
	}

	@Transactional
	fun delete(id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		syncJobs.enqueue(
			SyncEntityType.TICKET,
			id,
			SyncOperation.DELETE,
			payload = deletePayload(ticket.mirror.notionPageId),
		)
		tickets.delete(id)
		events.publish(KansoEvent.ticket(ChangeKind.DELETED, id, ticket.teamId, ticket.projectId))
	}

	@Transactional
	fun setAssignees(id: UUID, userIds: List<UUID>): TicketDetail {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		requireUsers(userIds)
		tickets.setAssignees(id, userIds)
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
		return decorate(listOf(ticket)).single()
	}

	@Transactional
	fun setDocs(id: UUID, docIds: List<UUID>): TicketDetail {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		requireDocs(docIds)
		tickets.setDocs(id, docIds)
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
		return decorate(listOf(ticket)).single()
	}

	// --- helpers -------------------------------------------------------------

	/** One extra query per relation for the whole page, instead of two per row. */
	private fun decorate(found: List<Ticket>): List<TicketDetail> {
		if (found.isEmpty()) return emptyList()
		val ids = found.map { it.id }
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associate { it.id to it.key }
		val assignees = tickets.assigneeIdsFor(ids)
		val docsByTicket = tickets.docIdsFor(ids)
		return found.map {
			TicketDetail(
				ticket = it,
				teamKey = keys[it.teamId] ?: "?",
				assigneeIds = assignees[it.id].orEmpty(),
				docIds = docsByTicket[it.id].orEmpty(),
			)
		}
	}

	private fun validateDates(startDate: LocalDate?, dueDate: LocalDate?) {
		if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
			throw BadRequestException("dueDate $dueDate is before startDate $startDate")
		}
	}

	private fun requireProject(id: UUID) {
		projects.findById(id) ?: throw BadRequestException("No project $id")
	}

	private fun requireUsers(ids: List<UUID>) {
		if (ids.isEmpty()) return
		val found = users.findAllById(ids).map { it.id }.toSet()
		val missing = ids.toSet() - found
		if (missing.isNotEmpty()) throw BadRequestException("Unknown users: ${missing.joinToString()}")
	}

	private fun requireDocs(ids: List<UUID>) {
		if (ids.isEmpty()) return
		val found = docs.findAllById(ids).map { it.id }.toSet()
		val missing = ids.toSet() - found
		if (missing.isNotEmpty()) throw BadRequestException("Unknown docs: ${missing.joinToString()}")
	}
}

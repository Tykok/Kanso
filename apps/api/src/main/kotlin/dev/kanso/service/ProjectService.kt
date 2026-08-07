package dev.kanso.service

import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DocRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.deletePayload
import dev.kanso.sync.SyncOperation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** A project plus the relations a caller almost always wants alongside it. */
data class ProjectDetail(val project: Project, val docIds: List<UUID>)

@Service
class ProjectService(
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID?, includeDescendants: Boolean, includeArchived: Boolean): List<ProjectDetail> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		val found = projects.search(teamIds, includeArchived)
		val docsByProject = projects.docIdsFor(found.map { it.id })
		return found.map { ProjectDetail(it, docsByProject[it.id].orEmpty()) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): ProjectDetail {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		return ProjectDetail(project, projects.docIds(id))
	}

	@Transactional
	fun create(
		name: String,
		status: ProjectStatus,
		startDate: LocalDate?,
		endDate: LocalDate?,
		leadUserId: UUID?,
		teamId: UUID?,
		docIds: List<UUID>,
	): ProjectDetail {
		validateDates(startDate, endDate)
		teamId?.let { requireTeam(it) }
		leadUserId?.let { requireUser(it) }
		requireDocs(docIds)

		val project = projects.insert(name, status, startDate, endDate, leadUserId, teamId)
		projects.setDocs(project.id, docIds)
		syncJobs.enqueue(SyncEntityType.PROJECT, project.id, SyncOperation.UPSERT)
		events.publish(KansoEvent.project(ChangeKind.CREATED, project.id, teamId))
		return ProjectDetail(project, docIds)
	}

	@Transactional
	fun update(
		id: UUID,
		name: String,
		status: ProjectStatus,
		startDate: LocalDate?,
		endDate: LocalDate?,
		leadUserId: UUID?,
		teamId: UUID?,
		archived: Boolean,
		docIds: List<UUID>?,
	): ProjectDetail {
		projects.findById(id) ?: throw NotFoundException("No project $id")
		validateDates(startDate, endDate)
		teamId?.let { requireTeam(it) }
		leadUserId?.let { requireUser(it) }
		docIds?.let { requireDocs(it) }

		val updated = projects.update(id, name, status, startDate, endDate, leadUserId, teamId, archived)
			?: throw NotFoundException("No project $id")
		if (docIds != null) projects.setDocs(id, docIds)

		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			id,
			if (archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, id, teamId))
		return ProjectDetail(updated, projects.docIds(id))
	}

	@Transactional
	fun delete(id: UUID) {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			id,
			SyncOperation.DELETE,
			payload = deletePayload(project.mirror.notionPageId),
		)
		projects.delete(id)
		events.publish(KansoEvent.project(ChangeKind.DELETED, id, project.teamId))
	}

	private fun validateDates(startDate: LocalDate?, endDate: LocalDate?) {
		if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
			throw BadRequestException("endDate $endDate is before startDate $startDate")
		}
	}

	private fun requireTeam(id: UUID) {
		teams.findById(id) ?: throw BadRequestException("No team $id")
	}

	private fun requireUser(id: UUID) {
		users.findById(id) ?: throw BadRequestException("No user $id")
	}

	private fun requireDocs(ids: List<UUID>) {
		if (ids.isEmpty()) return
		val found = docs.findAllById(ids).map { it.id }.toSet()
		val missing = ids.toSet() - found
		if (missing.isNotEmpty()) throw BadRequestException("Unknown docs: ${missing.joinToString()}")
	}
}

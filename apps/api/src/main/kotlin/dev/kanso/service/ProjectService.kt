package dev.kanso.service

import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionContents
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
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
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A project plus the relations a caller almost always wants alongside it. */
data class ProjectDetail(val project: Project, val docIds: List<UUID>)

@Service
class ProjectService(
	private val projects: ProjectRepository,
	private val teams: TeamRepository,
	private val tickets: TicketRepository,
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

	// --- disposition ---------------------------------------------------------

	/** A project holds no teams and no projects; only its tickets need a decision. */
	@Transactional(readOnly = true)
	fun contents(id: UUID): DispositionContents {
		get(id)
		// A project holds no teams, so there is no subtree for a plan to reach: the two
		// readings the modal switches between are the same number here.
		val counts = countsOf(id)
		return DispositionContents(direct = counts, subtree = counts)
	}

	private fun countsOf(id: UUID) =
		DispositionCounts(subTeams = 0, projects = 0, tickets = tickets.countByProject(id))

	@Transactional
	fun create(
		name: String,
		status: ProjectStatus,
		start: KansoInstant?,
		end: KansoInstant?,
		leadUserId: UUID?,
		teamId: UUID?,
		docIds: List<UUID>,
	): ProjectDetail {
		validateDates(start, end)
		teamId?.let { requireTeam(it) }
		leadUserId?.let { requireUser(it) }
		requireDocs(docIds)

		val project = projects.insert(name, status, start, end, leadUserId, teamId)
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
		start: KansoInstant?,
		end: KansoInstant?,
		leadUserId: UUID?,
		teamId: UUID?,
		docIds: List<UUID>?,
	): ProjectDetail {
		val existing = projects.findById(id) ?: throw NotFoundException("No project $id")
		validateDates(start, end)
		teamId?.let { requireTeam(it) }
		leadUserId?.let { requireUser(it) }
		docIds?.let { requireDocs(it) }

		// A ticket's project belongs to its team, and this is the writer that can break
		// it wholesale: moving a project into another team leaves every ticket still
		// pointing at it in a team that never had it. Refused rather than repaired,
		// because the repair — silently dropping the grouping of tickets nobody named —
		// is the loss `TicketService.patch` reserves for the one case that *was* asked
		// for. Clearing the team instead is always allowed: a team-less project is the
		// transverse case and belongs everywhere.
		if (teamId != null && teamId != existing.teamId) {
			val strays = tickets.countByProjectOutsideTeam(id, teamId)
			if (strays > 0) {
				throw ConflictException(
					"Project $id still holds $strays ticket(s) outside team $teamId; " +
						"move them first, or clear the project's team",
				)
			}
		}

		// Archiving has its own verb; an edit never changes that flag by accident.
		val updated = projects.update(id, name, status, start, end, leadUserId, teamId, existing.archived)
			?: throw NotFoundException("No project $id")
		if (docIds != null) projects.setDocs(id, docIds)

		syncJobs.enqueue(SyncEntityType.PROJECT, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, id, teamId))
		return ProjectDetail(updated, projects.docIds(id))
	}

	@Transactional
	fun archive(actor: User, id: UUID, plan: DispositionPlan): ProjectDetail {
		requireConfigurator(actor)
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		disperseTickets(id, plan, destructive = false)
		return setArchived(project, true)
	}

	/** Only the project comes back: its tickets were disposed of by an explicit choice. */
	@Transactional
	fun unarchive(id: UUID): ProjectDetail {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		return setArchived(project, false)
	}

	/**
	 * Deleting locally still archives in Notion, and still asks whether the counts on
	 * screen are the ones being agreed to — see `TeamService.delete` for why only the
	 * destructive side pays for that.
	 */
	@Transactional
	fun delete(actor: User, id: UUID, plan: DispositionPlan) {
		requireConfigurator(actor)
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")

		val declared = plan.counts
			?: throw BadRequestException("Deleting a project requires the counts the confirmation showed")
		val fresh = countsOf(id)
		if (declared != fresh) throw CountsChangedException(fresh)

		disperseTickets(id, plan, destructive = true)
		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			id,
			SyncOperation.DELETE,
			payload = deletePayload(project.mirror.notionPageId),
		)
		projects.delete(id)
		events.publish(KansoEvent.project(ChangeKind.DELETED, id, project.teamId))
	}

	/**
	 * `ticketsTargetTeamId` plays no part here: a ticket already has a team of its own,
	 * so keeping one costs it only its `project_id`. That is the whole difference
	 * between a project and a team.
	 */
	private fun disperseTickets(projectId: UUID, plan: DispositionPlan, destructive: Boolean) {
		val held = tickets.search(projectId = projectId, includeArchived = true, limit = Int.MAX_VALUE)
		if (held.isEmpty()) return
		val byId = held.associateBy { it.id }

		when {
			plan.tickets == DispositionChoice.KEEP -> tickets.clearProject(projectId).forEach {
				syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.UPSERT)
				events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, null))
			}

			destructive -> {
				tickets.deleteByProject(projectId)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.TICKET,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.ticket(ChangeKind.DELETED, it.id, it.teamId, projectId))
				}
			}

			else -> tickets.setArchivedByProject(projectId, true).forEach {
				syncJobs.enqueue(SyncEntityType.TICKET, it, SyncOperation.ARCHIVE)
				events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it, byId[it]?.teamId, projectId))
			}
		}
	}

	private fun setArchived(project: Project, archived: Boolean): ProjectDetail {
		val updated = projects.update(
			id = project.id,
			name = project.name,
			status = project.status,
			start = project.start,
			end = project.end,
			leadUserId = project.leadUserId,
			teamId = project.teamId,
			archived = archived,
		) ?: throw NotFoundException("No project ${project.id}")
		syncJobs.enqueue(
			SyncEntityType.PROJECT,
			project.id,
			if (archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, project.id, project.teamId))
		return ProjectDetail(updated, projects.docIds(project.id))
	}

	/**
	 * Archiving or deleting a project reaches every ticket it holds, across whichever
	 * teams those tickets belong to — the same blast radius as `TeamService.archive`
	 * and `TeamService.delete`, and the same rule applies: disposition is instance
	 * configuration, not daily work, regardless of which container is being disposed of.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can archive or delete a project")
		}
	}

	private fun validateDates(start: KansoInstant?, end: KansoInstant?) {
		if (start != null && end != null && end.at.isBefore(start.at)) {
			throw BadRequestException("end ${end.at} is before start ${start.at}")
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

package dev.kanso.service

import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionContents
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectHealth
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DocRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.ProjectUpdateRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.outbound.deletePayload
import dev.kanso.trash.TrashDisposal
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A project plus the relations a caller almost always wants alongside it.
 *
 * [health] is derived on every read from the newest row of `project_updates` and is
 * **not** a column — `V23` carries the argument, which is `V10`'s. Null means nobody has
 * assessed this project, which is a different fact from [ProjectHealth.ON_TRACK] and must
 * never be collapsed into it: defaulting to the optimistic value would turn the whole
 * signal green on the day it shipped. It is also independent of [Project.status] in both
 * directions — see [ProjectHealth].
 */
data class ProjectDetail(
	val project: Project,
	val docIds: List<UUID>,
	val health: ProjectHealth? = null,
)

@Service
class ProjectService(
	private val projects: ProjectRepository,
	private val updates: ProjectUpdateRepository,
	private val teams: TeamRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val outbox: OutboundJobRepository,
	private val events: EventPublisher,
	private val trash: TrashDisposal,
	private val access: TicketAccess,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID?, includeDescendants: Boolean, includeArchived: Boolean): List<ProjectDetail> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		val found = projects.search(teamIds, includeArchived)
		val docsByProject = projects.docIdsFor(found.map { it.id })
		// One `DISTINCT ON` for the whole list rather than one read per project: the sidebar
		// asks for every project in the instance, and health is drawn on each of its rows.
		val healthByProject = updates.latestHealthFor(found.map { it.id })
		return found.map { ProjectDetail(it, docsByProject[it.id].orEmpty(), healthByProject[it.id]) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): ProjectDetail {
		val project = projects.findById(id) ?: throw NotFoundException("No project $id")
		return ProjectDetail(project, projects.docIds(id), updates.latest(id)?.health)
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
		actor: User,
		name: String,
		status: ProjectStatus,
		start: KansoInstant?,
		end: KansoInstant?,
		leadUserId: UUID?,
		teamId: UUID?,
		docIds: List<UUID>,
	): ProjectDetail {
		validateDates(start, end)
		// Filing a project into a team is editing that team's plan, so it takes the team
		// rule and not `requireConfigurator` — the distinction `ProjectUpdateService`
		// draws for this package: daily work versus configuring the instance. A project
		// with no team is left open for the reason stated there too, that the transverse
		// ones are the least likely to have anybody watching them.
		teamId?.let { requireTeamExists(it); access.requireTeam(actor, it) }
		leadUserId?.let { requireUser(it) }
		requireDocs(docIds)

		val project = projects.insert(name, status, start, end, leadUserId, teamId)
		projects.setDocs(project.id, docIds)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.PROJECT, project.id, OutboundOperation.UPSERT)
		events.publish(KansoEvent.project(ChangeKind.CREATED, project.id, teamId))
		// No health: a project that has just been created has nobody's assessment on it yet,
		// and the absence is the honest answer rather than a starting value.
		return ProjectDetail(project, docIds)
	}

	@Transactional
	fun update(
		actor: User,
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
		// Both sides of a move, the way `TicketService.patch` checks both: asking only
		// about the destination would make `teamId` a second request that carries a
		// project out of a team the actor may not edit, and asking only about the origin
		// would let them push one into a team that is not theirs.
		existing.teamId?.let { access.requireTeam(actor, it) }
		teamId?.let { requireTeamExists(it); access.requireTeam(actor, it) }
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

		outbox.enqueue(Destination.NOTION, OutboundEntityType.PROJECT, id, OutboundOperation.UPSERT)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, id, teamId))
		// Editing a project cannot touch its health — including when it moves the status,
		// which is the one edit somebody will expect to. The health is read back out of the
		// updates, unchanged, because nothing here wrote to them.
		return ProjectDetail(updated, projects.docIds(id), updates.latest(id)?.health)
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
	fun unarchive(actor: User, id: UUID): ProjectDetail {
		requireConfigurator(actor)
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
		outbox.enqueue(
			Destination.NOTION,
			OutboundEntityType.PROJECT,
			id,
			OutboundOperation.DELETE,
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
				outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, it, OutboundOperation.UPSERT)
				events.publish(
					KansoEvent.ticket(
						ChangeKind.UPDATED,
						it,
						byId[it]?.teamId,
						null,
						byId[it]?.createdBy,
					),
				)
			}

			destructive -> {
				// Whatever it actually removed, which includes tickets already in the trash:
				// `held` does not see those, and their deletions have to be forgotten too or
				// a countdown goes on running over a row that is gone.
				trash.forgetTickets(tickets.deleteByProject(projectId))
				held.forEach {
					outbox.enqueue(
						Destination.NOTION,
						OutboundEntityType.TICKET,
						it.id,
						OutboundOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(
						KansoEvent.ticket(
							ChangeKind.DELETED,
							it.id,
							it.teamId,
							projectId,
							it.createdBy,
						),
					)
				}
			}

			else -> tickets.setArchivedByProject(projectId, true).forEach {
				outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, it, OutboundOperation.ARCHIVE)
				events.publish(
					KansoEvent.ticket(
						ChangeKind.UPDATED,
						it,
						byId[it]?.teamId,
						projectId,
						byId[it]?.createdBy,
					),
				)
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
		outbox.enqueue(
			Destination.NOTION,
			OutboundEntityType.PROJECT,
			project.id,
			if (archived) OutboundOperation.ARCHIVE else OutboundOperation.UPSERT,
		)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, project.id, project.teamId))
		return ProjectDetail(updated, projects.docIds(project.id), updates.latest(project.id)?.health)
	}

	/**
	 * Archiving, unarchiving, or deleting a project reaches every ticket it holds,
	 * across whichever teams those tickets belong to — the same blast radius as
	 * `TeamService.archive` and `TeamService.delete`, and the same rule applies:
	 * disposition is instance configuration, not daily work, regardless of which
	 * container is being disposed of or which direction the disposition runs.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can change projects")
		}
	}

	private fun validateDates(start: KansoInstant?, end: KansoInstant?) {
		if (start != null && end != null && end.at.isBefore(start.at)) {
			throw BadRequestException("end ${end.at} is before start ${start.at}")
		}
	}

	private fun requireTeamExists(id: UUID) {
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

package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.ProjectHealth
import dev.kanso.domain.User
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.ProjectUpdateRecord
import dev.kanso.repo.ProjectUpdateRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** An update with its person resolved. [author] is null once the account is gone. */
data class ProjectUpdateRow(
	val id: UUID,
	val projectId: UUID,
	val health: ProjectHealth,
	val body: String,
	val author: User?,
	val at: OffsetDateTime,
)

/**
 * How a project is going, posted by a person on a date.
 *
 * A service of its own rather than four more methods on [ProjectService], which is
 * already the longest thing in this package and is about the project's *shape* — its
 * name, its dates, its team, and what happens to its tickets when it goes away. Health is
 * none of that: it is a thing people say about a project, closer to [CommentService] than
 * to anything [ProjectService] does, and it obeys [CommentService]'s permission rule
 * rather than [ProjectService]'s for exactly that reason.
 *
 * [ProjectService] keeps one line of this — `ProjectDetail.health`, read through
 * [ProjectUpdateRepository.latestHealthFor] — because every caller that wants a project
 * wants its health with it, and a second round trip per project page would be the wrong
 * price for a boundary.
 */
@Service
class ProjectUpdateService(
	private val projects: ProjectRepository,
	private val updates: ProjectUpdateRepository,
	private val access: TicketAccess,
	private val activity: ActivityService,
	private val users: UserRepository,
) {

	/** Newest first. Reads are open, like every other GET in this codebase. */
	@Transactional(readOnly = true)
	fun forProject(projectId: UUID, limit: Int = DEFAULT_HISTORY): List<ProjectUpdateRow> =
		decorate(updates.forProject(projectId, limit))

	/**
	 * Post one.
	 *
	 * **Who may.** [TicketAccess]'s team scope, and not [ProjectService]'s
	 * `requireConfigurator`. The two rules already in this package divide on daily work
	 * versus instance configuration: archiving or deleting a project reaches every ticket
	 * it holds across whichever teams they belong to, so it is owner-or-admin, and
	 * `ProjectPermissionTest` pins that. Saying how the work is going reaches nothing —
	 * it adds a row and changes no other — and it is the person doing the work who knows.
	 * Requiring an admin would put the one signal that has to come from the people closest
	 * to the project behind the one role furthest from it. So this takes the rule
	 * [CommentService] takes, for the same reason and via the same service.
	 *
	 * A project with no team is open to anyone signed in, which is
	 * [ProjectRepository.setTeam]'s "the transverse case belongs everywhere" read back:
	 * there is no team to scope the write against, and [TicketAccess] has nothing to
	 * answer about. Refusing outright would leave the transverse projects — the ones most
	 * likely to need an update, since nobody owns them — the only ones that can never have
	 * one.
	 */
	@Transactional
	fun post(actor: User, projectId: UUID, health: ProjectHealth, body: String): ProjectUpdateRow {
		// The project decides which team the write belongs to, so it is read first and
		// nothing is validated until access has answered — the order `CommentService.create`
		// uses, and for the same reason.
		val project = projects.findById(projectId) ?: throw NotFoundException("No project $projectId")
		project.teamId?.let { access.requireTeam(actor, it) }

		val text = body.trim()
		// A health with no sentence under it is a colour nobody can act on: "at risk" alone
		// leaves the reader exactly where they started, and the reason is the whole update.
		if (text.isEmpty()) throw BadRequestException("An update needs a sentence saying why")

		// Read before the insert, so `from` names what the health was rather than what it
		// has just become.
		val previous = updates.latest(projectId)?.health

		val record = updates.insert(
			id = UUID.randomUUID(),
			projectId = projectId,
			health = health,
			body = text,
			authorId = actor.id,
			at = OffsetDateTime.now(),
		)

		// `from`/`to`, the shape `status_changed` already uses, so one reader in the client
		// formats both. Absent `from` on the first update — the mapper omits nulls, and
		// "moved to at risk from nothing" is not a sentence. The body is named by id and
		// never copied: an activity row outlives what it describes, and a feed carrying the
		// sentence would be a second copy to keep in step.
		activity.record(
			ActivityEntity.PROJECT,
			projectId,
			actor.id,
			ActivityKind.HEALTH_POSTED,
			mapOf("updateId" to record.id.toString(), "to" to health.wire, "from" to previous?.wire),
		)

		// Nothing is enqueued for Notion and no `KansoEvent` is published. `SyncEntityType`
		// has no project-update member and the mirror has no column for one, so a job would
		// be a row the worker could only fail on; the event channel is the same story on the
		// client side. Both are additions, not omissions, and neither is this slice's.
		return ProjectUpdateRow(record.id, projectId, health, text, actor, record.at)
	}

	private fun decorate(records: List<ProjectUpdateRecord>): List<ProjectUpdateRow> {
		if (records.isEmpty()) return emptyList()
		// One query for the page's authors rather than one per row: a project's updates are
		// written by the same two or three people.
		val people = users.findAllById(records.mapNotNull { it.authorId }.toSet()).associateBy { it.id }
		return records.map { record ->
			ProjectUpdateRow(
				id = record.id,
				projectId = record.projectId,
				health = record.health,
				body = record.body,
				author = record.authorId?.let { people[it] },
				at = record.at,
			)
		}
	}

	private companion object {
		/**
		 * Enough history to see a trend without paging. A project gets one of these a week
		 * at most, so twenty is roughly two quarters — past which the question stops being
		 * "how is this going" and starts being "what happened", which the activity feed
		 * answers.
		 */
		const val DEFAULT_HISTORY = 20
	}
}

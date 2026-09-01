package dev.kanso.sync.outbound

import dev.kanso.domain.NotionDoc
import dev.kanso.domain.Project
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.DocRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.notion.NotionProps
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Raised when a page this one relates to hasn't reached Notion yet. The job is put
 * back with a short delay instead of failing: the dependency is almost certainly
 * queued right behind it.
 */
class DependencyNotReady(message: String) : RuntimeException(message)

/**
 * Turns a Postgres row into the full set of Notion properties for its mirrored
 * page.
 *
 * Always the *full* set, never a delta. Notion replaces a relation array
 * wholesale rather than merging, so a partial write would silently drop
 * relations — and writing everything is what makes "Kanso wins" true rather than
 * aspirational.
 */
@Component
class NotionMapper(
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val dependencies: DependencyRepository,
) {

	fun teamProperties(team: Team): Map<String, Any?> {
		val memberships = teams.members(team.id)
		val personIds = users.notionPersonIds(memberships.map { it.user.id })
		return buildMap {
			put(NotionProps.NAME, NotionProps.title("${team.name} (${team.key})"))
			put(NotionProps.KANSO_ID, NotionProps.richText(team.id.toString()))
			put(NotionProps.MEMBERS, NotionProps.people(personIds.values))
			// Everyone, including those Notion can't represent as a person.
			put(NotionProps.MEMBERS_TEXT, NotionProps.richText(memberships.joinToString { it.user.displayName }))
			team.parentTeamId?.let { parentId ->
				put(NotionProps.PARENT_TEAM, NotionProps.relation(listOf(requirePage("team", parentId, teams.findById(parentId)?.mirror?.notionPageId))))
			} ?: put(NotionProps.PARENT_TEAM, NotionProps.relation(emptyList()))
		}
	}

	fun projectProperties(project: Project): Map<String, Any?> {
		val leadPersonId = project.leadUserId?.let { users.notionPersonIds(listOf(it)).values.firstOrNull() }
		val leadName = project.leadUserId?.let { users.findById(it)?.displayName }
		return buildMap {
			put(NotionProps.NAME, NotionProps.title(project.name))
			put(NotionProps.KANSO_ID, NotionProps.richText(project.id.toString()))
			put(NotionProps.STATUS, NotionProps.select(project.status.label))
			// Explicit bounds only. `project.start` and `project.end` are null when the
			// bound is derived from the tickets, and that is deliberate: pushing a
			// computed bound would have the poller read it back as a posed one, quietly
			// turning a derivation into a pinned date nobody chose. Same trade as the
			// two-date deviation — an honest round trip over a prettier mirror.
			put(NotionProps.START, NotionProps.date(project.start))
			put(NotionProps.END, NotionProps.date(project.end))
			put(NotionProps.LEAD, NotionProps.people(listOfNotNull(leadPersonId)))
			put(NotionProps.LEAD_TEXT, NotionProps.richText(leadName))
			put(NotionProps.TEAM, NotionProps.relation(teamRelation(project.teamId)))
			put(NotionProps.DOCS, NotionProps.relation(docRelation(projects.docIds(project.id))))
		}
	}

	fun ticketProperties(ticket: Ticket): Map<String, Any?> {
		// Null only for a ticket with no team, which `TicketService` never enqueues a push
		// for — a page with no identifier and no team relation is a row nothing could
		// reconcile. Tolerated rather than required so a job queued by some other path
		// writes a blank identifier instead of throwing inside the worker.
		val team = ticket.teamId?.let(teams::findById)
		val assigneeIds = tickets.assigneeIds(ticket.id)
		val personIds = users.notionPersonIds(assigneeIds)
		val assigneeNames = users.findAllById(assigneeIds).joinToString { it.displayName }
		return buildMap {
			put(NotionProps.NAME, NotionProps.title(ticket.title))
			put(NotionProps.KANSO_ID, NotionProps.richText(ticket.id.toString()))
			put(
				NotionProps.IDENTIFIER,
				NotionProps.richText(team?.let { "${it.key}-${ticket.number}" }),
			)
			put(NotionProps.STATUS, NotionProps.select(ticket.status.label))
			put(NotionProps.PRIORITY, NotionProps.select(ticket.priority.label))
			// An unestimated ticket writes an explicit null, like every absent date does:
			// leaving the key out would let the mirror keep a number the ticket no longer
			// carries, and this map is always the full set of properties.
			put(NotionProps.ESTIMATE, NotionProps.number(ticket.estimate))
			put(NotionProps.DESCRIPTION, NotionProps.richText(ticket.description))
			put(NotionProps.START, NotionProps.date(ticket.start))
			put(NotionProps.DUE, NotionProps.date(ticket.due))
			put(NotionProps.ASSIGNEES, NotionProps.people(personIds.values))
			put(NotionProps.ASSIGNEES_TEXT, NotionProps.richText(assigneeNames))
			put(NotionProps.TEAM, NotionProps.relation(teamRelation(ticket.teamId)))
			put(NotionProps.PROJECT, NotionProps.relation(projectRelation(ticket.projectId)))
			put(NotionProps.DOCS, NotionProps.relation(docRelation(tickets.docIds(ticket.id))))
			// Kanso-authoritative, like every other relation: Notion's self-referencing
			// relation accepts a cycle without complaint, so these arrows are written
			// and never read back.
			put(NotionProps.BLOCKED_BY, NotionProps.relation(predecessorRelation(ticket.id)))
		}
	}

	/** The index row standing in for a referenced page. */
	fun docProperties(doc: NotionDoc): Map<String, Any?> = mapOf(
		NotionProps.NAME to NotionProps.title(doc.title ?: doc.url ?: doc.notionPageId),
		NotionProps.URL to NotionProps.url(doc.url ?: "https://www.notion.so/${doc.notionPageId.replace("-", "")}"),
	)

	private fun teamRelation(teamId: UUID?): List<String> = teamId?.let {
		listOf(requirePage("team", it, teams.findById(it)?.mirror?.notionPageId))
	} ?: emptyList()

	private fun projectRelation(projectId: UUID?): List<String> = projectId?.let {
		listOf(requirePage("project", it, projects.findById(it)?.mirror?.notionPageId))
	} ?: emptyList()

	/**
	 * A predecessor still waiting for its own page defers this job rather than
	 * shortening the array: the dependency graph is acyclic — Postgres refuses a loop
	 * — so the predecessor's push always lands first in the end.
	 */
	private fun predecessorRelation(ticketId: UUID): List<String> =
		dependencies.predecessorPageIds(ticketId).map { (id, pageId) -> requirePage("ticket", id, pageId) }

	private fun docRelation(docIds: List<UUID>): List<String> =
		docs.findAllById(docIds).map { requirePage("doc", it.id, it.mirrorPageId) }

	private fun requirePage(kind: String, id: UUID, pageId: String?): String =
		pageId ?: throw DependencyNotReady("$kind $id has no Notion page yet")
}

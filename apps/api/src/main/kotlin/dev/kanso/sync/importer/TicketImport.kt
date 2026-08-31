package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.ProjectService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionPage
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What one tickets base wrote: its pages, the container project it needed, if any, and the
 * assignees it could not keep — see [TicketImport.resolveAssignees] for why a mapped person
 * is dropped rather than left to fail the whole base.
 */
data class TicketsWritten(val tickets: Int, val projects: Int, val droppedAssignees: Int)

/**
 * A base whose pages are tickets.
 *
 * A ticket needs a project only as far as the reader is concerned; what it cannot do
 * without is a team, because that is where its number comes from. So the project is
 * resolved first and the team follows it: [TicketService] refuses a ticket whose project
 * belongs to another team, and rightly — a ticket in `Roadmap` that says it belongs to
 * `Import` would be filed under a team that cannot see its own project.
 *
 * The arrows between tickets — `Blocked by` — are [TicketLinks]' job, drawn once per base
 * after every tickets base here has been written, not once per page here.
 */
@Service
class TicketImport(
	private val tickets: TicketService,
	private val projects: ProjectService,
	private val teams: TeamService,
	private val origins: ImportOriginRepository,
) {

	fun write(
		actor: User,
		base: PlannedBase,
		fallbackTeam: UUID?,
		links: ImportLinks.Resolved,
		rows: ImportedRows,
		people: Map<String, UUID?>,
	): TicketsWritten {
		var written = 0
		var containers = 0
		var droppedAssignees = 0
		val teamOfProject = mutableMapOf<UUID, UUID?>()
		// Read once per team rather than once per ticket, for the same reason [teamOf]
		// caches its own lookup: a base of four hundred tickets against one team is one
		// membership query, not four hundred.
		val membersOfTeam = mutableMapOf<UUID, Set<UUID>>()

		for (page in base.adoptable) {
			val projectId = links.projectOfTicket[page.id]?.let(rows::project)
				?: base.fallback.projectId
				// The container this base already has, from a page of this run or from a run
				// last month — see [container] for why both are the same lookup.
				?: rows.project(base.base.dataSourceId)
				?: container(base, fallbackTeam, rows).also { containers++ }

			// `!!`: unlike a project, a ticket must have a team, and `perform` already
			// refused before a page was read unless the request or this base supplies one.
			val teamId = teamOf(projectId, teamOfProject) ?: base.fallback.teamId ?: fallbackTeam!!
			val (assigneeIds, dropped) = resolveAssignees(base, page, teamId, people, membersOfTeam)
			droppedAssignees += dropped
			val ticketId = createTicket(actor, teamId, projectId, base, page, assigneeIds)
			rows.put(OriginKind.TICKET, page.id, ticketId)
			origins.record(ImportOrigin(page.id, OriginKind.TICKET, ticketId, base.base.dataSourceId))
			written++
		}
		return TicketsWritten(written, containers, droppedAssignees)
	}

	/**
	 * The project a base gets when its tickets resolve to none of their own.
	 *
	 * Recorded in `notion_import_origin` keyed by the base's *data source id* rather than
	 * by a page: it came from the base, not from any one page in it, and the data source id
	 * is the only stable name it has. Without that, a base imported once and then again
	 * with one new page got a second container named after it, and the reader was left with
	 * two projects called `Tasks` and no way to tell which was which. With it, the lookup
	 * that finds this run's container is the same lookup that finds last month's.
	 *
	 * Created only when a ticket actually needs it, so a base whose every ticket resolves
	 * to a real project creates no project at all.
	 */
	private fun container(base: PlannedBase, fallbackTeam: UUID?, rows: ImportedRows): UUID {
		val project = projects.create(
			name = base.base.name,
			// In progress, not planned: the pages being imported are work somebody has
			// already been doing somewhere else.
			status = ProjectStatus.IN_PROGRESS,
			start = null,
			end = null,
			leadUserId = null,
			teamId = base.fallback.teamId ?: fallbackTeam,
			docIds = emptyList(),
		).project
		rows.put(OriginKind.PROJECT, base.base.dataSourceId, project.id)
		// [ImportOriginRepository.recordContainer] rather than `record`: this base may already
		// have a row here, naming a container somebody has since deleted, and it has to end up
		// naming this one or the run after would create a third.
		origins.recordContainer(
			ImportOrigin(base.base.dataSourceId, OriginKind.PROJECT, project.id, base.base.dataSourceId)
		)
		return project.id
	}

	/** Read once per project rather than once per ticket: a base of four hundred is one base. */
	private fun teamOf(projectId: UUID, cache: MutableMap<UUID, UUID?>): UUID? {
		if (!cache.containsKey(projectId)) cache[projectId] = projects.get(projectId).project.teamId
		return cache[projectId]
	}

	/**
	 * The mapped `ASSIGNEES` column's people, resolved through the request's own
	 * correspondence and narrowed to who can actually hold this ticket.
	 *
	 * A Notion person nobody mapped resolves to nothing through `people[id]` and is
	 * dropped silently by `mapNotNull` — that is an unmapped person, not a failed
	 * assignment, and it is not what [TicketsWritten.droppedAssignees] counts. A person
	 * who *is* mapped but is not a member of this ticket's team is different: `people`
	 * names a real account, `TicketService.create` will not put it on a ticket outside
	 * its team, and leaving the ticket unassigned instead of failing the whole base is
	 * the reason this is checked here rather than left to that refusal — one page must
	 * not roll back the other three hundred ninety-nine.
	 */
	private fun resolveAssignees(
		base: PlannedBase,
		page: NotionPage,
		teamId: UUID,
		people: Map<String, UUID?>,
		membersOfTeam: MutableMap<UUID, Set<UUID>>,
	): Pair<List<UUID>, Int> {
		val resolved = base.reader.people(page, ImportField.ASSIGNEES).mapNotNull { people[it.id] }.distinct()
		val members = membersOfTeam.getOrPut(teamId) { teams.members(teamId).map { it.user.id }.toSet() }
		val (kept, dropped) = resolved.partition { it in members }
		return kept to dropped.size
	}

	private fun createTicket(
		actor: User,
		teamId: UUID,
		projectId: UUID,
		base: PlannedBase,
		page: NotionPage,
		assigneeIds: List<UUID>,
	): UUID =
		tickets.create(
			actor = actor,
			teamId = teamId,
			title = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" },
			description = describe(base.reader, page),
			// A status or priority the mapping did not place inside Kanso's vocabulary is not
			// adopted — the vocabulary is closed in Kotlin and by a CHECK, and "Blocked"
			// becoming "Todo" is better than a seventh status nothing else understands. The
			// default belongs here rather than in the reader: null means "nobody said", and
			// only the thing writing the row gets to decide what to write instead.
			status = base.reader.status(page) ?: TicketStatus.TODO,
			priority = base.reader.priority(page) ?: TicketPriority.NONE,
			start = base.reader.start(page),
			due = base.reader.due(page),
			projectId = projectId,
			assigneeIds = assigneeIds,
			docIds = emptyList(),
		).ticket.id
}

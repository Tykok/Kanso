package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.ConflictException
import dev.kanso.service.ProjectService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/** What one tickets base wrote: its pages, and the container project it needed, if any. */
data class TicketsWritten(val tickets: Int, val projects: Int)

/** What the dependency pass drew, and what it could not. */
data class Dependencies(val created: Int, val dropped: Int)

/**
 * A base whose pages are tickets, and the arrows between them.
 *
 * A ticket needs a project only as far as the reader is concerned; what it cannot do
 * without is a team, because that is where its number comes from. So the project is
 * resolved first and the team follows it: [TicketService] refuses a ticket whose project
 * belongs to another team, and rightly — a ticket in `Roadmap` that says it belongs to
 * `Import` would be filed under a team that cannot see its own project.
 */
@Service
class TicketImport(
	private val tickets: TicketService,
	private val projects: ProjectService,
	private val schedule: ScheduleService,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun write(
		actor: User,
		base: PlannedBase,
		fallbackTeam: UUID,
		links: ImportLinks.Resolved,
		rows: ImportedRows,
	): TicketsWritten {
		var written = 0
		var containers = 0
		val teamOfProject = mutableMapOf<UUID, UUID?>()

		for (page in base.adoptable) {
			val projectId = links.projectOfTicket[page.id]?.let(rows::project)
				?: base.fallback.projectId
				// The container this base already has, from a page of this run or from a run
				// last month — see [container] for why both are the same lookup.
				?: rows.project(base.base.dataSourceId)
				?: container(base, fallbackTeam, rows).also { containers++ }

			val teamId = teamOf(projectId, teamOfProject) ?: base.fallback.teamId ?: fallbackTeam
			val ticketId = createTicket(actor, teamId, projectId, base, page)
			rows.put(OriginKind.TICKET, page.id, ticketId)
			origins.record(ImportOrigin(page.id, OriginKind.TICKET, ticketId, base.base.dataSourceId))
			written++
		}
		return TicketsWritten(written, containers)
	}

	/**
	 * Turns the relations [ImportLinks] resolved into dependencies, once for every base.
	 *
	 * A dependency is exactly what the mapping called a dependency — the column somebody
	 * pointed at `Blocked by` — and not every relation the page happens to hold: a
	 * `Related` column is a link between two pages, not an order to do them in, and turning
	 * one into an arrow put work in a queue nobody asked for.
	 *
	 * Through [ScheduleService.link] rather than the repository, so an imported arrow is
	 * settled by the same engine as a drawn one and a cycle is refused rather than stored.
	 * A refusal drops that one arrow and counts it: a workspace whose relations happen to
	 * form a loop is not a reason to fail an import of four hundred pages, and the count is
	 * what tells the reader it happened.
	 */
	fun settleDependencies(actor: User, links: ImportLinks.Resolved, rows: ImportedRows): Dependencies {
		var created = 0
		var dropped = 0
		for (edge in links.dependencies) {
			val predecessor = rows.ticket(edge.predecessorPageId)
			val successor = rows.ticket(edge.successorPageId)
			if (predecessor == null || successor == null) {
				dropped++
				continue
			}
			try {
				schedule.link(actor, predecessor, successor)
				created++
			} catch (e: ConflictException) {
				log.info("Dropped an imported relation: {}", e.message)
				dropped++
			}
		}
		return Dependencies(created, dropped)
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
	private fun container(base: PlannedBase, fallbackTeam: UUID, rows: ImportedRows): UUID {
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
		origins.record(
			ImportOrigin(base.base.dataSourceId, OriginKind.PROJECT, project.id, base.base.dataSourceId)
		)
		return project.id
	}

	/** Read once per project rather than once per ticket: a base of four hundred is one base. */
	private fun teamOf(projectId: UUID, cache: MutableMap<UUID, UUID?>): UUID? {
		if (!cache.containsKey(projectId)) cache[projectId] = projects.get(projectId).project.teamId
		return cache[projectId]
	}

	private fun createTicket(
		actor: User,
		teamId: UUID,
		projectId: UUID,
		base: PlannedBase,
		page: NotionPage,
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
			// Notion `people` name workspace members, and matching them to Kanso accounts
			// is the mapping `users.notion_person_id` exists for — it points the other way
			// and only for people who have already been linked. Guessing an assignee from a
			// display name is how work lands on the wrong person.
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
}

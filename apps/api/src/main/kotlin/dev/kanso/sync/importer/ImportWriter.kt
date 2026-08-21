package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockKind
import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import dev.kanso.service.ProjectService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The half of the import that writes, and the only half.
 *
 * It is handed pages that are already in memory and never talks to Notion: an import is
 * one transaction, and a transaction that waits on a network call between two inserts
 * holds a connection and a team's counter lock for as long as Notion feels like taking.
 *
 * Everything goes through the same services the interface uses — `ProjectService`,
 * `TicketService`, `DocService` — rather than the repositories underneath them. A ticket
 * created here therefore gets its number from its team's counter, its activity row, and
 * its place in the outbox, exactly like one created by pressing `c`. The outbox part is
 * deliberate and worth being explicit about: the mirror will create *its own* page for
 * each imported ticket in `Kanso · Tickets`. It does not adopt the page the ticket came
 * from — writing to somebody's own database would make Kanso's "Kanso wins" rule overwrite
 * the workspace they just imported, and screen 24 promises nothing in Notion is changed at
 * any step.
 *
 * That is also why every row created from a page is followed by an [ImportOriginRepository.record]
 * for it: the row this instance created and the page it came from are the two ends of the
 * one fact `notion_import_origin` exists to hold, and recording it here, next to the insert
 * it belongs to, is what lets a second import of the same base skip rather than duplicate.
 * The project a `TICKETS` base gets as a container is not recorded — it did not come from
 * a page, so there is nothing to key an origin on.
 */
@Service
class ImportWriter(
	private val projects: ProjectService,
	private val tickets: TicketService,
	private val docs: DocService,
	private val blocks: DocBlockRepository,
	private val schedule: ScheduleService,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun write(actor: User, teamId: UUID, bases: List<PlannedBase>): ImportOutcome {
		val ticketByPage = mutableMapOf<String, UUID>()
		val skipped = mutableListOf<SkippedPage>()
		var ticketCount = 0
		var docCount = 0
		var projectCount = 0
		var folderCount = 0

		for (base in bases) {
			// `base.skippedPages` already excludes pages already imported — a page already
			// in Kanso is reported as already-imported only, never also as skipped, which is
			// the same rule [ImportPlanner.preview] applies from the same property.
			base.skippedPages.forEach { page ->
				val reason = requireNotNull(NotionPageReader.refusal(page)) { "skippedPages only holds refused pages" }
				skipped += SkippedPage(base.base.name, page.id, reason)
			}

			when (base.target) {
				// The `isNotEmpty()` guard is what makes a second import of an unchanged base
				// write nothing at all: without it, a base whose every page already has an
				// origin would still get a fresh, empty container on every press.
				ImportTarget.TICKETS -> if (base.adoptable.isNotEmpty()) {
					val project = createProject(base, teamId)
					projectCount++
					for (page in base.adoptable) {
						val ticketId = createTicket(actor, teamId, project, page)
						ticketByPage[page.id] = ticketId
						origins.record(ImportOrigin(page.id, OriginKind.TICKET, ticketId, base.base.dataSourceId))
						ticketCount++
					}
				}

				ImportTarget.DOCUMENTS -> if (base.adoptable.isNotEmpty()) {
					val folder = docs.createFolder(actor, teamId, null, base.base.name)
					folderCount++
					for (page in base.adoptable) {
						val docId = createDocument(actor, teamId, folder.id, page)
						origins.record(ImportOrigin(page.id, OriginKind.DOC, docId, base.base.dataSourceId))
						docCount++
					}
				}

				ImportTarget.TEAMS, ImportTarget.PROJECTS ->
					throw BadRequestException(
						"Importing a base as ${base.target.wire} is not wired up yet."
					)
			}
		}

		val dependencies = link(actor, bases, ticketByPage)
		// Read off the plan rather than counted alongside the loop above: `PlannedBase`
		// already excludes these pages from `adoptable`, so nothing in the loop ever touches
		// them — the count exists only so the caller is told, not silently left to notice.
		val alreadyImportedCount = bases.sumOf { it.alreadyImported.size }

		log.info(
			"Imported {} ticket(s) and {} document(s) into team {}; {} dependency/ies, {} relation(s) dropped, " +
				"{} page(s) skipped, {} already imported",
			ticketCount, docCount, teamId, dependencies.created, dependencies.dropped, skipped.size, alreadyImportedCount,
		)

		return ImportOutcome(
			started = true,
			tickets = ticketCount,
			docs = docCount,
			projects = projectCount,
			folders = folderCount,
			dependencies = dependencies.created,
			droppedRelations = dependencies.dropped,
			skipped = skipped,
			alreadyImported = alreadyImportedCount,
		)
	}

	/**
	 * In progress, not planned: the pages being imported are work somebody has already
	 * been doing somewhere else, and a project full of half-finished tickets that calls
	 * itself planned is wrong on the one screen that reads project status.
	 */
	private fun createProject(base: PlannedBase, teamId: UUID) = projects.create(
		name = base.base.name,
		status = ProjectStatus.IN_PROGRESS,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun createTicket(actor: User, teamId: UUID, project: Project, page: NotionPage): UUID =
		tickets.create(
			actor = actor,
			teamId = teamId,
			title = requireNotNull(NotionPageReader.title(page)) { "an unadoptable page reached the writer" },
			description = describe(page),
			// A status or priority whose label is outside Kanso's vocabulary is not adopted
			// — the vocabulary is closed in Kotlin and by a CHECK, and "Blocked" becoming
			// "Todo" is better than it becoming a seventh status nothing else understands.
			status = NotionPageReader.status(page) ?: TicketStatus.TODO,
			priority = NotionPageReader.priority(page) ?: TicketPriority.NONE,
			start = NotionPageReader.start(page),
			due = NotionPageReader.due(page),
			projectId = project.id,
			// Notion `people` name workspace members, and matching them to Kanso accounts
			// is the mapping `users.notion_person_id` exists for — it points the other way
			// and only for people who have already been linked. Guessing an assignee from a
			// display name is how work lands on the wrong person.
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

	private fun createDocument(actor: User, teamId: UUID, folderId: UUID, page: NotionPage): UUID {
		val created = docs.createPage(
			actor = actor,
			teamId = teamId,
			folderId = folderId,
			title = requireNotNull(NotionPageReader.title(page)) { "an unadoptable page reached the writer" },
			templateSlug = null,
		)
		// A callout, which is the block screen 07 draws for "read this bit": the properties
		// Kanso has no column for are the part of the page nothing else here explains.
		provenance(page)?.let { blocks.insert(created.page.id, 0, DocBlockKind.CALLOUT, mapOf("text" to it)) }
		return created.page.id
	}

	/**
	 * The description, with the "imported from Notion" section the drawing promises.
	 *
	 * It goes in the description rather than a column of its own because a column of its
	 * own would be a migration, and this is not worth one: the properties are already text
	 * by the time anybody reads them, the description is where a ticket's prose lives, and
	 * the section is what makes them readable rather than merely stored.
	 */
	private fun describe(page: NotionPage): String? {
		val body = NotionPageReader.description(page)
		val section = provenance(page)
		return listOfNotNull(body, section).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
	}

	/** The section itself: the heading, one line per unmapped property, then where it came from. */
	private fun provenance(page: NotionPage): String? {
		val lines = NotionPageReader.unmapped(page).map { (name, value) -> "$name: $value" }
		if (lines.isEmpty() && page.url == null) return null
		return (listOf(SECTION) + lines + listOfNotNull(page.url)).joinToString("\n")
	}

	/**
	 * Turns the resolved relations into dependencies, one at a time.
	 *
	 * Through [ScheduleService.link] rather than the repository, so an imported arrow is
	 * settled by the same engine as a drawn one and a cycle is refused rather than stored.
	 * A refusal drops that one arrow and counts it: a workspace whose relations happen to
	 * form a loop is not a reason to fail an import of four hundred pages, and the count is
	 * what tells the reader it happened.
	 */
	private fun link(actor: User, bases: List<PlannedBase>, ticketByPage: Map<String, UUID>): Linked {
		val plan = ImportPlanner.dependencies(bases)
		var created = 0
		var dropped = plan.dropped

		for (edge in plan.edges) {
			val predecessor = ticketByPage[edge.predecessorPageId]
			val successor = ticketByPage[edge.successorPageId]
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
		return Linked(created, dropped)
	}

	private data class Linked(val created: Int, val dropped: Int)

	private companion object {
		const val SECTION = "Imported from Notion"
	}
}

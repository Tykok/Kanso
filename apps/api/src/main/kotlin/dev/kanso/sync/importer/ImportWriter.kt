package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
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
 * What is left here is the *order*, and the order is the whole point. A relation resolves
 * only once the row it names exists, so teams are written before the projects that name
 * them and projects before the tickets that name them — whatever order the plan happened
 * to list the bases in. Asking the reader to sequence their own plan would be asking them
 * to know that, so the sequence is the server's: this file decides it, and each of the
 * four writers only knows how to write one kind of row.
 *
 * Everything goes through the same services the interface uses — `ProjectService`,
 * `TicketService`, `DocService`, `TeamService` — rather than the repositories underneath
 * them, with one exception: `TicketImport` and `ProjectImport` read `UserRepository`
 * directly to ask whether a mapped account still exists, because neither service exposes
 * that as anything but a private check that throws instead of answering. A ticket created
 * here therefore gets its number from its team's counter, its activity row, and its place
 * in the outbox, exactly like one created by pressing `c`. The outbox part is deliberate
 * and worth being explicit about: the mirror will create *its own* page for each imported
 * ticket in `Kanso · Tickets`. It does not adopt the page the
 * ticket came from — writing to somebody's own database would make Kanso's "Kanso wins"
 * rule overwrite the workspace they just imported, and screen 24 promises nothing in
 * Notion is changed at any step.
 *
 * The [ImportOriginRepository.record] calls are not here but in the four writers, each next
 * to the insert it belongs to: the row a writer created and the page it came from are the
 * two ends of the one fact `notion_import_origin` exists to hold, and a central pass that
 * recorded them afterwards would have to be told again which base each page came from. What
 * this file keeps of the table is the *read* — one query for the whole plan, before the
 * first pass, which is what makes a page imported last month resolve like one imported a
 * second ago.
 */
@Service
class ImportWriter(
	private val teams: TeamImport,
	private val projects: ProjectImport,
	private val tickets: TicketImport,
	private val ticketLinks: TicketLinks,
	private val documents: DocumentImport,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun write(actor: User, fallbackTeam: UUID?, people: Map<String, UUID?>, bases: List<PlannedBase>): ImportOutcome {
		// Filtered to the origins whose entity still exists: the table deliberately carries no
		// foreign key, so the *reader* of the seed is what has to be careful — see
		// [ImportOriginRepository.live].
		val rows = ImportedRows(origins.live(origins.byPageIds(seedKeys(bases))))
		val links = ImportLinks.resolve(bases)

		val teamBases = bases.filter { it.target == ImportTarget.TEAMS }
		var teamCount = 0
		// Two loops over the same bases rather than one: a parent can be listed after its
		// child, and can even live in another teams base of the same plan.
		for (base in teamBases) teamCount += teams.write(actor, base, rows)
		for (base in teamBases) teams.settleParents(actor, base, links, rows)

		var projectCount = 0
		for (base in bases.filter { it.target == ImportTarget.PROJECTS }) {
			projectCount += projects.write(actor, base, fallbackTeam, links, rows, people)
		}

		var ticketCount = 0
		var droppedAssignees = 0
		for (base in bases.filter { it.target == ImportTarget.TICKETS }) {
			val written = tickets.write(actor, base, fallbackTeam, links, rows, people)
			ticketCount += written.tickets
			// A container project is still a project the reader will see on the list.
			projectCount += written.projects
			droppedAssignees += written.droppedAssignees
		}
		// After every tickets base, not inside one: an arrow can cross from one base into
		// another, and half the tickets it points at do not exist yet in the middle of the
		// pass that creates them.
		val dependencies = ticketLinks.settle(actor, links, rows)

		var docCount = 0
		var folderCount = 0
		for (base in bases.filter { it.target == ImportTarget.DOCUMENTS }) {
			val written = documents.write(actor, base, fallbackTeam)
			docCount += written.docs
			folderCount += written.folders
		}

		val skipped = bases.flatMap { base ->
			// `base.skippedPages` already excludes pages already imported — a page already
			// in Kanso is reported as already-imported only, never also as skipped, which is
			// the same rule [ImportPlanner.preview] applies from the same property.
			base.skippedPages.map { page ->
				val reason = requireNotNull(base.reader.refusal(page)) { "skippedPages only holds refused pages" }
				SkippedPage(base.base.name, page.id, reason)
			}
		}
		// Read off the plan rather than counted alongside the passes above: `PlannedBase`
		// already excludes these pages from `adoptable`, so nothing above ever touches
		// them — the count exists only so the caller is told, not silently left to notice.
		val alreadyImportedCount = bases.sumOf { it.alreadyImported.size }

		log.info(
			"Imported {} team(s), {} project(s), {} ticket(s) and {} document(s) into team {}; {} dependency/ies, " +
				"{} relation(s) dropped, {} link disagreement(s), {} assignee(s) dropped, {} page(s) skipped, " +
				"{} already imported",
			teamCount, projectCount, ticketCount, docCount, fallbackTeam, dependencies.created,
			links.droppedRelations + dependencies.dropped, links.conflicts, droppedAssignees, skipped.size,
			alreadyImportedCount,
		)

		return ImportOutcome(
			started = true,
			teams = teamCount,
			tickets = ticketCount,
			docs = docCount,
			projects = projectCount,
			folders = folderCount,
			dependencies = dependencies.created,
			// Relation ends that resolved to nothing are already counted by the resolver,
			// over every relation the mapping named rather than only the dependencies; what
			// the dependency pass adds is the arrows it could not draw.
			droppedRelations = links.droppedRelations + dependencies.dropped,
			skipped = skipped,
			alreadyImported = alreadyImportedCount,
			linkConflicts = links.conflicts,
			droppedAssignees = droppedAssignees,
		)
	}

	/**
	 * Everything `notion_import_origin` might already hold about this plan, in one query.
	 *
	 * Every page of every base, plus every base's own data source id: that is the key a
	 * tickets base's container project is recorded under, and looking it up here rather
	 * than once per base keeps an import of four hundred pages at one round trip for the
	 * lot — see [TicketImport] for why the container is keyed by the base at all.
	 */
	private fun seedKeys(bases: List<PlannedBase>): List<String> =
		bases.flatMap { base -> base.pages.map { it.id } + base.base.dataSourceId }
}

/**
 * Page id → the row it became, across the four passes.
 *
 * Seeded from `notion_import_origin` before the first pass, so a relation pointing at a
 * page imported *last month* resolves exactly like one imported a second ago. That is the
 * whole reason the table exists: without the seed, a second import of a base with one new
 * page would leave that page's every relation unresolved and land it in a fallback.
 *
 * Every id in the seed is one the writers hand to a service as if they had just created the
 * row themselves, so the seed has to hold only rows that exist — [ImportOriginRepository.live]
 * is what makes that true, and every `?:` chain reading this class depends on it.
 */
class ImportedRows(seed: Map<String, ImportOrigin> = emptyMap()) {

	private val byKind: Map<OriginKind, MutableMap<String, UUID>> =
		OriginKind.entries.associateWith { mutableMapOf() }

	init {
		seed.forEach { (pageId, origin) -> byKind.getValue(origin.kind)[pageId] = origin.entityId }
	}

	fun put(kind: OriginKind, pageId: String, id: UUID) {
		byKind.getValue(kind)[pageId] = id
	}

	fun team(pageId: String): UUID? = byKind.getValue(OriginKind.TEAM)[pageId]
	fun project(pageId: String): UUID? = byKind.getValue(OriginKind.PROJECT)[pageId]
	fun ticket(pageId: String): UUID? = byKind.getValue(OriginKind.TICKET)[pageId]
}

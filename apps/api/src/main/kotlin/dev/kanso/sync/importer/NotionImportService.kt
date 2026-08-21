package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionRateLimited
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Screen 24: what the workspace holds, what would happen, and then what happens.
 *
 * Three steps and one rule between them — **nothing is written before the third**. The
 * shape that keeps that true is the split in this file: every Notion read goes through
 * [NotionDiscovery] and lands in memory first, and only [perform] is given the services
 * that write. A preview cannot write a row because it is never handed anything that can.
 */
@Service
class NotionImportService(
	private val discovery: NotionDiscovery,
	private val meta: NotionMetaRepository,
	private val originRows: ImportOriginRepository,
	private val access: TicketAccess,
	private val writer: ImportWriter,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Step 1. Reads. */
	fun sources(): ImportSources {
		val excluded = tx.execute { mirrorIds() }.orEmpty()
		return try {
			runBlocking {
				val found = discovery.search(excluded)
				found.unavailable?.let { return@runBlocking ImportSources(false, it, emptyList()) }
				ImportSources(
					available = true,
					reason = null,
					sources = found.bases.map { base ->
						val counted = discovery.count(base.dataSourceId)
						DiscoveredSource(
							id = base.dataSourceId,
							databaseId = base.databaseId,
							name = base.name,
							pages = counted.pages,
							pagesExact = counted.exact,
						)
					},
				)
			}
		} catch (e: NotionRateLimited) {
			// Not a 500: the workspace is readable, just not this second, and "try again in
			// twelve seconds" is a sentence somebody can act on.
			unavailable("Notion is rate-limiting this integration. Try again in ${e.retryAfter.toSeconds()}s.")
		} catch (e: NotionApiException) {
			unavailable("Notion refused the request: ${e.message}")
		}
	}

	/**
	 * Step 2 → 3, and the screen's central promise: this answers what *would* happen and
	 * writes nothing.
	 *
	 * Nothing in here could write if it tried — the reading is [NotionDiscovery]'s and the
	 * arithmetic is [ImportPlanner]'s, and neither has ever been handed a repository. The
	 * one seam that could break the promise is [perform] reading the workspace a second
	 * time and getting a different answer; that is the reason both go through [read].
	 */
	fun preview(plan: List<ImportPlanEntry>): ImportPreview = ImportPlanner.preview(read(plan))

	/**
	 * Step 3. The first thing that writes.
	 *
	 * The team is checked first, before a single page is read: an import is a write and a
	 * large one, and the refusal belongs in front of the work rather than after four
	 * hundred inserts have to be rolled back. [teamId] is also *where the team comes from*
	 * when nothing else answers — `architecture.md` says a Notion-authored page can supply
	 * neither a team nor a per-team number, and this is the request where somebody is
	 * present to answer the first, which lets `TicketService` answer the second from the
	 * team's own counter. A base of projects that resolved a team of its own uses that one,
	 * and its tickets follow their project; this is the answer for everything left over.
	 */
	fun perform(actor: User, teamId: UUID, plan: List<ImportPlanEntry>): ImportOutcome {
		// Directly, not inside `tx`: `TicketAccess.requireTeam` is transactional itself, and
		// a refusal raised inside a template here would also mark the caller's transaction
		// rollback-only on its way out — a 403 that poisons whatever else the request was in.
		access.requireTeam(actor, teamId)
		return writer.write(actor, teamId, read(plan))
	}

	/**
	 * The plan, resolved against the workspace as it is now.
	 *
	 * A row naming a base the workspace no longer holds is dropped rather than refused: the
	 * search happens again between the steps, a base can be deleted in Notion in between,
	 * and failing the whole import over one stale id would be worse than importing the rest
	 * of what somebody asked for. `import-map.ts` drops the same row on its own side, and
	 * for the same reason.
	 */
	private fun read(plan: List<ImportPlanEntry>): List<PlannedBase> {
		if (plan.isEmpty()) return emptyList()
		val excluded = tx.execute { mirrorIds() }.orEmpty()

		val resolved = runBlocking {
			val found = discovery.search(excluded)
			found.unavailable?.let { throw BadRequestException(it) }
			val byId = found.bases.associateBy { it.dataSourceId }

			plan.distinctBy { it.sourceId }.mapNotNull { entry ->
				val base = byId[entry.sourceId]
				if (base == null) {
					log.info("Ignoring plan row for {}: the workspace no longer holds it", entry.sourceId)
					null
				} else {
					ResolvedBase(base, entry, discovery.pages(base.dataSourceId))
				}
			}
		}

		// One query for the whole plan, not one per base and not one per page: a base of
		// four hundred pages must not cost four hundred round trips inside the transaction
		// that holds a team's ticket counter.
		val allPageIds = resolved.flatMap { it.pages }.map { it.id }
		val existing = tx.execute { originRows.byPageIds(allPageIds) }.orEmpty()

		return resolved.map { r ->
			val already = r.pages.mapNotNullTo(mutableSetOf()) { page -> page.id.takeIf { it in existing } }
			PlannedBase(r.base, r.entry.target, r.pages, alreadyImported = already, mapping = r.entry.mapping)
		}
	}

	private data class ResolvedBase(val base: WorkspaceBase, val entry: ImportPlanEntry, val pages: List<NotionPage>)

	/**
	 * Kanso's own four databases, by both ids.
	 *
	 * The mirror discovers these at bootstrap and writes to them continuously. Offering
	 * `Kanso · Tickets` as something to import would duplicate every ticket in the
	 * instance, and the second import would duplicate the duplicates.
	 */
	private fun mirrorIds(): Set<String> =
		meta.findAll().flatMapTo(mutableSetOf()) { listOf(it.databaseId, it.dataSourceId) }

	private fun unavailable(reason: String): ImportSources {
		log.info("Notion import discovery unavailable: {}", reason)
		return ImportSources(available = false, reason = reason, sources = emptyList())
	}
}

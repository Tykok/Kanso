package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionApiException
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

	/** Step 2 → 3. Reads, and is the screen's promise that step 3 has something true to show. */
	fun preview(plan: List<ImportPlanEntry>): ImportPreview = TODO("preview")

	/** Step 3. The first thing that writes. */
	fun perform(actor: User, teamId: UUID, plan: List<ImportPlanEntry>): ImportOutcome = TODO("perform")

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

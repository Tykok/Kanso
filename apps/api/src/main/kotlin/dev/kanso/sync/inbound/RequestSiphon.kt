package dev.kanso.sync.inbound

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.repo.RequestBase
import dev.kanso.service.TicketService
import dev.kanso.sync.importer.ColumnMapping
import dev.kanso.sync.importer.MappedPageReader
import dev.kanso.sync.importer.describe
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * One page of a Notion requests base, become one untriaged ticket.
 *
 * This is the whole of the new code for the feature, and it is deliberately this small.
 * The queue already exists and is defined by the *absence* of a `triage_decisions` row, so
 * there is no flag to set: a ticket filed into a team is in that team's queue by
 * construction — `TriageRepository.queue`'s header says a flag "would need a writer for
 * every route that can create a ticket... and one that forgot would drop work on the
 * floor". This is that new route, and it forgets nothing because there is nothing to
 * remember. Duplicate detection is `TriageService.similar` over `pg_trgm` and needs the
 * ticket to exist and nothing else.
 *
 * **The direction is one-way, and by omission rather than by rule.** Nothing here writes to
 * Notion, and more importantly nothing here writes the page id onto the ticket:
 * `tickets.notion_page_id` stays null, so when the mirror pushes this ticket it creates a
 * fresh page in `Kanso · Tickets` — `NotionOutboundHandler.plan` has no id to address the
 * requests base with, and no code path that could acquire one. The ledger row lives in
 * `notion_import_origin`, which no push planner reads. `V15`'s header wrote that argument
 * first; `V37`'s repeats it for this feature.
 *
 * **Adoption is one-shot.** A page in the ledger is skipped, which makes the same page
 * polled twice produce one ticket, and makes an edit in Notion after adoption produce
 * nothing at all. That second half is the one that matters: without it, editing a request
 * whose ticket somebody had already triaged and closed would either resurrect it or file a
 * second copy, and the poller re-reads every page inside the overlap window on purpose.
 * Note what is *not* doing this work — the echo and "Kanso wins" guards compare timestamps
 * against a last push, and a base Kanso never pushes to has no timestamps to compare.
 */
@Service
class RequestSiphon(
	private val tickets: TicketService,
	private val origins: ImportOriginRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * The reader, with nothing mapped, and that is the point.
	 *
	 * [MappedPageReader] finds the title by *type* — the one property Notion requires of
	 * every database — so a base whose title column is called `Demande` or `Sujet` works
	 * with nothing configured. Every other column is unclaimed, so all of it lands in the
	 * preserved "Imported from Notion" section: a salesperson's `Client`, `Urgence` and
	 * `Contexte` survive as prose instead of being dropped, without anyone having answered
	 * a mapping screen this poller has no human in front of.
	 *
	 * One instance, not one per page: a mapping this empty has nothing per-page in it.
	 */
	private val reader = MappedPageReader(ColumnMapping())

	/**
	 * Adopts [page] into [base]'s team, or explains in the log why it did not.
	 *
	 * Returns the new ticket's id, or null when nothing was written — which is the ordinary
	 * outcome, since every page stays in the base and is re-read on every poll.
	 */
	fun adopt(base: RequestBase, page: NotionPage): UUID? {
		// First, and cheap: the ordinary case is a page adopted weeks ago. `record` below is
		// a plain insert, so the primary key is still the guarantee under a concurrent poll
		// — this check is what keeps the normal path a skip rather than a caught violation.
		if (origins.byPageIds(listOf(page.id)).isNotEmpty()) return null

		// Archived, or with no title to be named from. The same two refusals the import
		// makes, in the same words, because they are the same two facts about a page.
		reader.refusal(page)?.let {
			log.debug("Not siphoning Notion page {}: {}", page.id, it)
			return null
		}
		val title = reader.title(page) ?: return null

		val ticket = tickets.create(
			// Nobody. A requests base is written by people who do not have Kanso open, which
			// is the entire premise — see `TicketService.create`.
			actor = null,
			teamId = base.teamId,
			title = title,
			// The body plus every column Kanso was told nothing about, through the section
			// the import already builds. Shared rather than copied: `ImportedText`'s own
			// comment refuses a second answer to "what does the section look like".
			description = describe(reader, page),
			// Kanso's defaults, and read off the page on purpose. A request arrives untriaged
			// — that is what the queue is for — so a `Status` or a `Priority` column in
			// somebody else's base is not adopted: it would let whoever is loudest in Notion
			// set the queue's ordering, and it would be Notion deciding a value out of a
			// closed vocabulary Kanso owns. The columns are not lost, they are in the
			// description above. Dates and an estimate are refused for the same reason
			// turned round: a requester is not the person who can say when this is due.
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket

		origins.record(ImportOrigin(page.id, OriginKind.TICKET, ticket.id, base.dataSourceId))
		log.info("Siphoned Notion request page {} into ticket {}", page.id, ticket.id)
		return ticket.id
	}
}

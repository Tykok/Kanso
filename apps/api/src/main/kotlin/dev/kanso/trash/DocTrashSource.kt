package dev.kanso.trash

import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocFolderRepository
import dev.kanso.docs.DocPage
import dev.kanso.docs.DocPageRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A page written here — `doc_pages`, not `notion_docs`.
 *
 * Thin for the reason [TicketTrashSource] is thin: the three exits go through
 * [DocService], where the `TicketAccess` rule for a page's team already lives, so this
 * file adds no second answer to "who may throw a document away". What is its own is the
 * read — the title somebody recognises, the folder a restore names, and what the page
 * holds.
 *
 * No [archived]: `doc_pages` has no such column and the interface's default says why.
 */
@Component
class DocTrashSource(
	private val documents: DocService,
	private val rows: DocPageRepository,
	private val blocks: DocBlockRepository,
	private val folders: DocFolderRepository,
	private val teams: TeamRepository,
) : TrashSource {

	override val kind = TrashKind.DOC

	override fun describe(ids: Collection<UUID>): List<TrashItem> = itemsOf(rows.findTrashed(ids))

	override fun restore(actor: User, id: UUID) = documents.restorePage(actor, id)

	override fun purge(actor: User?, id: UUID) = documents.purgePage(actor, id)

	/** One query per relation for the whole set rather than three per row. */
	private fun itemsOf(found: List<DocPage>): List<TrashItem> {
		if (found.isEmpty()) return emptyList()
		val ids = found.map { it.id }
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associateBy { it.id }
		// `findById` per folder and not one tree read: a page names at most one folder, the
		// trash holds tens of rows, and asking for a whole team's tree to print one name
		// would be the more expensive of the two.
		val foldersById = found.mapNotNull { it.folderId }.toSet()
			.mapNotNull(folders::findById).associateBy { it.id }
		val blockCounts = blocks.blockCountsFor(ids)
		val ticketCounts = blocks.ticketCountsFor(ids)

		return found.map { page ->
			TrashItem(
				kind = kind,
				id = page.id,
				// The title, and nothing prepended to it: a page has no identifier anybody
				// says out loud, which is exactly why the ticket's row carries one and this
				// one does not.
				label = page.title,
				// The folder it was filed in, its team otherwise. A page always has a team,
				// so "Restore" never has to say "somewhere".
				parent = page.folderId?.let(foldersById::get)?.let { TrashParent("folder", it.id, it.name) }
					?: keys[page.teamId]?.let { TrashParent("team", it.id, it.name) },
				holds = holdingsOf(blockCounts[page.id] ?: 0, ticketCounts[page.id] ?: 0),
			)
		}
	}

	/**
	 * The pair the whole slice was waiting for, and the reason this source is the
	 * interesting one.
	 *
	 * `BLOCKS` cascades and `MENTIONED_TICKETS` does not, and sending both is what makes the
	 * drawing's sentence — "les tickets n'ont pas été supprimés, seul le renvoi disparaît" —
	 * a fact the pane reads rather than a sentence somebody typed into a component. Both
	 * halves are needed for it to say anything: the rider is only true in contrast to
	 * something that *does* go.
	 *
	 * A count of zero is omitted rather than sent as a zero. "Held 0 blocks" is a sentence
	 * about nothing, and `copy.ts` builds its paragraph out of whatever it is given.
	 */
	private fun holdingsOf(blockCount: Int, ticketCount: Int): List<TrashHolding> = buildList {
		if (blockCount > 0) add(TrashHolding(TrashHoldingKind.BLOCKS, blockCount, cascades = true))
		if (ticketCount > 0) {
			add(TrashHolding(TrashHoldingKind.MENTIONED_TICKETS, ticketCount, cascades = false))
		}
	}
}

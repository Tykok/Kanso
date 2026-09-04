package dev.kanso.docs

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.TicketRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The blocks of a page, and the two gestures screen 25 names inside a document:
 * `#` mentions a ticket, `c` creates one already attached to the page.
 *
 * Every write scopes through the *page's* team and then stamps the page — who touched
 * it and when — because that pair is what the footer prints and nothing about a block
 * row can answer it once the block is gone.
 *
 * `KAN-25` added the other two things every write here does: it asks [locks] whether
 * somebody else is holding the block, and it announces itself on the realtime bus. Both
 * are one line at each call site rather than a wrapper, because which of them applies is
 * not uniform — a reorder is guarded against the whole page and an insert against
 * nothing, and the argument for each asymmetry is at the line that makes it.
 */
@Service
class DocBlockService(
	private val blocks: DocBlockRepository,
	private val pages: DocPageRepository,
	private val tickets: TicketService,
	private val ticketRows: TicketRepository,
	private val access: TicketAccess,
	private val locks: DocBlockLockService,
	private val events: EventPublisher,
) {

	/**
	 * Appends, or inserts straight after [afterBlockId] — which is where the `/` menu
	 * puts a block: the one being typed in is the anchor.
	 */
	@Transactional
	fun addBlock(
		actor: User,
		pageId: UUID,
		kind: DocBlockKind,
		content: Map<String, Any?>,
		afterBlockId: UUID?,
	): DocBlock {
		val page = requirePage(actor, pageId)
		requireContentFor(kind, content)
		// No lock check, and it is the one write here that has none. Adding a paragraph is
		// what writing a document *is*, so refusing it while a colleague holds a block
		// somewhere on the page would make two people writing at once — the case this
		// ticket exists to allow — impossible. It is also the write that cannot lose
		// anything: `V9` made `position` dense but *not unique* and `findByPage` breaks a
		// tie on `id`, so two inserts racing produce both blocks in an arbitrary order
		// between them rather than one of them vanishing.
		val id = insertAt(pageId, afterBlockId, kind, content)
		pages.touch(page.id, actor.id)
		events.publish(KansoEvent.doc(ChangeKind.CREATED, page.id, page.teamId, id))
		return requireNotNull(blocks.findById(id))
	}

	@Transactional
	fun updateBlock(actor: User, id: UUID, content: Map<String, Any?>): DocBlock {
		val block = blocks.findById(id) ?: throw NotFoundException("No block $id")
		val page = requirePage(actor, block.pageId)
		// The overwrite the ticket names first: `content` is written whole, so the second
		// commit takes the whole paragraph. This is where that stops being silent.
		locks.requireWritable(actor, id)
		requireContentFor(block.kind, content)
		blocks.updateContent(id, content)
		pages.touch(page.id, actor.id)
		events.publish(KansoEvent.doc(ChangeKind.UPDATED, page.id, page.teamId, id))
		return requireNotNull(blocks.findById(id))
	}

	/** Where the drag handle lands: [toIndex] is the block's new place in the page. */
	@Transactional
	fun moveBlock(actor: User, id: UUID, toIndex: Int): List<DocBlock> {
		val block = blocks.findById(id) ?: throw NotFoundException("No block $id")
		val page = requirePage(actor, block.pageId)
		// The whole page, not this block — `requirePageWritable` argues why a reorder is
		// the one gesture that takes everybody's claim into account.
		locks.requirePageWritable(actor, block.pageId)
		val order = orderOf(block.pageId).toMutableList()
		order.remove(id)
		order.add(toIndex.coerceIn(0, order.size), id)
		blocks.setOrder(order)
		pages.touch(page.id, actor.id)
		// No `blockId`: every position on the page moved, so naming one of them would tell
		// a receiver that the others are unchanged. The page is the unit of this change.
		events.publish(KansoEvent.doc(ChangeKind.UPDATED, page.id, page.teamId))
		return blocks.findByPage(block.pageId)
	}

	@Transactional
	fun deleteBlock(actor: User, id: UUID) {
		val block = blocks.findById(id) ?: throw NotFoundException("No block $id")
		val page = requirePage(actor, block.pageId)
		// Deleting a paragraph somebody is typing in loses more than overwriting it does.
		// The lock row goes with the block — `V40`'s `ON DELETE CASCADE` — so there is
		// nothing to release once this is allowed.
		locks.requireWritable(actor, id)
		blocks.delete(id)
		// Dense again straight away, so `position` is always 0..n-1 and no reader has to
		// know whether the page has ever had a block removed from the middle of it.
		blocks.setOrder(orderOf(block.pageId))
		pages.touch(page.id, actor.id)
		events.publish(KansoEvent.doc(ChangeKind.DELETED, page.id, page.teamId, id))
	}

	/**
	 * `#` — mentions an existing ticket.
	 *
	 * A block of its own rather than a run of text, because what makes this worth having
	 * is that the reference renders the ticket's *live* status: a document that has to be
	 * re-read to still be true is the thing Kanso exists not to be.
	 */
	@Transactional
	fun linkTicket(actor: User, pageId: UUID, ticketId: UUID, afterBlockId: UUID?): DocBlock {
		val page = requirePage(actor, pageId)
		ticketRows.findById(ticketId) ?: throw BadRequestException("No ticket $ticketId")
		val id = insertAt(pageId, afterBlockId, DocBlockKind.TICKET_LINK, emptyMap())
		blocks.setTickets(id, listOf(ticketId))
		pages.touch(page.id, actor.id)
		events.publish(KansoEvent.doc(ChangeKind.CREATED, page.id, page.teamId, id))
		return requireNotNull(blocks.findById(id))
	}

	/**
	 * `c` inside a document, and deliberately **not** the generic composer.
	 *
	 * The composer asks which team, which project, which priority. Here the page has
	 * already answered all of that: a ticket raised while writing is a ticket about what
	 * is being written, in that page's team. So it takes a title and nothing else — the
	 * page receives a reference block, the ticket keeps the link, and both land in one
	 * transaction, because a ticket created and then not linked is the exact failure this
	 * gesture exists to prevent.
	 */
	@Transactional
	fun createLinkedTicket(actor: User, pageId: UUID, title: String): LinkedTicket {
		val page = requirePage(actor, pageId)
		val ticket = tickets.create(
			actor = actor,
			teamId = page.teamId,
			title = title.trim().ifEmpty { throw BadRequestException("A ticket needs a title") },
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		return LinkedTicket(ticket, linkTicket(actor, pageId, ticket.ticket.id, null))
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * `findLive`, so a page in the trash takes no writes.
	 *
	 * The screen cannot reach one — `DocService.page` is a 404 for it — but a client holding
	 * the id from before the delete can, and a block written into a document that is
	 * counting down would be written straight into the sweep.
	 */
	private fun requirePage(actor: User, pageId: UUID): DocPage {
		val page = pages.findLive(pageId) ?: throw NotFoundException("No document $pageId")
		access.requireTeam(actor, page.teamId)
		return page
	}

	private fun orderOf(pageId: UUID): List<UUID> = blocks.findByPage(pageId).map { it.id }

	/**
	 * Parks the new block past the end, then rewrites the whole page's order with it in
	 * the right place.
	 *
	 * Two statements rather than a `position + 1` shift because the shift is the one
	 * thing that would need arithmetic SQL here, and because parking at `n` — the one
	 * position a dense 0..n-1 page does not hold — means the intermediate state is never
	 * ambiguous. `position` carries no unique constraint (see `V9`), so nothing about
	 * this is a two-phase write.
	 */
	private fun insertAt(
		pageId: UUID,
		afterBlockId: UUID?,
		kind: DocBlockKind,
		content: Map<String, Any?>,
	): UUID {
		val existing = orderOf(pageId)
		val index = when (afterBlockId) {
			null -> existing.size
			else -> existing.indexOf(afterBlockId).let {
				if (it < 0) throw BadRequestException("Block $afterBlockId is not in this document")
				it + 1
			}
		}
		val id = blocks.insert(pageId, existing.size, kind, content)
		blocks.setOrder(existing.toMutableList().apply { add(index, id) })
		return id
	}
}

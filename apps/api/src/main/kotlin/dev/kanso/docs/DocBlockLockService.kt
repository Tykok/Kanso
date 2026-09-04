package dev.kanso.docs

import dev.kanso.config.KansoProperties
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.service.BlockLockedException
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * `KAN-25`'s block lock: who may type into a paragraph, and what the person who may not
 * is told.
 *
 * Its own service rather than four more methods on [DocBlockService], because it answers a
 * different question. That one is about what a document *is*; this is about who is holding
 * a piece of it at this instant, which is a fact with an expiry and no history. Keeping
 * them apart is also what keeps both files re-readable in one sitting.
 *
 * **Nothing here calls `DocPageRepository.touch`.** Taking, renewing and releasing a lock
 * write one row in `doc_block_locks` and move neither `doc_pages.updated_at` nor
 * `edited_by`. `V38` measured what happens when a bookkeeping write is mistaken for an
 * edit, and resting a caret in a paragraph is exactly that write: it would float the page
 * to the top of screen 22's "recently changed" list for being *read*. `V40`'s header
 * carries the argument, and `V9` is why it is a rule about call sites here rather than an
 * ignore-list on a trigger.
 */
@Service
class DocBlockLockService(
	private val locks: DocBlockLockRepository,
	private val blocks: DocBlockRepository,
	private val pages: DocPageRepository,
	private val access: TicketAccess,
	private val events: EventPublisher,
	private val props: KansoProperties,
) {

	/**
	 * Takes the block, or refuses with the name of whoever has it.
	 *
	 * Also the renewal: the client calls this on a timer while the caret is in the block,
	 * and it is the same statement in the repository for the reason argued there. What
	 * differs is only whether anybody else needs to be told — see [announce].
	 */
	@Transactional
	fun take(actor: User, blockId: UUID): DocBlockLock {
		val page = requireWritablePage(actor, blockId)

		// Read before write, and it is not a check-then-act: the write below re-decides the
		// same question atomically and this is only used to answer "was the caller already
		// holding it", which is what keeps a renewal from broadcasting.
		val before = locks.liveFor(blockId)

		val granted = locks.take(blockId, actor.id, props.docs.lockTtl) ?: throw refusal(blockId)

		if (before?.userId != actor.id) announce(page, blockId)
		return granted
	}

	/**
	 * Lets the block go.
	 *
	 * Idempotent and quiet about a lock the caller no longer holds: a blur, a page unload
	 * and a lapsed claim can all arrive in any order, and the repository's `user_id` guard
	 * is what stops a late one freeing somebody else's block. Announced only when a row
	 * actually went, so the three of them do not each cost every reader a refetch.
	 */
	@Transactional
	fun release(actor: User, blockId: UUID) {
		val page = requireWritablePage(actor, blockId)
		if (locks.release(blockId, actor.id)) announce(page, blockId)
	}

	/**
	 * The guard every block write goes through, and it refuses in exactly one case:
	 * **a live lock belonging to somebody else.**
	 *
	 * Not "no lock, no write". A block nobody has claimed stays writable, which is what
	 * keeps this ticket from breaking every caller that is not a browser — the MCP tools,
	 * an API token's script, the Notion importer — none of which has a caret to hold a
	 * lock with and none of which is the collision this feature was built for. Requiring a
	 * lock would have made a soft advisory into a hard protocol, and made every one of
	 * those callers take and release one for a single write they make alone.
	 */
	fun requireWritable(actor: User, blockId: UUID) {
		val held = locks.liveFor(blockId) ?: return
		if (held.userId == actor.id) return
		throw refusal(blockId)
	}

	/**
	 * The guard a **reorder** goes through, and it is deliberately wider than
	 * [requireWritable]: no block on the page may be held by anybody else.
	 *
	 * Because `moveBlock` is not a write to one block. `DocBlockRepository.setOrder`
	 * rewrites `position` for *every* block on the page, from an order the mover read a
	 * moment earlier — which is the second half of the ticket's own diagnosis, "`position
	 * INT` + `moveBlock(toIndex)` = last write wins". Two reorders racing genuinely undo
	 * each other, and a reorder racing an edit moves the paragraph out from under the
	 * caret that is in it.
	 *
	 * The cost is that nobody reorders a page while a colleague has a caret down anywhere
	 * on it, and that is affordable in a way the same rule on [DocBlockService.addBlock]
	 * would not be: a reorder is a rare, deliberate press of an arrow button, while adding
	 * a paragraph is what writing *is*. So this is the one gesture that takes the whole
	 * page's word for it.
	 */
	fun requirePageWritable(actor: User, pageId: UUID) {
		val held = locks.liveForPage(pageId).firstOrNull { it.userId != actor.id } ?: return
		throw BlockLockedException(holder = held.displayName, freesAt = held.expiresAt)
	}

	/** Every live lock on the page, for the one read that draws them all. */
	@Transactional(readOnly = true)
	fun holdersOn(pageId: UUID): List<DocBlockLockHolder> = locks.liveForPage(pageId)

	// --- helpers -------------------------------------------------------------

	/**
	 * The refusal, read back from the database rather than from whatever was in hand.
	 *
	 * Re-read because the interesting case is a race: the caller lost the block between
	 * reading it and asking for it, so the holder worth naming is the one there *now*. If
	 * the row has gone in the meantime the claim lapsed under us — rare, and honest to
	 * report as an empty-handed refusal rather than to retry in a loop, since the client's
	 * next keystroke asks again anyway.
	 */
	private fun refusal(blockId: UUID): BlockLockedException {
		val held = locks.liveFor(blockId)
		val name = held?.userId?.let(locks::holderName) ?: "Somebody else"
		return BlockLockedException(
			holder = name,
			freesAt = held?.expiresAt ?: java.time.OffsetDateTime.now().plus(props.docs.lockTtl),
		)
	}

	/**
	 * Tells the page's other readers that a block changed hands.
	 *
	 * A `docs` event and not a lock-specific one: `lockedBy` is part of a block's shape in
	 * `GET /api/docs/pages/{id}`, so "the lock moved" and "the text moved" are the same
	 * news to a receiver — refetch this page — and a second entity would have been a
	 * vocabulary for a distinction nobody acts on.
	 */
	private fun announce(page: DocPage, blockId: UUID) {
		events.publish(KansoEvent.doc(ChangeKind.UPDATED, page.id, page.teamId, blockId))
	}

	private fun requireWritablePage(actor: User, blockId: UUID): DocPage {
		val block = blocks.findById(blockId) ?: throw NotFoundException("No block $blockId")
		val page = pages.findLive(block.pageId) ?: throw NotFoundException("No document ${block.pageId}")
		access.requireTeam(actor, page.teamId)
		return page
	}
}

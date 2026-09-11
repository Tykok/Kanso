package dev.kanso.docs

import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.TeamRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Folders, pages and templates. Blocks are [DocBlockService]'s.
 *
 * Reads are open and writes are scoped — `architecture.md`'s rule, with no exception
 * for a new endpoint: every `GET` below takes no actor, and every write starts at
 * [TicketAccess.requireTeam]. A page's team is the unit of that scoping because it is
 * the only unit `TicketAccess` knows how to answer about.
 *
 * Nothing here writes to `notion_docs`. A `doc_page` is a page written in Kanso; a
 * `notion_docs` row indexes a page written in Notion so a Notion relation has
 * something inside its own data source to point at. `DocServiceTest` asserts the
 * index row count does not move when a page is written, because the day the two are
 * conflated is the day the mirror's semantics stop holding.
 */
@Service
class DocService(
	private val folders: DocFolderRepository,
	private val pages: DocPageRepository,
	private val blocks: DocBlockRepository,
	private val templates: DocTemplateRepository,
	private val teams: TeamRepository,
	private val tickets: TicketService,
	private val access: TicketAccess,
	private val trash: TrashRepository,
	private val locks: DocBlockLockRepository,
	private val events: EventPublisher,
) {

	// --- folders -------------------------------------------------------------

	/**
	 * The tree, without the branches somebody threw away.
	 *
	 * The pruning is a branch and not a row, which is the folder delete's whole shape:
	 * deleting a folder takes its sub-folders with it, so a sub-folder of a deleted folder
	 * is on its way out too and showing it — at the root, or under a parent that is no
	 * longer in this list — would be drawing half a delete.
	 */
	@Transactional(readOnly = true)
	fun folders(teamId: UUID?): List<DocFolder> {
		val all = folders.findByTeam(teamId)
		val doomed = doomedFolderIds { all }
		return all.filterNot { it.id in doomed }
	}

	@Transactional
	fun createFolder(actor: User, teamId: UUID, parentId: UUID?, name: String): DocFolder {
		access.requireTeam(actor, teamId)
		requireTeamExists(teamId)
		parentId?.let { requireSameTeam(it, teamId) }
		return folders.insert(teamId, parentId, requireName(name))
	}

	/**
	 * `null` means "leave alone"; `"parentId"` in [unset] moves the folder to the root.
	 *
	 * The `unset` convention rather than a bare nullable, and the reason is not symmetry:
	 * a bare nullable made *renaming* a nested folder un-nest it, because a client sending
	 * only a name sends no parent and `null` had to mean something.
	 */
	@Transactional
	fun updateFolder(actor: User, id: UUID, name: String?, parentId: UUID?, unset: Set<String>): DocFolder {
		val folder = folders.findLive(id) ?: throw NotFoundException("No folder $id")
		access.requireTeam(actor, folder.teamId)
		val nextParent = when {
			"parentId" in unset -> null
			parentId != null -> {
				requireSameTeam(parentId, folder.teamId)
				// The same guard team parenting has, for the same reason: a folder filed
				// under its own descendant disappears from the tree it is still in, and no
				// constraint can express reachability.
				if (parentId == id || id in folders.ancestorIds(parentId)) {
					throw BadRequestException("Folder $id cannot be filed inside itself")
				}
				parentId
			}
			else -> folder.parentId
		}
		return folders.update(id, name?.let(::requireName) ?: folder.name, nextParent)
			?: throw NotFoundException("No folder $id")
	}

	/**
	 * Puts the folder in the trash. Nothing is written to the tree at all.
	 *
	 * The cascade question the wiki's `Follow-ups` left open, answered: the delete reaches the
	 * **sub-folders** and not the **pages**, which is the line `V9` already drew in the
	 * schema — `doc_folders.parent_id` is `ON DELETE CASCADE`, `doc_pages.folder_id` is
	 * `ON DELETE SET NULL`. Structure travels with the branch; writing does not, because
	 * destroying what somebody wrote is the one direction of that mistake nobody can undo.
	 * The trash does not change the shape of the delete, only when it becomes irreversible.
	 *
	 * So the entry on this folder is the countdown for the whole branch, and
	 * [FolderTrashSource][dev.kanso.trash.FolderTrashSource] says so with a `FOLDERS`
	 * holding that cascades — no sub-folder is ever destroyed without a countdown, and one
	 * gesture makes one row in the trash rather than thirteen that have to be restored one
	 * at a time.
	 *
	 * The pages, meanwhile, need nowhere to be *put*: `folder_id` is left exactly as it was,
	 * so [pages] surfaces them at the root — where a purge's `SET NULL` would leave them
	 * anyway — and a restore has nothing to guess, because nothing was guessed on the way in.
	 */
	@Transactional
	fun deleteFolder(actor: User, id: UUID) {
		val folder = folders.findById(id) ?: throw NotFoundException("No folder $id")
		access.requireTeam(actor, folder.teamId)
		if (trash.find(TrashKind.FOLDER, id) != null) return
		trash.add(TrashKind.FOLDER, id, actor.id)
	}

	/**
	 * The first exit of screen 26, for a folder.
	 *
	 * Nothing to put back: removing the entry is [dev.kanso.trash.TrashService]'s half and
	 * the whole of what makes the branch visible again. What is left here is the one thing
	 * a source must not answer twice — who may do it.
	 */
	@Transactional
	fun restoreFolder(actor: User, id: UUID) {
		val folder = folders.findById(id) ?: throw NotFoundException("No folder $id")
		access.requireTeam(actor, folder.teamId)
	}

	/**
	 * The last exit, and what [deleteFolder] used to do: the branch goes, the pages are
	 * filed at the root.
	 *
	 * [actor] is null for the retention sweep — thirty days is the consent.
	 *
	 * The pages are filed explicitly rather than left to `ON DELETE SET NULL`, for the
	 * reason the version before the trash gave: `descendantIds` covers the sub-folders the
	 * cascade takes, and clearing them here means the write happens in one place whichever
	 * level of the branch a page was filed at.
	 */
	@Transactional
	fun purgeFolder(actor: User?, id: UUID) {
		val folder = folders.findById(id) ?: throw NotFoundException("No folder $id")
		actor?.let { access.requireTeam(it, folder.teamId) }
		descendantIds(folder).forEach(pages::clearFolder)
		folders.delete(id)
	}

	// --- pages ---------------------------------------------------------------

	/**
	 * Newest edit first: screen 22's "recently changed" is this list, minus the trash.
	 *
	 * The rows come back already filed at the root when their folder is on its way out —
	 * see [atRoot]. The extra read that decides it is the same flat tree the sidebar asks
	 * for anyway, over a table with tens of rows in it, and it buys the one property that
	 * makes a folder's delete safe: a page never disappears because its folder did.
	 */
	@Transactional(readOnly = true)
	fun pages(teamId: UUID?, folderId: UUID?, limit: Int): List<DocPage> {
		val found = pages.search(teamId, folderId, limit)
		if (found.isEmpty()) return found
		return atRoot(found, doomedFolderIds { folders.findByTeam(teamId) })
	}

	@Transactional(readOnly = true)
	fun page(id: UUID): DocPageDetail {
		val page = pages.findLive(id) ?: throw NotFoundException("No document $id")
		return DocPageDetail(
			page = atRoot(listOf(page), doomedFolderIds { folders.findByTeam(page.teamId) }).single(),
			blocks = blocks.findByPage(id),
			// One `get` per linked ticket. A page's rail carries a handful of them, and
			// the bulk read that would replace this only exists inside
			// `TicketService.decorate` — private, in a file this branch does not own.
			//
			// Sorted by identifier, not left in the order the rows came back: the rail
			// draws this list, and a rail that reshuffles between two reads of an
			// unchanged page reads as a change to the page.
			tickets = blocks.ticketIdsForPage(id).map(tickets::get).sortedBy { it.identifier },
			// One query for the whole page's locks, and the reason it is here rather than in
			// the controller is that a lock is part of what a block *is* to a reader: a
			// second endpoint for it would let a client draw the blocks before knowing which
			// of them it may type in, which is a paragraph that accepts keystrokes for one
			// paint and then refuses them.
			locks = locks.liveForPage(id).associateBy { it.blockId },
		)
	}

	@Transactional
	fun createPage(
		actor: User,
		teamId: UUID,
		folderId: UUID?,
		title: String,
		templateSlug: String?,
	): DocPageDetail {
		access.requireTeam(actor, teamId)
		requireTeamExists(teamId)
		folderId?.let { requireSameTeam(it, teamId) }
		val template = templateSlug?.let {
			templates.findBySlug(it) ?: throw BadRequestException("No template '$it'")
		}

		val page = pages.insert(teamId, folderId, requireName(title), actor.id)
		template?.blocks?.forEachIndexed { index, block ->
			blocks.insert(page.id, index, block.kind, block.content)
		}
		events.publish(KansoEvent.doc(ChangeKind.CREATED, page.id, teamId))
		return page(page.id)
	}

	/**
	 * `null` means "leave alone"; naming a field in [unset] clears it. The same
	 * convention `TicketPatch` uses, for the same reason: JSON cannot tell an absent key
	 * from an explicit null, and a page can legitimately belong to no folder.
	 */
	@Transactional
	fun updatePage(
		actor: User,
		id: UUID,
		title: String?,
		folderId: UUID?,
		unset: Set<String>,
	): DocPageDetail {
		val page = pages.findLive(id) ?: throw NotFoundException("No document $id")
		access.requireTeam(actor, page.teamId)
		val nextFolder = when {
			"folderId" in unset -> null
			folderId != null -> folderId.also { requireSameTeam(it, page.teamId) }
			else -> page.folderId
		}
		pages.update(id, title?.let(::requireName) ?: page.title, nextFolder, actor.id)
			?: throw NotFoundException("No document $id")
		// No `blockId`: the page itself changed — a title, a folder — and no block did.
		// `KAN-25`'s receiver reads the absence as "the page, not a paragraph", which is
		// what keeps a retitle from looking like somebody typing into the caret you are in.
		events.publish(KansoEvent.doc(ChangeKind.UPDATED, id, page.teamId))
		return page(id)
	}

	/**
	 * Puts the page in the trash, and touches the row not at all.
	 *
	 * A second delete is not an error and not a second countdown: the entry is the fact, and
	 * `PRIMARY KEY (entity_type, entity_id)` would refuse the duplicate anyway — returning
	 * quietly is what `TicketService.delete` does and for the same reason, a retried request.
	 */
	@Transactional
	fun deletePage(actor: User, id: UUID) {
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		access.requireTeam(actor, page.teamId)
		if (trash.find(TrashKind.DOC, id) != null) return
		trash.add(TrashKind.DOC, id, actor.id)
		// DELETED for a page that is only in the trash, because that is what it *is* to
		// every reader: `pages.findLive` is a 404 for it and the tree stops drawing it. The
		// row surviving for the retention window is the sweep's business, not a client's.
		events.publish(KansoEvent.doc(ChangeKind.DELETED, id, page.teamId))
	}

	/** Nothing to put back — the delete wrote nothing. Who may do it is the whole of this. */
	@Transactional
	fun restorePage(actor: User, id: UUID) {
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		access.requireTeam(actor, page.teamId)
	}

	/**
	 * For good, and what [deletePage] used to do. [actor] is null for the retention sweep.
	 *
	 * The blocks and their backlink rows cascade; the tickets those backlinks named do not,
	 * which is the drawing's own detail — deleting a document that mentioned two tickets
	 * deletes neither ticket. Only the reference goes, and `V9`'s two `ON DELETE CASCADE`s
	 * on `doc_block_tickets` are what make that true from both ends.
	 */
	@Transactional
	fun purgePage(actor: User?, id: UUID) {
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		actor?.let { access.requireTeam(it, page.teamId) }
		pages.delete(id)
	}

	// --- templates -----------------------------------------------------------

	@Transactional(readOnly = true)
	fun templates(): List<DocTemplate> = templates.findAll()

	// --- helpers -------------------------------------------------------------

	/**
	 * Every folder on its way out: the ones in the trash, and everything under them.
	 *
	 * Takes the tree it was given rather than reading one, so the two callers that already
	 * hold a flat list do not read it twice. One query for the whole trash, which is what
	 * [dev.kanso.trash.TrashRepository.idsOf] exists for.
	 */
	private fun doomedFolderIds(tree: () -> List<DocFolder>): Set<UUID> {
		val thrownAway = trash.idsOf(TrashKind.FOLDER).toSet()
		// Before the tree, and that is the point of the lambda: with no folder in the trash —
		// which is nearly always — the two page reads below cost no extra query at all.
		if (thrownAway.isEmpty()) return emptySet()
		val all = tree()
		val doomed = all.mapNotNullTo(mutableSetOf()) { it.id.takeIf { id -> id in thrownAway } }
		if (doomed.isEmpty()) return emptySet()
		var frontier: List<UUID> = doomed.toList()
		while (frontier.isNotEmpty()) {
			frontier = all.filter { it.parentId in frontier && it.id !in doomed }.map { it.id }
			doomed += frontier
		}
		return doomed
	}

	/**
	 * Files a page at the root when the folder it names is on its way out.
	 *
	 * A read, not a write: `folder_id` stays exactly as it was, which is the whole reason
	 * restoring a folder puts its pages back *exactly* rather than approximately. What the
	 * reader is told is where the page can be found today — the root — and that happens to
	 * be where a purge's `ON DELETE SET NULL` would leave it for good.
	 */
	private fun atRoot(found: List<DocPage>, doomed: Set<UUID>): List<DocPage> {
		if (doomed.isEmpty()) return found
		return found.map { if (it.folderId in doomed) it.copy(folderId = null) else it }
	}

	/** [id] and every folder beneath it, so a purge can file their pages at the root. */
	private fun descendantIds(folder: DocFolder): List<UUID> {
		val all = folders.findByTeam(folder.teamId)
		val found = mutableListOf(folder.id)
		var frontier = listOf(folder.id)
		while (frontier.isNotEmpty()) {
			frontier = all.filter { it.parentId in frontier }.map { it.id } - found.toSet()
			found += frontier
		}
		return found
	}

	private fun requireTeamExists(teamId: UUID) {
		teams.findById(teamId) ?: throw BadRequestException("No team $teamId")
	}

	/**
	 * A folder and the thing filed in it belong to the same team. Not a database
	 * constraint, for the same reason a ticket's project is not one: the check needs two
	 * rows, and the message it can give — which team the folder actually belongs to — is
	 * the whole value of refusing.
	 */
	private fun requireSameTeam(folderId: UUID, teamId: UUID) {
		// `findLive`, so nothing can be filed into a folder somebody has thrown away: the
		// tree does not draw it, and a page filed there would vanish from the tree with it.
		val folder = folders.findLive(folderId) ?: throw BadRequestException("No folder $folderId")
		if (folder.teamId != teamId) {
			throw BadRequestException("Folder $folderId belongs to team ${folder.teamId}, not team $teamId")
		}
	}

	private fun requireName(raw: String): String =
		raw.trim().ifEmpty { throw BadRequestException("A name cannot be blank") }
}

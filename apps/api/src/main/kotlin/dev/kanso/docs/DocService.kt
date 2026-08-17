package dev.kanso.docs

import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
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
) {

	// --- folders -------------------------------------------------------------

	@Transactional(readOnly = true)
	fun folders(teamId: UUID?): List<DocFolder> = folders.findByTeam(teamId)

	@Transactional
	fun createFolder(actor: User, teamId: UUID, parentId: UUID?, name: String): DocFolder {
		access.requireTeam(actor, teamId)
		requireTeamExists(teamId)
		parentId?.let { requireSameTeam(it, teamId) }
		return folders.insert(teamId, parentId, requireName(name))
	}

	@Transactional
	fun updateFolder(actor: User, id: UUID, name: String?, parentId: UUID?): DocFolder {
		val folder = folders.findById(id) ?: throw NotFoundException("No folder $id")
		access.requireTeam(actor, folder.teamId)
		if (parentId != null) {
			requireSameTeam(parentId, folder.teamId)
			// The same guard team parenting has, for the same reason: a folder reparented
			// under its own descendant disappears from the tree it is still in, and no
			// constraint can express reachability.
			if (parentId == id || id in folders.ancestorIds(parentId)) {
				throw BadRequestException("Folder $id cannot be filed inside itself")
			}
		}
		return folders.update(id, name?.let(::requireName) ?: folder.name, parentId)
			?: throw NotFoundException("No folder $id")
	}

	/**
	 * Deletes the folder and its sub-folders, and files their pages at the root.
	 *
	 * The pages survive on purpose — `folder_id` is `ON DELETE SET NULL` in `V9`.
	 * Tidying a tree is not a decision to destroy what was written in it, and this is
	 * the one direction of that mistake nobody can undo from the interface.
	 */
	@Transactional
	fun deleteFolder(actor: User, id: UUID) {
		val folder = folders.findById(id) ?: throw NotFoundException("No folder $id")
		access.requireTeam(actor, folder.teamId)
		descendantIds(folder).forEach(pages::clearFolder)
		folders.delete(id)
	}

	// --- pages ---------------------------------------------------------------

	/** Newest edit first: screen 22's "recently changed" is this list, unfiltered. */
	@Transactional(readOnly = true)
	fun pages(teamId: UUID?, folderId: UUID?, limit: Int): List<DocPage> =
		pages.search(teamId, folderId, limit)

	@Transactional(readOnly = true)
	fun page(id: UUID): DocPageDetail {
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		return DocPageDetail(
			page = page,
			blocks = blocks.findByPage(id),
			// One `get` per linked ticket. A page's rail carries a handful of them, and
			// the bulk read that would replace this only exists inside
			// `TicketService.decorate` — private, in a file this branch does not own.
			tickets = blocks.ticketIdsForPage(id).map(tickets::get),
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
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		access.requireTeam(actor, page.teamId)
		val nextFolder = when {
			"folderId" in unset -> null
			folderId != null -> folderId.also { requireSameTeam(it, page.teamId) }
			else -> page.folderId
		}
		pages.update(id, title?.let(::requireName) ?: page.title, nextFolder, actor.id)
			?: throw NotFoundException("No document $id")
		return page(id)
	}

	@Transactional
	fun deletePage(actor: User, id: UUID) {
		val page = pages.findById(id) ?: throw NotFoundException("No document $id")
		access.requireTeam(actor, page.teamId)
		// The blocks and their backlinks cascade; the tickets those backlinks named do
		// not, which is the drawing's own detail: deleting a document that mentioned two
		// tickets deletes neither ticket. Only the reference goes.
		pages.delete(id)
	}

	// --- templates -----------------------------------------------------------

	@Transactional(readOnly = true)
	fun templates(): List<DocTemplate> = templates.findAll()

	// --- helpers -------------------------------------------------------------

	/** [id] and every folder beneath it, so a delete can file their pages at the root. */
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
		val folder = folders.findById(folderId) ?: throw BadRequestException("No folder $folderId")
		if (folder.teamId != teamId) {
			throw BadRequestException("Folder $folderId belongs to team ${folder.teamId}, not team $teamId")
		}
	}

	private fun requireName(raw: String): String =
		raw.trim().ifEmpty { throw BadRequestException("A name cannot be blank") }
}

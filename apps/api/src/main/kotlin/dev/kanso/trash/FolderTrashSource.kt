package dev.kanso.trash

import dev.kanso.docs.DocFolder
import dev.kanso.docs.DocFolderRepository
import dev.kanso.docs.DocPageRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A folder of the document tree, and the one kind whose `holds` had a decision in it.
 *
 * **The delete cascades to the sub-folders and not to the pages.** That is not a new rule:
 * it is the line `V9` drew in the schema, where `doc_folders.parent_id` is `ON DELETE
 * CASCADE` and `doc_pages.folder_id` is `ON DELETE SET NULL`. Structure travels with the
 * branch; writing does not. The trash changes when the delete becomes irreversible, not
 * what it reaches — and [DocService.deleteFolder] argues the rest of it in place.
 *
 * Two things follow, and both are visible from here.
 *
 * The `FOLDERS` holding cascades, which is what keeps the invariant `V11` rests on: a row
 * in `trash_entries` *is* a deletion, so nothing may be destroyed without one. The entry on
 * this folder is the countdown for the whole branch, the pane names the branch and counts
 * it before anybody confirms, and restoring is exact because nothing under it was ever
 * written. The alternative — an entry per sub-folder — turns one gesture into thirteen rows
 * and thirteen restores, and leaves the parent's restore having to undo deletions it did
 * not make.
 *
 * The `PAGES` holding does not cascade, and the pages need nowhere to be *put*: `folder_id`
 * is untouched, so `DocService.pages` surfaces them at the root — where a purge's `SET
 * NULL` leaves them for good — and a restore puts them back exactly rather than
 * approximately.
 */
@Component
class FolderTrashSource(
	private val documents: DocService,
	private val rows: DocFolderRepository,
	private val pages: DocPageRepository,
	private val teams: TeamRepository,
) : TrashSource {

	override val kind = TrashKind.FOLDER

	override fun describe(ids: Collection<UUID>): List<TrashItem> = itemsOf(rows.findTrashed(ids))

	override fun restore(actor: User, id: UUID) = documents.restoreFolder(actor, id)

	override fun purge(actor: User?, id: UUID) = documents.purgeFolder(actor, id)

	private fun itemsOf(found: List<DocFolder>): List<TrashItem> {
		if (found.isEmpty()) return emptyList()
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associate { it.id to it }
		// One flat tree per team in play, not one per folder: how big a branch is is a
		// question about the whole tree, and two folders of one team share the answer. The
		// tree includes what is in the trash, which is what lets a branch be measured at all.
		val treesByTeam = found.map { it.teamId }.toSet().associateWith { rows.findByTeam(it) }
		val branches = found.associate { it.id to branchOf(treesByTeam.getValue(it.teamId), it.id) }
		val pageCounts = pages.countsByFolder(branches.values.flatten().toSet())

		return found.map { folder ->
			val tree = treesByTeam.getValue(folder.teamId)
			val branch = branches.getValue(folder.id)
			TrashItem(
				kind = kind,
				id = folder.id,
				label = folder.name,
				// The folder above it, its team at the root. A folder always has a team.
				parent = folder.parentId?.let { parent -> tree.firstOrNull { it.id == parent } }
					?.let { TrashParent("folder", it.id, it.name) }
					?: keys[folder.teamId]?.let { TrashParent("team", it.id, it.name) },
				holds = holdingsOf(
					subFolders = branch.size - 1,
					heldPages = branch.sumOf { pageCounts[it] ?: 0 },
				),
			)
		}
	}

	/** [id] and every folder under it, from a tree already read. Nearest order is irrelevant. */
	private fun branchOf(tree: List<DocFolder>, id: UUID): List<UUID> {
		val found = mutableListOf(id)
		var frontier = listOf(id)
		while (frontier.isNotEmpty()) {
			frontier = tree.filter { it.parentId in frontier }.map { it.id } - found.toSet()
			found += frontier
		}
		return found
	}

	/**
	 * The pair, and the pane's whole sentence about a folder: "Held 2 folders and 3 pages —
	 * the pages were not deleted, only the reference goes."
	 *
	 * The rider is literally true at the moment it matters. Purging the folder destroys the
	 * sub-folders through `parent_id`'s cascade and clears `folder_id` on every page in the
	 * branch: the reference is exactly what goes.
	 */
	private fun holdingsOf(subFolders: Int, heldPages: Int): List<TrashHolding> = buildList {
		if (subFolders > 0) add(TrashHolding(TrashHoldingKind.FOLDERS, subFolders, cascades = true))
		if (heldPages > 0) add(TrashHolding(TrashHoldingKind.PAGES, heldPages, cascades = false))
	}
}

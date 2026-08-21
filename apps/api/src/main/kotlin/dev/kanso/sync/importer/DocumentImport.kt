package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockKind
import dev.kanso.docs.DocBlockRepository
import dev.kanso.docs.DocService
import dev.kanso.domain.User
import dev.kanso.repo.ImportOrigin
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import dev.kanso.sync.notion.NotionPage
import org.springframework.stereotype.Service
import java.util.UUID

/** What one documents base wrote: its pages, and the folder it needed, if any. */
data class DocsWritten(val docs: Int, val folders: Int)

/**
 * A base whose pages are documents, in a folder named after it.
 *
 * The folder is created on the first page rather than up front, so a base with nothing
 * left to adopt — every page already imported, or every page refused — leaves no empty
 * folder behind on the docs tree.
 *
 * Unlike a tickets base's container project, the folder is *not* recorded in
 * `notion_import_origin`: the table's `entity_type` is checked against four values and a
 * folder is none of them. A second import of a base with one new page therefore still
 * creates a second folder — the same defect the container project used to have, waiting
 * on a migration that gives a folder a kind of its own.
 */
@Service
class DocumentImport(
	private val docs: DocService,
	private val blocks: DocBlockRepository,
	private val origins: ImportOriginRepository,
) {

	fun write(actor: User, base: PlannedBase, fallbackTeam: UUID): DocsWritten {
		val teamId = base.fallback.teamId ?: fallbackTeam
		var folderId: UUID? = null
		var written = 0

		for (page in base.adoptable) {
			val folder = folderId ?: docs.createFolder(actor, teamId, null, base.base.name).id.also { folderId = it }
			val docId = createDocument(actor, teamId, folder, base, page)
			origins.record(ImportOrigin(page.id, OriginKind.DOC, docId, base.base.dataSourceId))
			written++
		}
		return DocsWritten(written, if (folderId == null) 0 else 1)
	}

	private fun createDocument(
		actor: User,
		teamId: UUID,
		folderId: UUID,
		base: PlannedBase,
		page: NotionPage,
	): UUID {
		val created = docs.createPage(
			actor = actor,
			teamId = teamId,
			folderId = folderId,
			title = requireNotNull(base.reader.title(page)) { "an unadoptable page reached the writer" },
			templateSlug = null,
		)
		// A callout, which is the block screen 07 draws for "read this bit": the properties
		// Kanso has no column for are the part of the page nothing else here explains.
		provenance(base.reader, page)?.let {
			blocks.insert(created.page.id, 0, DocBlockKind.CALLOUT, mapOf("text" to it))
		}
		return created.page.id
	}
}

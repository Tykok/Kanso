package dev.kanso.docs

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocPageRepository {

	fun findById(id: UUID): DocPage? =
		DocPages.selectAll().where { DocPages.id eq id }.singleOrNull()?.toPage()

	/**
	 * Newest edit first, which is the only order screen 22 reads this table in — its
	 * "recently changed" list — and the order `doc_pages_recent_idx` was written for.
	 */
	fun search(teamId: UUID?, folderId: UUID?, limit: Int): List<DocPage> {
		val conditions = buildList {
			teamId?.let { add(DocPages.teamId eq it) }
			folderId?.let { add(DocPages.folderId eq it) }
		}
		return DocPages.selectAll()
			.where { if (conditions.isEmpty()) Op.TRUE else conditions.compoundAnd() }
			.orderBy(DocPages.updatedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toPage() }
	}

	fun insert(teamId: UUID, folderId: UUID?, title: String, authorId: UUID): DocPage {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		DocPages.insert {
			it[DocPages.id] = id
			it[DocPages.teamId] = teamId
			it[DocPages.folderId] = folderId
			it[DocPages.title] = title
			it[DocPages.authorId] = authorId
			it[editedBy] = authorId
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun update(id: UUID, title: String, folderId: UUID?, editedBy: UUID): DocPage? {
		val changed = DocPages.update({ DocPages.id eq id }) {
			it[DocPages.title] = title
			it[DocPages.folderId] = folderId
			it[DocPages.editedBy] = editedBy
			it[updatedAt] = OffsetDateTime.now()
		}
		return if (changed == 0) null else findById(id)
	}

	/**
	 * What a block edit does to the page: stamps who touched it, and when. The footer's
	 * "edited by Tykok 3 min ago" is those two facts, and neither is reachable from the
	 * block rows themselves — a page whose last change was a *deleted* block has no
	 * block left to read a timestamp off.
	 */
	fun touch(id: UUID, editedBy: UUID) {
		DocPages.update({ DocPages.id eq id }) {
			it[DocPages.editedBy] = editedBy
			it[updatedAt] = OffsetDateTime.now()
		}
	}

	fun delete(id: UUID): Boolean = DocPages.deleteWhere { DocPages.id eq id } > 0

	/** Filing, not destruction: a deleted folder leaves its pages at the root. */
	fun clearFolder(folderId: UUID) {
		DocPages.update({ DocPages.folderId eq folderId }) { it[DocPages.folderId] = null }
	}
}

internal fun ResultRow.toPage() = DocPage(
	id = this[DocPages.id],
	teamId = this[DocPages.teamId],
	folderId = this[DocPages.folderId],
	title = this[DocPages.title],
	authorId = this[DocPages.authorId],
	editedById = this[DocPages.editedBy],
	notionPageId = this[DocPages.notionPageId],
	notionUrl = this[DocPages.notionUrl],
	createdAt = this[DocPages.createdAt],
	updatedAt = this[DocPages.updatedAt],
)

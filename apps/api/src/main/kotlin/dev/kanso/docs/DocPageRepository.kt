package dev.kanso.docs

import dev.kanso.db.TrashEntries
import dev.kanso.trash.TrashKind
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocPageRepository {

	/**
	 * A page is soft-deleted exactly when `trash_entries` names it. There is no
	 * `deleted_at` on `doc_pages` to keep in step with that — `V11`'s whole argument is
	 * that the entry *is* the deletion, which is why landing this table cost it no column.
	 *
	 * The same split [dev.kanso.repo.TicketRepository] keeps: every query that answers
	 * "which pages are there" excludes the trash, and [findById] deliberately does not.
	 */
	private val trashed
		get() = TrashEntries.select(TrashEntries.entityId)
			.where { TrashEntries.entityType eq TrashKind.DOC.wire }

	/**
	 * The row, whatever state it is in — **including** one in the trash.
	 *
	 * A row reader, not a scope query: the trash has to describe, restore and purge a page
	 * *because* it was thrown away, so filtering here would leave three exits pointing at
	 * nothing. [findLive] is what a reader or a write loads instead.
	 */
	fun findById(id: UUID): DocPage? =
		DocPages.selectAll().where { DocPages.id eq id }.singleOrNull()?.toPage()

	/** The row unless it is in the trash: what an open, an edit or a block write may touch. */
	fun findLive(id: UUID): DocPage? =
		DocPages.selectAll()
			.where { (DocPages.id eq id) and (DocPages.id notInSubQuery trashed) }
			.singleOrNull()?.toPage()

	/**
	 * [findLive] for a whole set, in one query — the sidebar's favourites resolve four
	 * kinds at once and cannot afford a round trip per pin.
	 *
	 * The inverse of [findTrashed] over the same ids, which is exactly what a favourite
	 * wants: a pinned page in the trash is not drawn, and the pin itself survives, so the
	 * thirty days it has to come back are thirty days the pin is still there.
	 */
	fun findAllLive(ids: Collection<UUID>): List<DocPage> =
		if (ids.isEmpty()) emptyList()
		else DocPages.selectAll()
			.where { (DocPages.id inList ids) and (DocPages.id notInSubQuery trashed) }
			.map { it.toPage() }

	/** The trash's own read: only the rows among [ids] that are actually in it. */
	fun findTrashed(ids: Collection<UUID>): List<DocPage> =
		if (ids.isEmpty()) emptyList()
		else DocPages.selectAll()
			.where { (DocPages.id inList ids) and (DocPages.id inSubQuery trashed) }
			.map { it.toPage() }

	/**
	 * Newest edit first, which is the only order screen 22 reads this table in — its
	 * "recently changed" list — and the order `doc_pages_recent_idx` was written for.
	 */
	fun search(teamId: UUID?, folderId: UUID?, limit: Int): List<DocPage> {
		val conditions = buildList {
			// Unconditional: the tree, the recent list and a folder's contents are all the
			// same question, and none of them is asking about what somebody threw away.
			add(DocPages.id notInSubQuery trashed)
			teamId?.let { add(DocPages.teamId eq it) }
			folderId?.let { add(DocPages.folderId eq it) }
		}
		return DocPages.selectAll()
			.where { conditions.compoundAnd() }
			.orderBy(DocPages.updatedAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toPage() }
	}

	/**
	 * How many live pages sit directly in each of these folders — what a folder's trash
	 * row counts to say the writing survives it.
	 *
	 * One query for the whole set rather than one per folder, and it excludes the trash:
	 * a folder's pane must not promise to file a page somebody had already thrown away.
	 */
	fun countsByFolder(folderIds: Collection<UUID>): Map<UUID, Int> =
		if (folderIds.isEmpty()) emptyMap()
		else DocPages.select(DocPages.folderId)
			.where { (DocPages.folderId inList folderIds) and (DocPages.id notInSubQuery trashed) }
			.mapNotNull { it[DocPages.folderId] }
			.groupingBy { it }
			.eachCount()

	/**
	 * Which of [ids] belong to one of [teamIds] — what a team's disposition has to forget.
	 *
	 * Unfiltered on the trash on purpose: the caller's [ids] *are* the trash.
	 */
	fun idsWithinTeams(ids: Collection<UUID>, teamIds: Collection<UUID>): List<UUID> =
		if (ids.isEmpty() || teamIds.isEmpty()) emptyList()
		else DocPages.select(DocPages.id)
			.where { (DocPages.id inList ids) and (DocPages.teamId inList teamIds) }
			.map { it[DocPages.id] }

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

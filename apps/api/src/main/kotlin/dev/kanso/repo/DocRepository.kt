package dev.kanso.repo

import dev.kanso.db.NotionDocs
import dev.kanso.db.toDoc
import dev.kanso.domain.NotionDoc
import dev.kanso.domain.SyncState
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocRepository {

	fun findById(id: UUID): NotionDoc? =
		NotionDocs.selectAll().where { NotionDocs.id eq id }.singleOrNull()?.toDoc()

	fun findAllById(ids: Collection<UUID>): List<NotionDoc> =
		if (ids.isEmpty()) emptyList()
		else NotionDocs.selectAll().where { NotionDocs.id inList ids }.map { it.toDoc() }

	fun findByNotionPageId(pageId: String): NotionDoc? =
		NotionDocs.selectAll().where { NotionDocs.notionPageId eq pageId }.singleOrNull()?.toDoc()

	/**
	 * Docs are Notion pages Kanso only references, never authors — so this is an
	 * upsert on the Notion id rather than a create.
	 */
	fun upsert(notionPageId: String, title: String?, url: String?): NotionDoc {
		val existing = findByNotionPageId(notionPageId)
		NotionDocs.upsert(NotionDocs.notionPageId) {
			it[NotionDocs.id] = existing?.id ?: UUID.randomUUID()
			it[NotionDocs.notionPageId] = notionPageId
			it[NotionDocs.title] = title
			it[NotionDocs.url] = url
			if (existing == null) it[syncState] = SyncState.PENDING.wire
		}
		return requireNotNull(findByNotionPageId(notionPageId))
	}

	fun findAll(): List<NotionDoc> =
		NotionDocs.selectAll().orderBy(NotionDocs.title to SortOrder.ASC).map { it.toDoc() }

	// --- mirror bookkeeping --------------------------------------------------

	fun markSynced(id: UUID, mirrorPageId: String, notionLastEdited: OffsetDateTime?) {
		NotionDocs.update({ NotionDocs.id eq id }) {
			it[NotionDocs.mirrorPageId] = mirrorPageId
			it[syncState] = SyncState.SYNCED.wire
			it[notionSyncedAt] = OffsetDateTime.now()
			it[notionLastEditedTime] = notionLastEdited
		}
	}

	fun markSyncState(id: UUID, state: SyncState) {
		NotionDocs.update({ NotionDocs.id eq id }) { it[syncState] = state.wire }
	}
}

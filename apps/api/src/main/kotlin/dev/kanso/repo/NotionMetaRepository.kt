package dev.kanso.repo

import dev.kanso.db.NotionDatabases
import dev.kanso.db.NotionSyncCursors
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** Which Notion database and data source each mirrored entity kind lives in. */
data class NotionDatabaseRef(
	val kind: String,
	val databaseId: String,
	val dataSourceId: String,
	val parentPageId: String?,
)

data class NotionCursor(
	val dataSourceId: String,
	val lastEditTime: OffsetDateTime?,
	val lastRunAt: OffsetDateTime?,
	val lastError: String?,
)

@Repository
class NotionMetaRepository {

	fun find(kind: String): NotionDatabaseRef? =
		NotionDatabases.selectAll().where { NotionDatabases.kind eq kind }.singleOrNull()?.let {
			NotionDatabaseRef(
				kind = it[NotionDatabases.kind],
				databaseId = it[NotionDatabases.databaseId],
				dataSourceId = it[NotionDatabases.dataSourceId],
				parentPageId = it[NotionDatabases.parentPageId],
			)
		}

	fun findAll(): List<NotionDatabaseRef> = NotionDatabases.selectAll().map {
		NotionDatabaseRef(
			kind = it[NotionDatabases.kind],
			databaseId = it[NotionDatabases.databaseId],
			dataSourceId = it[NotionDatabases.dataSourceId],
			parentPageId = it[NotionDatabases.parentPageId],
		)
	}

	fun save(kind: String, databaseId: String, dataSourceId: String, parentPageId: String?) {
		NotionDatabases.upsert(NotionDatabases.kind) {
			it[NotionDatabases.kind] = kind
			it[NotionDatabases.databaseId] = databaseId
			it[NotionDatabases.dataSourceId] = dataSourceId
			it[NotionDatabases.parentPageId] = parentPageId
			it[createdAt] = OffsetDateTime.now()
			it[updatedAt] = OffsetDateTime.now()
		}
	}

	// --- inbound polling cursors ---------------------------------------------

	fun cursor(dataSourceId: String): NotionCursor? =
		NotionSyncCursors.selectAll().where { NotionSyncCursors.dataSourceId eq dataSourceId }
			.singleOrNull()?.let {
				NotionCursor(
					dataSourceId = it[NotionSyncCursors.dataSourceId],
					lastEditTime = it[NotionSyncCursors.lastEditTime],
					lastRunAt = it[NotionSyncCursors.lastRunAt],
					lastError = it[NotionSyncCursors.lastError],
				)
			}

	fun saveCursor(dataSourceId: String, lastEditTime: OffsetDateTime?, error: String?) {
		NotionSyncCursors.upsert(NotionSyncCursors.dataSourceId) {
			it[NotionSyncCursors.dataSourceId] = dataSourceId
			it[NotionSyncCursors.lastEditTime] = lastEditTime
			it[lastRunAt] = OffsetDateTime.now()
			it[lastError] = error?.take(2000)
		}
	}
}

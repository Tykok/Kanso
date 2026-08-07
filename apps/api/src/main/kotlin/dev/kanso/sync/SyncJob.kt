package dev.kanso.sync

import java.util.UUID

/**
 * Dependency order is encoded in [priority]: a project page cannot be created in
 * Notion before its team page exists, and a ticket needs both. The worker drains
 * lower numbers first, so a fresh install pushes the graph in a valid order
 * without a topological sort at runtime.
 */
enum class SyncEntityType(val wire: String, val priority: Int) {
	TEAM("team", 10),
	PROJECT("project", 20),
	TICKET("ticket", 30),
	DOC("doc", 40);

	companion object {
		fun from(raw: String): SyncEntityType = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown sync entity type '$raw'")
	}
}

enum class SyncOperation(val wire: String) {
	/** Create or overwrite the mirrored page from the current Postgres row. */
	UPSERT("upsert"),

	/** Notion archives rather than deletes; a Kanso delete lands here too. */
	ARCHIVE("archive"),
	DELETE("delete");

	companion object {
		fun from(raw: String): SyncOperation = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown sync operation '$raw'")
	}
}

/**
 * A delete has to carry the Notion page id: by the time the worker runs, the
 * Postgres row is gone and there is nothing left to look it up from.
 */
fun deletePayload(notionPageId: String?): String? =
	notionPageId?.let { """{"notionPageId":"$it"}""" }

data class SyncJob(
	val id: Long,
	val entityType: SyncEntityType,
	val entityId: UUID,
	val operation: SyncOperation,
	val attempts: Int,
	val priority: Int,
	val payload: String?,
	val lastError: String?,
)

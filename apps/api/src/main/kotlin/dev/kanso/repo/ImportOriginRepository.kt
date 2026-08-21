package dev.kanso.repo

import dev.kanso.db.NotionImportOrigins
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** The four kinds of row an import can produce. Closed, like every other wire vocabulary. */
enum class OriginKind(override val wire: String) : Wire {
	TEAM("team"), PROJECT("project"), TICKET("ticket"), DOC("doc");

	companion object {
		fun from(raw: String): OriginKind = parse(entries.toTypedArray(), raw)
	}
}

data class ImportOrigin(
	val notionPageId: String,
	val kind: OriginKind,
	val entityId: UUID,
	val dataSourceId: String,
)

@Repository
class ImportOriginRepository {

	/**
	 * Looked up in one query per import rather than one per page: a base of four hundred
	 * pages would otherwise cost four hundred round trips inside the transaction that holds
	 * the team's counter.
	 */
	fun byPageIds(pageIds: Collection<String>): Map<String, ImportOrigin> {
		if (pageIds.isEmpty()) return emptyMap()
		return NotionImportOrigins.selectAll()
			.where { NotionImportOrigins.notionPageId inList pageIds.toSet() }
			.associate { it[NotionImportOrigins.notionPageId] to it.toOrigin() }
	}

	fun record(origin: ImportOrigin) {
		NotionImportOrigins.insert {
			it[notionPageId] = origin.notionPageId
			it[entityType] = origin.kind.wire
			it[entityId] = origin.entityId
			it[dataSourceId] = origin.dataSourceId
			it[importedAt] = OffsetDateTime.now()
		}
	}

	private fun ResultRow.toOrigin() = ImportOrigin(
		notionPageId = this[NotionImportOrigins.notionPageId],
		kind = OriginKind.from(this[NotionImportOrigins.entityType]),
		entityId = this[NotionImportOrigins.entityId],
		dataSourceId = this[NotionImportOrigins.dataSourceId],
	)
}

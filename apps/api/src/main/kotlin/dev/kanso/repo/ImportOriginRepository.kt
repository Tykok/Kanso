package dev.kanso.repo

import dev.kanso.db.NotionImportOrigins
import dev.kanso.db.Projects
import dev.kanso.db.Teams
import dev.kanso.db.Tickets
import dev.kanso.docs.DocPages
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

	/**
	 * The seed, minus every row whose entity is gone.
	 *
	 * `V15` gives this table no foreign key — the reference is polymorphic — and answers the
	 * obvious objection by saying that "a row whose entity was deleted resolves to nothing,
	 * which the resolver already treats as unlinked and falls back on". That is only true of
	 * a reader that checks: neither `TeamService.delete` nor `ProjectService.delete` touches
	 * this table, so a row outliving the thing it names is ordinary, and an id that is
	 * *present but dead* is the one thing a `?: fallback` chain cannot fall through. Handed
	 * on unfiltered it reaches `ProjectService.get` or `TeamService.update` as a real
	 * argument and takes the whole import down with a `NotFoundException` or a
	 * `BadRequestException` — permanently, since the rollback leaves the row where it was.
	 * Filtered here, once, rather than guarded at each of the five places a seeded id is
	 * read: the promise `V15` makes in one comment is kept in one place.
	 *
	 * Filtered, not deleted. Cleaning up origins whose entity is gone is a separate decision
	 * with a separate consequence — the page would become importable again — and a read path
	 * is not where it gets made.
	 *
	 * One query per kind, never one per row: [byPageIds] exists precisely so that a base of
	 * four hundred pages costs one round trip, and a filter that undid that would be worse
	 * than the defect.
	 */
	fun live(seed: Map<String, ImportOrigin>): Map<String, ImportOrigin> {
		if (seed.isEmpty()) return seed
		val alive = seed.values.groupBy({ it.kind }, { it.entityId })
			.flatMapTo(mutableSetOf()) { (kind, ids) -> existing(kind, ids) }
		return seed.filterValues { it.entityId in alive }
	}

	/**
	 * Which of [ids] still have a row of that kind.
	 *
	 * Row existence and nothing more: a ticket or a document in the trash still has a row,
	 * and still resolves for everything that loads one by id — `ScheduleService.link` loads
	 * its predecessor trash and all — so treating a trashed row as gone would drop arrows
	 * that would have worked. What this excludes is what is really not there any more: a
	 * deleted team, a deleted project, a purged ticket or page.
	 */
	private fun existing(kind: OriginKind, ids: List<UUID>): List<UUID> {
		val id = when (kind) {
			OriginKind.TEAM -> Teams.id
			OriginKind.PROJECT -> Projects.id
			OriginKind.TICKET -> Tickets.id
			OriginKind.DOC -> DocPages.id
		}
		return id.table.select(id).where { id inList ids }.map { it[id] }
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

	/**
	 * The same fact for a key that names a *base* rather than a page: a tickets base's
	 * container project — see [dev.kanso.sync.importer.TicketImport] for why it is keyed
	 * that way at all.
	 *
	 * An upsert where [record] is a plain insert, and only here. A container this base's
	 * seed named may have been deleted since, in which case [live] drops it, the base is
	 * read as having no container and a fresh one is created — and the row then has to name
	 * the new project, or the next run would find the same dead id again and make a third
	 * container. [record]'s insert keeps its primary-key collision, which is what makes a
	 * *page* imported twice a failure rather than a silent overwrite; a base's own key is
	 * the one key this import legitimately writes twice.
	 */
	fun recordContainer(origin: ImportOrigin) {
		NotionImportOrigins.upsert(NotionImportOrigins.notionPageId) {
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

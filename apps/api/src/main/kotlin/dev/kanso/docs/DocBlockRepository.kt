package dev.kanso.docs

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocBlockRepository(private val json: ObjectMapper) {

	fun findById(id: UUID): DocBlock? =
		decorate(DocBlocks.selectAll().where { DocBlocks.id eq id }.toList()).singleOrNull()

	/**
	 * `position, id` and not `position` alone: positions are dense but not unique (see
	 * `V9`), so a tie has to break on something stable or two reads of one page could
	 * disagree about which of the two blocks comes first.
	 */
	fun findByPage(pageId: UUID): List<DocBlock> = decorate(
		DocBlocks.selectAll()
			.where { DocBlocks.pageId eq pageId }
			.orderBy(DocBlocks.position to SortOrder.ASC, DocBlocks.id to SortOrder.ASC)
			.toList(),
	)

	fun insert(pageId: UUID, position: Int, kind: DocBlockKind, content: Map<String, Any?>): UUID {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		DocBlocks.insert {
			it[DocBlocks.id] = id
			it[DocBlocks.pageId] = pageId
			it[DocBlocks.position] = position
			it[DocBlocks.kind] = kind.wire
			it[DocBlocks.content] = json.writeValueAsString(content)
			it[createdAt] = now
			it[updatedAt] = now
		}
		return id
	}

	fun updateContent(id: UUID, content: Map<String, Any?>): Boolean =
		DocBlocks.update({ DocBlocks.id eq id }) {
			it[DocBlocks.content] = json.writeValueAsString(content)
			it[updatedAt] = OffsetDateTime.now()
		} > 0

	fun delete(id: UUID): Boolean = DocBlocks.deleteWhere { DocBlocks.id eq id } > 0

	/** Rewrites the page's positions as 0..n-1, in the order given. */
	fun setOrder(orderedIds: List<UUID>) {
		orderedIds.forEachIndexed { index, id ->
			DocBlocks.update({ DocBlocks.id eq id }) { it[position] = index }
		}
	}

	// --- backlinks -----------------------------------------------------------

	/** Replaces the whole set, the way every other relation in this codebase is written. */
	fun setTickets(blockId: UUID, ticketIds: Collection<UUID>) {
		DocBlockTickets.deleteWhere { DocBlockTickets.blockId eq blockId }
		if (ticketIds.isEmpty()) return
		DocBlockTickets.batchInsert(ticketIds.distinct()) { ticketId ->
			this[DocBlockTickets.blockId] = blockId
			this[DocBlockTickets.ticketId] = ticketId
		}
	}

	/**
	 * Every ticket any block of [pageId] mentions, once — the "Lié à" rail, derived
	 * rather than stored.
	 *
	 * Two queries and not a join: the tables carry plain UUID columns rather than
	 * Exposed `reference()`s (see `db/Tables.kt` on why the whole schema does), so
	 * `innerJoin` has no key pair to infer a condition from and there is nothing to
	 * gain by spelling one out for a page's worth of rows.
	 */
	fun ticketIdsForPage(pageId: UUID): List<UUID> {
		val blockIds = DocBlocks.select(DocBlocks.id)
			.where { DocBlocks.pageId eq pageId }
			.map { it[DocBlocks.id] }
		if (blockIds.isEmpty()) return emptyList()
		return DocBlockTickets.select(DocBlockTickets.ticketId)
			.where { DocBlockTickets.blockId inList blockIds }
			.map { it[DocBlockTickets.ticketId] }
			.distinct()
	}

	// --- mapping -------------------------------------------------------------

	/** One extra query for the whole page's backlinks rather than one per block. */
	private fun decorate(rows: List<ResultRow>): List<DocBlock> {
		if (rows.isEmpty()) return emptyList()
		val ids = rows.map { it[DocBlocks.id] }
		val ticketsByBlock = DocBlockTickets.selectAll()
			.where { DocBlockTickets.blockId inList ids }
			.groupBy({ it[DocBlockTickets.blockId] }, { it[DocBlockTickets.ticketId] })
		return rows.map { row ->
			DocBlock(
				id = row[DocBlocks.id],
				pageId = row[DocBlocks.pageId],
				position = row[DocBlocks.position],
				kind = DocBlockKind.from(row[DocBlocks.kind]),
				content = readContent(row[DocBlocks.content]),
				ticketIds = ticketsByBlock[row[DocBlocks.id]].orEmpty(),
			)
		}
	}

	private fun readContent(raw: String): Map<String, Any?> = decodeObject(json, raw)
}

/**
 * A jsonb object as a map.
 *
 * The cast is unchecked because Jackson erases to `Map<*, *>`, and it is safe for the
 * only value that can be here: the column is written by [DocBlockRepository.insert]
 * from a map, and `V9` seeds the templates with json objects. A malformed value would
 * throw inside Jackson before reaching the cast.
 */
@Suppress("UNCHECKED_CAST")
internal fun decodeObject(json: ObjectMapper, raw: String): Map<String, Any?> =
	json.readValue(raw, Map::class.java) as Map<String, Any?>

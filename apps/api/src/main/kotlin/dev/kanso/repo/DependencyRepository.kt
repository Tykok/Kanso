package dev.kanso.repo

import dev.kanso.db.TicketLinks
import dev.kanso.db.Tickets
import dev.kanso.domain.TicketLinkType
import dev.kanso.schedule.Edge
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The scheduler's door onto the ticket graph, and the only thing in Kanso that turns a
 * row into an [Edge].
 *
 * Since `V33` the table it reads holds three kinds of edge and exactly one of them is a
 * schedule. Every read here therefore says `type = 'blocks'`, and that is deliberately
 * *here* rather than in the call sites downstream: `Cascade`, `CriticalPath`,
 * `TimelineService` and `MyStatsService.blocked` all take a `Collection<Edge>` and have
 * no way to tell one kind from another, so a filter each of them was responsible for
 * applying would be a filter one of them eventually forgot. What that costs is not an
 * exception — a `relates` edge joins two components that have nothing to do with each
 * other, and every date derived from the merged component comes out wrong by a few days,
 * silently. `TicketLinkTest` holds this boundary from the outside.
 *
 * The navigable graph — all three types, for a ticket page — is [TicketLinkRepository].
 */
@Repository
class DependencyRepository(private val jdbc: JdbcClient) {

	private val blocks = TicketLinkType.BLOCKS.wire

	fun insert(predecessorId: UUID, successorId: UUID) {
		TicketLinks.insert {
			it[TicketLinks.fromTicketId] = predecessorId
			it[TicketLinks.toTicketId] = successorId
			it[type] = TicketLinkType.BLOCKS.wire
			it[createdAt] = OffsetDateTime.now()
		}
	}

	fun delete(predecessorId: UUID, successorId: UUID): Boolean =
		TicketLinks.deleteWhere {
			(TicketLinks.fromTicketId eq predecessorId) and
				(TicketLinks.toTicketId eq successorId) and
				(TicketLinks.type eq blocks)
		} > 0

	fun exists(predecessorId: UUID, successorId: UUID): Boolean =
		TicketLinks.selectAll().where {
			(TicketLinks.fromTicketId eq predecessorId) and
				(TicketLinks.toTicketId eq successorId) and
				(TicketLinks.type eq blocks)
		}.limit(1).any()

	/** Every edge with at least one end inside [ticketIds]. */
	fun edgesTouching(ticketIds: Collection<UUID>): List<Edge> {
		if (ticketIds.isEmpty()) return emptyList()
		return TicketLinks.selectAll().where {
			((TicketLinks.fromTicketId inList ticketIds) or
				(TicketLinks.toTicketId inList ticketIds)) and
				(TicketLinks.type eq blocks)
		}.map { Edge(it[TicketLinks.fromTicketId], it[TicketLinks.toTicketId]) }
	}

	/**
	 * The predecessors of [ticketId] with their mirror pages, keyed by ticket id, and
	 * null where that predecessor has not reached Notion yet.
	 *
	 * The nulls are handed to the caller rather than filtered out here. Notion replaces
	 * a relation array wholesale, so writing only the arrows that happen to have pages
	 * would drop the rest permanently — nothing re-pushes a successor once its
	 * predecessor lands. The mapper turns a null into a deferral instead, exactly as
	 * the project and doc relations already do.
	 */
	fun predecessorPageIds(ticketId: UUID): Map<UUID, String?> =
		TicketLinks
			.join(Tickets, JoinType.INNER, TicketLinks.fromTicketId, Tickets.id)
			.select(Tickets.id, Tickets.notionPageId)
			.where { (TicketLinks.toTicketId eq ticketId) and (TicketLinks.type eq blocks) }
			.associate { it[Tickets.id] to it[Tickets.notionPageId] }

	/**
	 * The weakly connected components containing [ticketIds] — the walk ignores the
	 * direction of the arrows, which is why the recursive term flips the edge.
	 *
	 * Raw SQL for the reason the other recursive walks are: Exposed has no
	 * `WITH RECURSIVE`. `UNION` rather than `UNION ALL` is load-bearing — an
	 * undirected walk revisits every node from both ends and would not terminate.
	 *
	 * The critical path is computed over this set rather than over what the caller
	 * asked to see: anchoring on the visible scope would repaint the screen when the
	 * filter changes, on identical data.
	 */
	fun componentIds(ticketIds: Collection<UUID>): List<UUID> {
		if (ticketIds.isEmpty()) return emptyList()
		return jdbc.sql(
			"""
			WITH RECURSIVE component AS (
			    SELECT id FROM tickets WHERE id IN (:seed)
			  UNION
			    SELECT CASE WHEN d.from_ticket_id = c.id THEN d.to_ticket_id ELSE d.from_ticket_id END
			      FROM ticket_links d
			      JOIN component c ON d.from_ticket_id = c.id OR d.to_ticket_id = c.id
			     WHERE d.type = 'blocks'
			)
			SELECT id FROM component
			""".trimIndent()
		).param("seed", ticketIds.toList()).query(UUID::class.java).list().filterNotNull()
	}

	/**
	 * The first path found from [fromId] to [toId], both ends included, or null when
	 * there is none. Used to name the chain in the 409 a refused dependency produces:
	 * "cycle detected" on its own is not something anyone can act on.
	 *
	 * The `uuid[]` is read through an explicit [RowMapper] rather than
	 * `query(Array::class.java)`: `JdbcClient` sends any non-simple type to
	 * `SimplePropertyRowMapper`, which tries to construct it and cannot, because
	 * `java.sql.Array` is an interface. `getArray` on the column is the one call that
	 * does not go looking for a constructor.
	 */
	fun pathBetween(fromId: UUID, toId: UUID): List<UUID>? {
		val path = jdbc.sql(
			"""
			WITH RECURSIVE walk AS (
			    SELECT :from::uuid AS id, ARRAY[:from::uuid] AS path
			  UNION ALL
			    SELECT d.to_ticket_id, w.path || d.to_ticket_id
			      FROM ticket_links d
			      JOIN walk w ON d.from_ticket_id = w.id
			     WHERE d.type = 'blocks' AND NOT d.to_ticket_id = ANY(w.path)
			)
			SELECT path FROM walk WHERE id = :to::uuid LIMIT 1
			""".trimIndent()
		).param("from", fromId).param("to", toId)
			.query(RowMapper { rs, _ -> rs.getArray("path").array as Array<*> })
			.optional().orElse(null) ?: return null
		return path.map { it as UUID }
	}
}

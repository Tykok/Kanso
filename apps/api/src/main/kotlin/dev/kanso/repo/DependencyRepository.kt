package dev.kanso.repo

import dev.kanso.db.TicketDependencies
import dev.kanso.schedule.Edge
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DependencyRepository(private val jdbc: JdbcClient) {

	fun insert(predecessorId: UUID, successorId: UUID) {
		TicketDependencies.insert {
			it[TicketDependencies.predecessorId] = predecessorId
			it[TicketDependencies.successorId] = successorId
			it[createdAt] = OffsetDateTime.now()
		}
	}

	fun delete(predecessorId: UUID, successorId: UUID): Boolean =
		TicketDependencies.deleteWhere {
			(TicketDependencies.predecessorId eq predecessorId) and
				(TicketDependencies.successorId eq successorId)
		} > 0

	fun exists(predecessorId: UUID, successorId: UUID): Boolean =
		TicketDependencies.selectAll().where {
			(TicketDependencies.predecessorId eq predecessorId) and
				(TicketDependencies.successorId eq successorId)
		}.limit(1).any()

	/** Every edge with at least one end inside [ticketIds]. */
	fun edgesTouching(ticketIds: Collection<UUID>): List<Edge> {
		if (ticketIds.isEmpty()) return emptyList()
		return TicketDependencies.selectAll().where {
			(TicketDependencies.predecessorId inList ticketIds) or
				(TicketDependencies.successorId inList ticketIds)
		}.map { Edge(it[TicketDependencies.predecessorId], it[TicketDependencies.successorId]) }
	}

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
			    SELECT CASE WHEN d.predecessor_id = c.id THEN d.successor_id ELSE d.predecessor_id END
			      FROM ticket_dependencies d
			      JOIN component c ON d.predecessor_id = c.id OR d.successor_id = c.id
			)
			SELECT id FROM component
			""".trimIndent()
		).param("seed", ticketIds.toList()).query(UUID::class.java).list().filterNotNull()
	}

	/**
	 * True when `[predecessorId] -> [successorId]` would close a loop, i.e. when
	 * [predecessorId] is already reachable by following arrows forward from
	 * [successorId].
	 */
	fun wouldCreateCycle(predecessorId: UUID, successorId: UUID): Boolean =
		predecessorId == successorId || pathBetween(successorId, predecessorId) != null

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
			    SELECT d.successor_id, w.path || d.successor_id
			      FROM ticket_dependencies d
			      JOIN walk w ON d.predecessor_id = w.id
			     WHERE NOT d.successor_id = ANY(w.path)
			)
			SELECT path FROM walk WHERE id = :to::uuid LIMIT 1
			""".trimIndent()
		).param("from", fromId).param("to", toId)
			.query(RowMapper { rs, _ -> rs.getArray("path").array as Array<*> })
			.optional().orElse(null) ?: return null
		return path.map { it as UUID }
	}
}

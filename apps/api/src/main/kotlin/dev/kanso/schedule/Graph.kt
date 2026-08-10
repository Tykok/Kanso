package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** One finish-to-start dependency: [successorId] cannot start before [predecessorId] ends. */
data class Edge(val predecessorId: UUID, val successorId: UUID)

/**
 * One ticket as the scheduler sees it.
 *
 * A node with both bounds null is unscheduled: it takes part in the graph but has
 * nothing to move and constrains nothing. A node with one bound is a milestone of zero
 * duration sitting on the bound it has — including the shape a deadline naturally
 * takes, a due date with no start.
 */
data class Node(
	val id: UUID,
	val start: OffsetDateTime?,
	val end: OffsetDateTime?,
	val done: Boolean,
) {
	val from: OffsetDateTime? get() = start ?: end
	val to: OffsetDateTime? get() = end ?: start
	val scheduled: Boolean get() = from != null

	val duration: Duration
		get() = if (from == null || to == null) Duration.ZERO else Duration.between(from, to)
}

/** Where a node ended up. */
data class Placement(val id: UUID, val start: OffsetDateTime, val end: OffsetDateTime)

/**
 * Successors first, in an order where every node appears after all of its predecessors
 * that are themselves in [ids]. Cycles are impossible — `DependencyRepository`
 * refuses them at insert — so this always consumes the whole set.
 */
internal fun topologicalOrder(ids: Set<UUID>, edges: Collection<Edge>): List<UUID> {
	val inner = edges.filter { it.predecessorId in ids && it.successorId in ids }
	val remaining = inner.groupingBy { it.successorId }.eachCount().toMutableMap()
	val bySource = inner.groupBy { it.predecessorId }
	val ready = ArrayDeque(ids.filter { remaining.getOrDefault(it, 0) == 0 })
	val order = mutableListOf<UUID>()
	while (ready.isNotEmpty()) {
		val id = ready.removeFirst()
		order += id
		for (edge in bySource[id].orEmpty()) {
			val left = remaining.getValue(edge.successorId) - 1
			remaining[edge.successorId] = left
			if (left == 0) ready += edge.successorId
		}
	}
	check(order.size == ids.size) { "the dependency graph has a cycle, which the insert guard should have refused" }
	return order
}

/** Everything reachable from [rootId] by following arrows forward, [rootId] included. */
internal fun reachableFrom(rootId: UUID, edges: Collection<Edge>): Set<UUID> {
	val bySource = edges.groupBy { it.predecessorId }
	val seen = mutableSetOf(rootId)
	val queue = ArrayDeque(listOf(rootId))
	while (queue.isNotEmpty()) {
		for (edge in bySource[queue.removeFirst()].orEmpty()) {
			if (seen.add(edge.successorId)) queue += edge.successorId
		}
	}
	return seen
}

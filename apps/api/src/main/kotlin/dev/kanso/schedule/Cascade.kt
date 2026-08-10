package dev.kanso.schedule

import java.util.UUID

data class CascadeResult(val moved: List<Placement>, val violated: Set<Edge>)

/**
 * The forward pass: a change to one ticket pushes the successors it now overlaps.
 *
 * Three rules, and each one is a product decision rather than an optimisation:
 *
 * - **Slack is respected.** A successor that still starts after its predecessor ends
 *   does not move, and the descent stops there — a node that did not move cannot have
 *   pushed anything behind it. Without this, every micro-adjustment would creep the
 *   whole graph forward and no ticket would ever have slack, which would make the
 *   critical path meaningless.
 * - **Nothing is ever pulled backwards.** Freeing slack does not drag work into the
 *   past. Nobody expects it and nobody could undo it.
 * - **A done ticket never moves.** Its edge is reported violated instead: a plan that
 *   claims to hold when it does not is the worst outcome available here.
 */
object Cascade {

	fun apply(nodes: Collection<Node>, edges: Collection<Edge>, changedId: UUID): CascadeResult {
		val current = nodes.associateBy { it.id }.toMutableMap()
		if (changedId !in current) return CascadeResult(emptyList(), emptySet())

		// Only what sits downstream of the change is settled. Repairing a violation
		// elsewhere in the component would move tickets the person did not touch, in a
		// request they did not make.
		val downstream = reachableFrom(changedId, edges) - changedId
		val byTarget = edges.groupBy { it.successorId }
		val moved = LinkedHashMap<UUID, Placement>()
		val violated = mutableSetOf<Edge>()

		// Topological order over the whole graph, so a node is settled only once every
		// predecessor of it has been. A diamond — two chains meeting at one ticket — is
		// exactly the case that breaks if the order is the one the edges were stored in.
		for (id in topologicalOrder(current.keys, edges)) {
			if (id !in downstream) continue
			val node = current.getValue(id)
			val startsAt = node.from ?: continue

			// Every predecessor, not just the ones the change reached: the binding
			// constraint may come from a chain that did not move at all.
			val binding = byTarget[id].orEmpty()
				.mapNotNull { edge -> current[edge.predecessorId]?.to?.let { edge to it } }
				.maxByOrNull { (_, end) -> end }
				?: continue
			val (edge, requiredStart) = binding
			if (!startsAt.isBefore(requiredStart)) continue

			if (node.done) {
				violated += edge
				continue
			}

			val shifted = node.copy(start = requiredStart, end = requiredStart.plus(node.duration))
			current[id] = shifted
			moved[id] = Placement(id, requiredStart, requiredStart.plus(node.duration))
		}

		return CascadeResult(moved.values.toList(), violated)
	}
}

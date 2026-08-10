package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The backward pass. Slack is how long a ticket could be delayed without pushing the
 * end of its chain; zero slack is the critical path, negative slack is a chain that
 * overruns a deadline someone posed.
 *
 * The unit is the **weakly connected component**, not the project and not the visible
 * scope. A chain can span three projects: anchoring per project would cut it into
 * three unrelated critical paths, and anchoring on what happens to be on screen would
 * repaint identical data when the filter changes.
 *
 * A component of one ticket is excluded. So is an unscheduled one. Both would be
 * saying something about dependencies they do not have.
 */
object CriticalPath {

	/**
	 * @param deadlines the explicit end of a ticket's project, per ticket id. Absent
	 *        means the project has no posed end, or the ticket has no project — a
	 *        derived bound is not a deadline, it is a consequence.
	 */
	fun slack(
		nodes: Collection<Node>,
		edges: Collection<Edge>,
		deadlines: Map<UUID, OffsetDateTime>,
	): Map<UUID, Duration> {
		val byId = nodes.associateBy { it.id }
		val usable = edges.filter { byId[it.predecessorId]?.scheduled == true && byId[it.successorId]?.scheduled == true }
		if (usable.isEmpty()) return emptyMap()

		val result = mutableMapOf<UUID, Duration>()
		for (component in components(usable)) {
			val members = component.mapNotNull { byId[it] }.filter { it.scheduled }
			if (members.size < 2) continue

			// The end of the longest chain anchors whatever has no successor, which is
			// what keeps a chain with no deadline anywhere on it still showing a red path.
			val chainEnd = members.mapNotNull { it.to }.max()

			val inner = usable.filter { it.predecessorId in component && it.successorId in component }
			val bySource = inner.groupBy { it.predecessorId }
			val lateFinish = mutableMapOf<UUID, OffsetDateTime>()

			// Reverse topological order: a node's late finish is bounded by its
			// successors, so every successor must already be settled.
			for (id in topologicalOrder(component, inner).asReversed()) {
				val successors = bySource[id].orEmpty()
				val bound = successors
					.mapNotNull { edge -> byId[edge.successorId]?.let { lateFinish.getValue(it.id).minus(it.duration) } }
					.minOrNull() ?: chainEnd

				// A deadline binds the ticket whose project posted it, and only that
				// ticket. Taking the tightest one in the component and applying it to
				// everybody made a ticket finishing ten days inside its own project's end
				// read as late, because something upstream in a *different* project was
				// tight — a predecessor's deadline cannot constrain a successor's finish.
				//
				// It only ever tightens, never loosens: a generous deadline must not buy
				// the chain slack it does not have, or nothing would be critical.
				val own = deadlines[id]
				lateFinish[id] = if (own != null && own.isBefore(bound)) own else bound
			}

			for (node in members) {
				val end = node.to ?: continue
				result[node.id] = Duration.between(end, lateFinish.getValue(node.id))
			}
		}
		return result
	}

	/** Weakly connected components: the walk ignores the direction of the arrows. */
	private fun components(edges: Collection<Edge>): List<Set<UUID>> {
		val neighbours = mutableMapOf<UUID, MutableSet<UUID>>()
		for (edge in edges) {
			neighbours.getOrPut(edge.predecessorId) { mutableSetOf() } += edge.successorId
			neighbours.getOrPut(edge.successorId) { mutableSetOf() } += edge.predecessorId
		}

		val seen = mutableSetOf<UUID>()
		return neighbours.keys.mapNotNull { root ->
			if (!seen.add(root)) return@mapNotNull null
			val component = mutableSetOf(root)
			val queue = ArrayDeque(listOf(root))
			while (queue.isNotEmpty()) {
				for (next in neighbours[queue.removeFirst()].orEmpty()) {
					if (seen.add(next)) {
						component += next
						queue += next
					}
				}
			}
			component
		}
	}
}

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

		val byTarget = edges.groupBy { it.successorId }
		val moved = LinkedHashMap<UUID, Placement>()
		val violated = mutableSetOf<Edge>()

		// What has actually moved so far, seeded with the ticket the person edited.
		//
		// This set is what stops the descent. Reaching a node from the change is not
		// enough to consider it: a node whose predecessors all held still has no new
		// constraint on it, and settling it anyway would repair a violation that
		// predates the request — moving tickets nobody touched, in an edit nobody made.
		val dirty = mutableSetOf(changedId)

		// Topological order over the whole graph, so a node is settled only once every
		// predecessor of it has been. A diamond — two chains meeting at one ticket — is
		// exactly the case that breaks if the order is the one the edges were stored in.
		for (id in topologicalOrder(current.keys, edges)) {
			val incoming = byTarget[id].orEmpty()
			if (incoming.none { it.predecessorId in dirty }) continue

			val node = current.getValue(id)
			val startsAt = node.from ?: continue

			// Every predecessor, not just the ones that moved: the binding constraint may
			// come from a chain that held still, and settling against the mover alone
			// would leave the other edge broken.
			val ends = incoming.mapNotNull { edge -> current[edge.predecessorId]?.to?.let { edge to it } }
			val requiredStart = ends.maxOfOrNull { (_, end) -> end } ?: continue
			if (!startsAt.isBefore(requiredStart)) continue

			if (node.done) {
				// Every edge this node breaks, not only the binding one: two red arrows
				// are two facts, and drawing one of them is a plan that under-reports.
				violated += ends.filter { (_, end) -> startsAt.isBefore(end) }.map { (edge, _) -> edge }
				continue
			}

			current[id] = node.copy(start = requiredStart, end = requiredStart.plus(node.duration))
			moved[id] = Placement(id, requiredStart, requiredStart.plus(node.duration))
			dirty += id
		}

		return CascadeResult(moved.values.toList(), violated)
	}
}

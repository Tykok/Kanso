package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.Cascade
import dev.kanso.schedule.Node
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncOperation
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The database side of the cascade: load the component, hand it to the pure rule,
 * write back what moved.
 *
 * No `@Transactional` of its own — it runs inside the caller's transaction on purpose.
 * A cascade half-applied because it committed separately from the edit that caused it
 * would be worse than no cascade at all.
 */
@Service
class ScheduleService(
	private val tickets: TicketRepository,
	private val dependencies: DependencyRepository,
	private val syncJobs: SyncJobRepository,
) {

	/**
	 * Applies the cascade started by a change to [changedId]. Returns the ids it moved,
	 * each with a mirror push already queued.
	 *
	 * The component closure rather than the direct successors: a diamond in the graph
	 * has a node whose two predecessors are both in the blast radius, and settling it
	 * against only one of them would leave the other violated.
	 */
	fun cascadeFrom(changedId: UUID): List<UUID> {
		val componentIds = dependencies.componentIds(listOf(changedId))
		if (componentIds.size < 2) return emptyList()

		val edges = dependencies.edgesTouching(componentIds)
		val loaded = tickets.findAllById(componentIds).associateBy { it.id }

		val result = Cascade.apply(loaded.values.map(::toNode), edges, changedId)
		for (placement in result.moved) {
			// Only the bounds the ticket already had. A milestone carries one of the two
			// on purpose — a deadline with no start is a normal shape — and writing both
			// would turn it into a dated span nobody asked for, with a granularity flag
			// that never matched the bound it was invented for.
			val before = loaded.getValue(placement.id)
			tickets.reschedule(
				id = placement.id,
				start = placement.start.takeIf { before.start != null },
				end = placement.end.takeIf { before.due != null },
			)
			syncJobs.enqueue(SyncEntityType.TICKET, placement.id, SyncOperation.UPSERT)
		}
		return result.moved.map { it.id }
	}

	private fun toNode(ticket: Ticket) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = ticket.status == TicketStatus.DONE,
	)
}

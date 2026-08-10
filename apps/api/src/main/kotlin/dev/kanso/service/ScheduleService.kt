package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.Cascade
import dev.kanso.schedule.Node
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncOperation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The database side of the cascade: load the component, hand it to the pure rule,
 * write back what moved. It also owns the two edits that create and erase an edge.
 *
 * [cascadeFrom] carries no `@Transactional` of its own — it runs inside the caller's
 * transaction on purpose. A cascade half-applied because it committed separately from
 * the edit that caused it would be worse than no cascade at all. [link] and [unlink]
 * are the entry points a request calls directly, so they open that transaction
 * themselves: the new edge and the moves it forces commit together or not at all.
 */
@Service
class ScheduleService(
	private val tickets: TicketRepository,
	private val dependencies: DependencyRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
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

	/**
	 * Creates a finish-to-start dependency and applies it. Returns the tickets the new
	 * constraint moved.
	 *
	 * A cycle is a 409 naming the chain that would close: "cycle detected" on its own
	 * gives nobody anything to act on, and on a forty-ticket graph nobody can find the
	 * offending arrow by hand.
	 */
	@Transactional
	fun link(predecessorId: UUID, successorId: UUID): List<UUID> {
		val predecessor = tickets.findById(predecessorId)
			?: throw NotFoundException("No ticket $predecessorId")
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		if (predecessorId == successorId) {
			throw ConflictException("A ticket cannot depend on itself")
		}
		if (dependencies.exists(predecessorId, successorId)) return emptyList()

		dependencies.pathBetween(successorId, predecessorId)?.let { chain ->
			throw ConflictException(
				"That dependency would close a loop: ${chain.joinToString(" -> ")}"
			)
		}

		dependencies.insert(predecessorId, successorId)
		syncJobs.enqueue(SyncEntityType.TICKET, predecessorId, SyncOperation.UPSERT)
		syncJobs.enqueue(SyncEntityType.TICKET, successorId, SyncOperation.UPSERT)
		events.publish(
			KansoEvent.ticket(ChangeKind.UPDATED, successorId, successor.teamId, successor.projectId)
		)
		return cascadeFrom(predecessor.id)
	}

	/**
	 * Removes a dependency. Nothing moves: freeing slack is not a reason to drag work
	 * into the past, and a schedule that reshuffles itself when an arrow is erased is
	 * doing something nobody asked for.
	 */
	@Transactional
	fun unlink(predecessorId: UUID, successorId: UUID) {
		if (!dependencies.delete(predecessorId, successorId)) {
			throw NotFoundException("No dependency $predecessorId -> $successorId")
		}
		val successor = tickets.findById(successorId)
		syncJobs.enqueue(SyncEntityType.TICKET, predecessorId, SyncOperation.UPSERT)
		syncJobs.enqueue(SyncEntityType.TICKET, successorId, SyncOperation.UPSERT)
		successor?.let {
			events.publish(KansoEvent.ticket(ChangeKind.UPDATED, it.id, it.teamId, it.projectId))
		}
	}

	private fun toNode(ticket: Ticket) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = ticket.status == TicketStatus.DONE,
	)
}

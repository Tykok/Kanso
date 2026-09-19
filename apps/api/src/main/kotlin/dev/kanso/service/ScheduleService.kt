package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.schedule.Cascade
import dev.kanso.schedule.Node
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
	private val teams: TeamRepository,
	private val dependencies: DependencyRepository,
	private val outbox: OutboundJobRepository,
	private val events: EventPublisher,
	private val access: TicketAccess,
	private val statusCategories: StatusCategories,
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

		// The whole component in one read: a dependency chain crosses teams freely, so
		// "is this one done" is a question about each ticket's own team's catalogue.
		val categories = statusCategories.of(loaded.values)
		val result = Cascade.apply(loaded.values.map { toNode(it, categories) }, edges, changedId)
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
			outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, placement.id, OutboundOperation.UPSERT)
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
	fun link(actor: User, predecessorId: UUID, successorId: UUID): List<UUID> {
		val predecessor = tickets.findById(predecessorId)
			?: throw NotFoundException("No ticket $predecessorId")
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		// The successor only. Drawing an arrow *into* my ticket declares that I wait,
		// which commits nobody else; drawing one *out of* it imposes a constraint on
		// work that is not mine.
		access.require(actor, successor)
		linkRefusal(predecessorId, successorId)?.let { throw ConflictException(it) }
		// Asked again here even when the caller already asked: an arrow already stored is
		// never one [linkRefusal] would decline — no refused arrow was ever inserted — so
		// re-drawing one still ends in this idempotent exit rather than in a 409.
		if (dependencies.exists(predecessorId, successorId)) return emptyList()

		dependencies.insert(predecessorId, successorId)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, predecessorId, OutboundOperation.UPSERT)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, successorId, OutboundOperation.UPSERT)
		events.publish(
			KansoEvent.ticket(
				ChangeKind.UPDATED,
				successorId,
				successor.teamId,
				successor.projectId,
				successor.createdBy,
			)
		)
		return cascadeFrom(predecessor.id)
	}

	/**
	 * Why [link] would refuse the arrow [predecessorId] -> [successorId], or null when it
	 * would draw it. Never throws.
	 *
	 * [link]'s own two refusals rather than a second copy of them — it raises whatever this
	 * answers — so nothing can drift into calling an arrow legal that [link] would decline.
	 * It says nothing about who may draw the arrow: access is the caller's own question,
	 * asked against the successor, and [link] still asks it.
	 *
	 * Public because a caller can be obliged to *decide* rather than react. `TicketLinks`
	 * is one: it drops the one imported relation Kanso refuses and keeps the other four
	 * hundred, and it cannot learn the arrow is refused by catching [ConflictException] out
	 * of [link] — see [dev.kanso.sync.importer.ImportWriter] for what a `catch` there costs.
	 */
	@Transactional(readOnly = true)
	fun linkRefusal(predecessorId: UUID, successorId: UUID): String? {
		if (predecessorId == successorId) return "A ticket cannot depend on itself"
		return dependencies.pathBetween(successorId, predecessorId)
			?.let { chain -> "That dependency would close a loop: ${addresses(chain).joinToString(" -> ")}" }
	}

	/**
	 * The chain said the way people say it — `KAN-4 -> KAN-9` — from the ids the graph
	 * stores. Order preserved: the arrows are the sentence.
	 *
	 * Named *here* rather than by [DependencyRepository.pathBetween] returning identifiers,
	 * and the reason is where the cost lands. [linkRefusal] asks for the path on every link
	 * attempt and the answer is null on all the ones that work — the importer alone asks it
	 * four hundred times to build no sentence at all. A walk that carried names would join
	 * `tickets` and `teams` once per node it *visits*, which is the whole reachable subgraph
	 * and not the one path that comes back, to decorate rows nobody reads. So the walk stays
	 * in ids, and the two statements below are paid only by a refusal, which is rare and is
	 * already the slow path by the time anyone is reading its sentence.
	 *
	 * A draft has no identifier to print, so its id *is* its address — the same fallback
	 * [dev.kanso.mcp.tools.TicketStructure] prints on the far end of an edge, and the same
	 * one `/t/{uuid}` addresses it by. A chain naming a draft therefore still shows one
	 * UUID; inventing a placeholder for it would be worse, because a placeholder gets
	 * pasted into a comment and rots.
	 */
	private fun addresses(chain: List<UUID>): List<String> {
		val found = tickets.findAllById(chain).associateBy { it.id }
		val keys = teams.findAllById(found.values.mapNotNull { it.teamId }.toSet())
			.associate { it.id to it.key }
		return chain.map { id ->
			val ticket = found[id]
			val key = ticket?.teamId?.let { keys[it] }
			if (key != null && ticket.number != null) "$key-${ticket.number}" else id.toString()
		}
	}

	/**
	 * Removes a dependency. Nothing moves: freeing slack is not a reason to drag work
	 * into the past, and a schedule that reshuffles itself when an arrow is erased is
	 * doing something nobody asked for.
	 */
	@Transactional
	fun unlink(actor: User, predecessorId: UUID, successorId: UUID) {
		val successor = tickets.findById(successorId)
			?: throw NotFoundException("No ticket $successorId")
		access.require(actor, successor)
		if (!dependencies.delete(predecessorId, successorId)) {
			throw NotFoundException("No dependency $predecessorId -> $successorId")
		}
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, predecessorId, OutboundOperation.UPSERT)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, successorId, OutboundOperation.UPSERT)
		events.publish(
			KansoEvent.ticket(
				ChangeKind.UPDATED,
				successor.id,
				successor.teamId,
				successor.projectId,
				successor.createdBy,
			),
		)
	}

	private fun toNode(ticket: Ticket, categories: Categories) = Node(
		id = ticket.id,
		start = ticket.start?.at,
		end = ticket.due?.at,
		done = categories[ticket] == StatusCategory.COMPLETED,
	)
}

package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * How much of a parent is finished, **computed on read and never stored**.
 *
 * [total] excludes cancelled children, which is the one arithmetic decision here worth
 * arguing. A cancelled child is neither done nor outstanding: counting it as outstanding
 * makes the parent permanently unfinishable, and counting it as done claims work that
 * did not happen. Leaving it out of both is the only reading in which "5 of 5" can ever
 * mean "this parent is finished", which is the sentence the number exists to say.
 *
 * [donePoints] and [totalPoints] are null unless *every* counted child is estimated.
 * Mixing them silently weights an unestimated child as zero, so a parent would read 80 %
 * complete while a third of it had never been sized — the KAN-40 rule, which is that
 * nothing should be shown that reads like a grade it has not earned.
 */
data class SubTicketProgress(
	val total: Int,
	val done: Int,
	val donePoints: Int?,
	val totalPoints: Int?,
)

/**
 * Sub-tickets: one level of parenthood, and the number a parent shows for it.
 *
 * Its own service rather than more of [TicketService], which is already the largest file
 * in the write path. Nothing here is on the create/patch path: a parent is set by its own
 * gesture, and the progress is a read.
 */
@Service
class SubTicketService(
	private val tickets: TicketRepository,
	private val details: TicketDetails,
	private val access: TicketAccess,
	private val events: EventPublisher,
	private val statusCategories: StatusCategories,
) {

	/**
	 * Why [setParent] would refuse to hang [ticketId] under [parentId], or null when it
	 * would allow it. Never throws.
	 *
	 * Three rules, and together they cap the nest at one level and make a cycle
	 * impossible to build rather than merely refused — `V34`'s header sets out why one
	 * level is the feature. There is deliberately no recursive ancestor walk: closing
	 * `A -> B -> A` needs B to have a parent when A asks (rule 2) and needs A to have a
	 * child when B asks (rule 3), so either rule alone already refuses the second half of
	 * every loop. A walk would be a fourth rule that could disagree with these three.
	 */
	@Transactional(readOnly = true)
	fun parentRefusal(ticketId: UUID, parentId: UUID): String? {
		// 1. Also `tickets_not_own_parent_chk`, refused here so it reads as a sentence
		//    rather than as a constraint violation.
		if (ticketId == parentId) return "A ticket cannot be its own parent"

		val parent = tickets.findById(parentId) ?: return "No ticket $parentId"
		// 2. The parent must be top-level, or this would be a third level.
		if (parent.parentId != null) {
			return "$parentId is already a sub-ticket, and sub-tickets do not nest"
		}
		// 3. And the child must not already be a parent, for the same reason from the
		//    other end. Said as what it costs the reader, not as "rule 3 failed".
		if (tickets.hasChildren(ticketId)) {
			return "$ticketId has sub-tickets of its own, so it cannot become one"
		}
		return null
	}

	/**
	 * Hangs [ticketId] under [parentId], or promotes it to top level when [parentId] is
	 * null.
	 *
	 * Access is asked of the child — the ticket whose row actually changes. Being made a
	 * part of something is a change to the child, and a parent gains no field.
	 */
	@Transactional
	fun setParent(actor: User, ticketId: UUID, parentId: UUID?) {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)
		parentId?.let { parentRefusal(ticketId, it)?.let { why -> throw ConflictException(why) } }

		tickets.setParent(ticketId, parentId)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, ticketId, ticket.teamId, ticket.projectId))
		// The old parent's progress changed too, and the new one's did. Neither is stored,
		// so there is nothing to recompute — which is the whole reason it is derived.
	}

	/** The children of [ticketId], for the panel that lists them. */
	@Transactional(readOnly = true)
	fun children(actor: User, ticketId: UUID): List<TicketDetail> {
		access.requireReadable(actor, ticketId)
		return details.of(tickets.childrenOf(listOf(ticketId))[ticketId].orEmpty())
	}

	/**
	 * The progress of each of [parentIds] that actually has children.
	 *
	 * A parent with no children is **absent from the map**, not present with zeroes. "0
	 * of 0 done" is a number about nothing, and an empty bar drawn on every ordinary
	 * ticket in a list is the chart with one bar that KAN-40 went to some trouble not to
	 * draw. Absent means the caller draws nothing, which is the honest rendering of a
	 * ticket that is not a parent.
	 */
	@Transactional(readOnly = true)
	fun progress(parentIds: Collection<UUID>): Map<UUID, SubTicketProgress> =
		tickets.childrenOf(parentIds).let { byParent ->
			// One read for every team the children are in, not one per parent: a parent's
			// children can sit in another team, and a query per parent is the N+1 this
			// method's batched signature exists to avoid.
			val categories = statusCategories.of(byParent.values.flatten())
			byParent.mapValues { (_, children) -> progressOf(children, categories) }
		}.filterValues { it.total > 0 }

	/**
	 * Pure, so the arithmetic is provable without a database — and internal rather than
	 * private for exactly that reason.
	 *
	 * [categories] is a parameter rather than a service call for that same reason: since
	 * `KAN-90` what a status means is a read of the children's teams, and injecting the
	 * reader here would have put a database behind the one piece of arithmetic that was
	 * worth proving without one. A test builds a `Categories` from a literal.
	 */
	internal fun progressOf(children: List<Ticket>, categories: Categories): SubTicketProgress {
		val counted = children.filter { categories[it] != StatusCategory.CANCELED }
		val done = counted.count { categories[it] == StatusCategory.COMPLETED }
		val estimates = counted.map { it.estimate }
		val allEstimated = counted.isNotEmpty() && estimates.none { it == null }
		return SubTicketProgress(
			total = counted.size,
			done = done,
			donePoints = if (!allEstimated) null else
				counted.filter { categories[it] == StatusCategory.COMPLETED }.sumOf { it.estimate ?: 0 },
			totalPoints = if (!allEstimated) null else estimates.sumOf { it ?: 0 },
		)
	}
}

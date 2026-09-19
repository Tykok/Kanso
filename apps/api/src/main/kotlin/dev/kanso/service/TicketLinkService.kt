package dev.kanso.service

import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.TicketLink
import dev.kanso.repo.TicketLinkRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One edge of a ticket's graph, with the ticket at the other end resolved for a screen. */
data class TicketLinkView(val other: TicketDetail, val type: TicketLinkType, val outgoing: Boolean)

/**
 * Drawing and undrawing the edges of the ticket graph, all three kinds.
 *
 * `blocks` is delegated to [ScheduleService] rather than reimplemented here. It is the
 * one type with consequences beyond the row — the cycle refusal that names the chain,
 * the cascade that moves dates, two mirror pushes — and a second way of inserting one
 * would be a second way of getting all of that wrong. So this class owns exactly what
 * the other two kinds need, which is much less: no cascade, because they order nothing,
 * and no mirror push, because the Notion mirror has one relation and it is `Blocked by`.
 */
@Service
class TicketLinkService(
	private val tickets: TicketRepository,
	private val links: TicketLinkRepository,
	private val schedule: ScheduleService,
	private val details: TicketDetails,
	private val access: TicketAccess,
	private val events: EventPublisher,
) {

	@Transactional(readOnly = true)
	fun of(actor: User, ticketId: UUID): List<TicketLinkView> {
		access.requireReadable(actor, ticketId)
		val rows = links.of(ticketId)
		if (rows.isEmpty()) return emptyList()
		// The far end is filtered, not merely resolved. The graph crosses teams by
		// design, so an edge can name somebody's private draft, and a panel that printed
		// its title would be the one place a draft's existence leaks. `mapNotNull` then
		// drops the row silently rather than showing a placeholder: a reader who cannot
		// open the far end has nothing to do with the information that it exists.
		val others = details.of(tickets.findAllById(rows.map { it.otherId }).filter { access.mayRead(actor, it) })
			.associateBy { it.ticket.id }
		return rows.mapNotNull { row -> others[row.otherId]?.let { TicketLinkView(it, row.type, row.outgoing) } }
	}

	/**
	 * Draws [type] from [fromId] to [toId]. Returns the ticket ids the edge moved, which
	 * is always empty for the two kinds that are not a schedule.
	 *
	 * Access is asked of [fromId] — the ticket whose page the gesture was made on — and of
	 * one end only, which is the rule dependencies already follow because the graph
	 * deliberately crosses teams. For `blocks` [ScheduleService] asks it of the successor
	 * instead, and that difference is its own: an arrow *into* my ticket says I wait, and
	 * committing myself is mine to do.
	 */
	@Transactional
	fun link(actor: User, fromId: UUID, toId: UUID, type: TicketLinkType): List<UUID> {
		if (type == TicketLinkType.BLOCKS) return schedule.link(actor, fromId, toId)

		val from = tickets.findById(fromId) ?: throw NotFoundException("No ticket $fromId")
		tickets.findById(toId) ?: throw NotFoundException("No ticket $toId")
		access.require(actor, from)
		// Refused here as well as by `ticket_links_not_self_chk`, because a constraint
		// violation reaches the caller as a 500 and this is a 400 somebody can read.
		if (fromId == toId) throw BadRequestException("A ticket cannot ${type.wire} itself")

		if (!links.link(fromId, toId, type)) return emptyList()
		events.publish(
			KansoEvent.ticket(
				ChangeKind.UPDATED,
				fromId,
				from.teamId,
				from.projectId,
				from.createdBy,
			),
		)
		return emptyList()
	}

	@Transactional
	fun unlink(actor: User, fromId: UUID, toId: UUID, type: TicketLinkType) {
		if (type == TicketLinkType.BLOCKS) return schedule.unlink(actor, fromId, toId)

		val from = tickets.findById(fromId) ?: throw NotFoundException("No ticket $fromId")
		access.require(actor, from)
		if (!links.unlink(fromId, toId, type)) {
			throw NotFoundException("No ${type.wire} link $fromId -> $toId")
		}
		events.publish(
			KansoEvent.ticket(
				ChangeKind.UPDATED,
				fromId,
				from.teamId,
				from.projectId,
				from.createdBy,
			),
		)
	}
}

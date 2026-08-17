package dev.kanso.publik

import dev.kanso.db.PublicTickets
import dev.kanso.db.TicketFiles
import dev.kanso.domain.User
import dev.kanso.repo.TicketRepository
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketAccess
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The two writes behind the public surfaces: putting a ticket in the shop window, and
 * saying where a contributor should look first.
 *
 * Both are ordinary scoped writes — `TicketAccess` decides them, exactly as it decides
 * a status change — and neither is reachable without a session. That asymmetry is the
 * point of the slice: reading the roadmap needs nobody's permission, and *filling* it
 * needs the same permission as moving the ticket, because publishing somebody else's
 * work is a bigger act than moving it.
 *
 * Nothing is pushed to Notion and no event is published. The mirror has no property for
 * this flag, and inventing one would put a Kanso-only concept into a database people
 * read as a copy of their work; the realtime channel is for surfaces with a session,
 * and the roadmap has none to push to.
 */
@Service
class PublicationService(
	private val tickets: TicketRepository,
	private val access: TicketAccess,
) {

	@Transactional
	fun publish(actor: User, ticketId: UUID, public: Boolean) {
		require(actor, ticketId)
		PublicTickets.update({ PublicTickets.id eq ticketId }) { it[isPublic] = public }
	}

	/**
	 * Replaces the list wholesale rather than merging it.
	 *
	 * A maintainer editing this is re-answering "where should someone look", and the
	 * answer is the list, not the individual lines — a merge would silently keep a path
	 * that was deliberately dropped. [FilePointer.position] comes from the order given.
	 */
	@Transactional
	fun whereToLook(actor: User, ticketId: UUID, files: List<FilePointer>) {
		require(actor, ticketId)
		TicketFiles.deleteWhere { TicketFiles.ticketId eq ticketId }
		files.forEachIndexed { index, pointer ->
			TicketFiles.insert {
				it[TicketFiles.ticketId] = ticketId
				it[path] = pointer.path
				it[note] = pointer.note
				it[position] = index
			}
		}
	}

	private fun require(actor: User, ticketId: UUID) {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)
	}
}

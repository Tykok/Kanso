package dev.kanso.repo

import dev.kanso.db.TicketLinks
import dev.kanso.domain.TicketLinkType
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One edge as a ticket page reads it: the ticket at the *other* end, what the edge
 * means, and which way round it is.
 *
 * [outgoing] is what turns one row into the two sentences a reader needs — "blocks X"
 * against "blocked by X", "duplicates X" against "duplicated by X". For a symmetric
 * type it says nothing worth reading, and [TicketLinkType.symmetric] is how a caller
 * knows not to render it.
 */
data class TicketLink(val otherId: UUID, val type: TicketLinkType, val outgoing: Boolean)

/**
 * The navigable ticket graph — every kind of edge, read for a screen.
 *
 * Deliberately not the same door as [DependencyRepository], which reads `blocks` and
 * nothing else for the scheduler. One repository serving both would mean the scheduler's
 * "only blocking edges" rule living in a `filter` at each of its call sites, and the day
 * one of them forgot, the critical path would move by a few days rather than fail — the
 * failure mode the ticket named. Two doors makes it structural instead: nothing here
 * returns a [dev.kanso.schedule.Edge], so no `relates` row has a route into the graph.
 *
 * This class is also the only place that knows `relates` is stored canonically. Both
 * halves of that rule live here — [canonical] on the way in, the both-columns match on
 * the way out — because a caller that had to remember either would eventually not.
 */
@Repository
class TicketLinkRepository {

	/**
	 * The pair in the order the row must be stored in.
	 *
	 * A symmetric edge is one fact, so `ticket_links_relates_canonical_chk` insists on
	 * the smaller uuid first and the primary key then means "one relates edge per pair"
	 * rather than "per pair per direction". Nothing is lost by sorting, because for a
	 * symmetric type there is no direction to lose. A directed type is left exactly as
	 * the caller gave it.
	 *
	 * Compared as **strings**, and that is not a shortcut. `UUID.compareTo` in Java reads
	 * the two halves as *signed* longs, so it orders any uuid whose 64th bit is set below
	 * one whose is not; Postgres compares the `uuid` type as sixteen *unsigned* bytes. The
	 * two disagree on a good half of all pairs, and the disagreement is not academic —
	 * the first version of this method used `<` on the uuids and the CHECK rejected its
	 * own writes, on roughly every other pair, for no reason a caller could have guessed.
	 * The canonical hex form sorts exactly as Postgres's `memcmp` does, because the
	 * dashes sit at fixed positions and `'0'..'9' < 'a'..'f'` in ASCII is the same order
	 * as the nibbles they spell.
	 */
	private fun canonical(fromId: UUID, toId: UUID, type: TicketLinkType): Pair<UUID, UUID> =
		if (type.symmetric && fromId.toString() > toId.toString()) toId to fromId else fromId to toId

	/**
	 * Draws the edge, or reports that it was already there.
	 *
	 * Returns false rather than throwing on a repeat: drawing a link is an idempotent
	 * gesture from a screen, and the second press of it — two tabs, a double click — has
	 * nothing to tell the person who made it. The refusals that *are* worth reporting
	 * (a self-link, a cycle among `blocks`) belong upstream, where they can name what
	 * they refused.
	 */
	fun link(fromId: UUID, toId: UUID, type: TicketLinkType): Boolean {
		val (from, to) = canonical(fromId, toId, type)
		if (exists(from, to, type)) return false
		TicketLinks.insert {
			it[fromTicketId] = from
			it[toTicketId] = to
			it[TicketLinks.type] = type.wire
			it[createdAt] = OffsetDateTime.now()
		}
		return true
	}

	fun unlink(fromId: UUID, toId: UUID, type: TicketLinkType): Boolean {
		val (from, to) = canonical(fromId, toId, type)
		return TicketLinks.deleteWhere {
			(fromTicketId eq from) and (toTicketId eq to) and (TicketLinks.type eq type.wire)
		} > 0
	}

	fun exists(fromId: UUID, toId: UUID, type: TicketLinkType): Boolean {
		val (from, to) = canonical(fromId, toId, type)
		return TicketLinks.selectAll().where {
			(TicketLinks.fromTicketId eq from) and
				(TicketLinks.toTicketId eq to) and
				(TicketLinks.type eq type.wire)
		}.limit(1).any()
	}

	/**
	 * Every edge touching [ticketId], from both columns.
	 *
	 * Both columns for two different reasons, and it matters that they are different. A
	 * directed edge is genuinely two facts about two tickets — the row where this ticket
	 * is `from` says "blocks", the row where it is `to` says "blocked by" — and a page
	 * that read one column would silently be half a panel. A symmetric edge is one fact
	 * that could be stored either way round, so a `relates` query that matched one column
	 * would find it from one end of the pair and not the other, depending on how two
	 * random uuids happened to sort.
	 */
	fun of(ticketId: UUID): List<TicketLink> =
		TicketLinks.selectAll().where {
			(TicketLinks.fromTicketId eq ticketId) or (TicketLinks.toTicketId eq ticketId)
		}.map { row ->
			val outgoing = row[TicketLinks.fromTicketId] == ticketId
			TicketLink(
				otherId = if (outgoing) row[TicketLinks.toTicketId] else row[TicketLinks.fromTicketId],
				type = TicketLinkType.from(row[TicketLinks.type]),
				outgoing = outgoing,
			)
		}
}

package dev.kanso.service

import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketLinkRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.TriageRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The four keys, as a closed vocabulary. Mirrors `triage_decisions_decision_chk`.
 *
 * They are decisions, not statuses: `backlogged` and `closed` both move the ticket's
 * status, and `accepted` moves it into a cycle, but what is recorded is the ruling
 * somebody made rather than its consequence — which is what makes the trace readable
 * after the status has moved on again.
 */
enum class TriageDecision(override val wire: String) : Wire {
	ACCEPTED("accepted"), BACKLOGGED("backlogged"), DUPLICATE("duplicate"), CLOSED("closed");

	companion object {
		fun from(raw: String): TriageDecision = parse(entries.toTypedArray(), raw)
	}
}

/**
 * [total] is the whole queue; [items] is the page of it the screen holds. The drawing
 * lists five rows under the words "7 tickets", so the two are different numbers on
 * purpose.
 */
data class TriageQueue(val total: Int, val items: List<TicketDetail>)

data class TriageRuling(
	val ticket: TicketDetail,
	val decision: TriageDecision,
	val duplicateOf: TicketDetail?,
	val decidedBy: User?,
	val decidedAt: OffsetDateTime,
)

/** [similarity] is a whole percent, because the drawing prints "68 %". */
data class SimilarTicket(val detail: TicketDetail, val similarity: Int)

/**
 * Triage: the gate between "somebody wrote this down" and "the team took it on".
 *
 * The queue is defined by what has *not* been decided, so there is no flag anywhere that
 * a new way of creating a ticket could forget to set. The cost is that every ticket
 * created outside a cycle appears here until somebody rules on it, which is the honest
 * reading of a gate: work nobody has accepted is work nobody has accepted.
 */
@Service
class TriageService(
	private val triage: TriageRepository,
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
	private val users: UserRepository,
	private val cycles: CycleService,
	private val ticketService: TicketService,
	private val details: TicketDetails,
	private val links: TicketLinkRepository,
) {

	@Transactional(readOnly = true)
	fun queue(teamId: UUID, limit: Int = 50): TriageQueue {
		val teamIds = teams.descendantIds(teamId)
		val found = triage.queue(teamIds, limit)
		return TriageQueue(total = triage.countQueued(teamIds), items = details.of(found))
	}

	@Transactional(readOnly = true)
	fun decisions(teamId: UUID, limit: Int = 100): List<TriageRuling> {
		val rows = triage.decisionsFor(teams.descendantIds(teamId), limit)
		if (rows.isEmpty()) return emptyList()
		val subjects = details.of(tickets.findAllById(rows.map { it.ticketId } + rows.mapNotNull { it.duplicateOf }))
			.associateBy { it.ticket.id }
		val actors = users.findAllById(rows.mapNotNull { it.decidedBy }).associateBy { it.id }
		return rows.mapNotNull { row ->
			subjects[row.ticketId]?.let { subject ->
				TriageRuling(
					ticket = subject,
					decision = TriageDecision.from(row.decision),
					duplicateOf = row.duplicateOf?.let(subjects::get),
					decidedBy = row.decidedBy?.let(actors::get),
					decidedAt = row.decidedAt,
				)
			}
		}
	}

	/**
	 * One ruling, and the consequence that goes with it.
	 *
	 * Every consequence goes through [TicketService.patch] rather than writing the status
	 * column here: the mirror push, the activity the team sees and the dependency cascade
	 * all hang off that one method, and a triage decision that skipped it would be the
	 * only status change in Kanso the mirror never learns about.
	 */
	@Transactional
	fun decide(
		actor: User,
		ticketId: UUID,
		decision: TriageDecision,
		duplicateOf: UUID?,
	): TriageRuling {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		triage.findDecision(ticketId)?.let {
			throw ConflictException("That ticket was already triaged as ${it.decision}")
		}
		if ((decision == TriageDecision.DUPLICATE) != (duplicateOf != null)) {
			throw BadRequestException(
				"A duplicate names the ticket it duplicates, and no other decision does",
			)
		}
		duplicateOf?.let {
			if (it == ticketId) throw BadRequestException("A ticket cannot duplicate itself")
			if (tickets.findById(it) == null) throw BadRequestException("No ticket $it")
		}

		// Access is checked by `patch` for the three decisions that move the ticket. The
		// fourth, `accepted`, is checked by `addTickets` on both the cycle and the ticket.
		when (decision) {
			TriageDecision.ACCEPTED -> {
				// A cycle belongs to a team, so a ticket with none cannot be accepted into
				// one. Unreachable from the queue — `TriageRepository` scopes by `team_id`,
				// so a draft is never in it — and said here anyway, because the alternative
				// is this reading as "no cycle in progress" for a ticket that could never
				// have had one.
				val teamId = ticket.teamId
					?: throw ConflictException(
						"That ticket belongs to no team, so there is no cycle to accept it into",
					)
				val cycle = cycles.findActive(teamId)
					?: throw ConflictException(
						"That team has no cycle in progress to accept into",
					)
				cycles.addTickets(actor, cycle.id, listOf(ticketId))
			}
			TriageDecision.BACKLOGGED ->
				ticketService.patch(actor, ticketId, TicketPatch(status = TicketStatus.BACKLOG))
			// Both end the ticket. They differ in the record, not the outcome: "marked
			// duplicate" points somewhere, "closed without action" does not, and a reader
			// six weeks later needs to know which one happened.
			TriageDecision.DUPLICATE, TriageDecision.CLOSED ->
				ticketService.patch(actor, ticketId, TicketPatch(status = TicketStatus.CANCELED))
		}

		// The ruling also draws the link, so that "what does this duplicate?" has one
		// answer whoever asked the question. `triage_decisions` stays the record of the
		// *decision* — who ruled, when, and why this ticket is cancelled — and
		// `ticket_links` is the current relation, which is what a ticket page navigates.
		// Without this, a ticket cancelled at the gate would show no duplicate link at
		// all while a hand-drawn one three weeks later would, and a reader would have to
		// know which route retired it to know where to look.
		//
		// One way, deliberately: the ruling draws a link, a link does not rule. Drawing
		// one by hand cancels nothing — see `V33`'s header — because an editing gesture
		// must not destroy work, and undrawing it would then have to restore a status
		// nothing recorded.
		duplicateOf?.let { links.link(ticketId, it, TicketLinkType.DUPLICATES) }

		val row = triage.insertDecision(ticketId, decision.wire, duplicateOf, actor.id)
		val subject = details.of(listOfNotNull(tickets.findById(ticketId))).single()
		return TriageRuling(
			ticket = subject,
			decision = decision,
			duplicateOf = duplicateOf?.let { id -> details.of(listOfNotNull(tickets.findById(id))).firstOrNull() },
			decidedBy = actor,
			decidedAt = row.decidedAt,
		)
	}

	/**
	 * What this ticket reads like, scored by trigram similarity on the title.
	 *
	 * Not a model and not a stored score: the comparison is between two titles as they
	 * are right now, and a column would be stale the first time either was renamed. The
	 * search is scoped to the ticket's own team and its descendants — a possible duplicate
	 * in another team's board is not something the person triaging can act on.
	 */
	@Transactional(readOnly = true)
	fun similar(ticketId: UUID, limit: Int = 5): List<SimilarTicket> {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		// The search is scoped to a team's board, so a ticket with no team has no board to
		// look for a duplicate on. Empty rather than "every team": widening the scope here
		// would show the author of a private draft a slice of work they may not otherwise
		// see, which is a disclosure dressed as a suggestion.
		val teamId = ticket.teamId ?: return emptyList()
		val scored = triage.similarTitles(
			ticketId = ticketId,
			title = ticket.title,
			teamIds = teams.descendantIds(teamId),
			limit = limit,
		)
		if (scored.isEmpty()) return emptyList()
		val byId = details.of(tickets.findAllById(scored.map { it.ticketId })).associateBy { it.ticket.id }
		return scored.mapNotNull { row -> byId[row.ticketId]?.let { SimilarTicket(it, row.similarity) } }
	}
}

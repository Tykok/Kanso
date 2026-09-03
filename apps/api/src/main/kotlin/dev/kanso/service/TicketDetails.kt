package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.repo.CustomFieldRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Turns ticket rows into the shape every screen renders — `KAN-142` and its relations.
 *
 * [TicketService] has a private `decorate` doing exactly this, and four organising
 * services need the same three queries. Copied four times it would be four places to
 * fix the day a ticket grows a fifth relation, so it is one collaborator instead. It
 * deliberately does not live on [TicketService]: the cycle, view, triage and workload
 * services have no other reason to depend on the whole write path.
 */
@Service
class TicketDetails(
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
	private val fields: CustomFieldRepository,
) {

	/**
	 * One extra query per relation for the whole page, instead of two per row.
	 *
	 * `V32` made this the fourth relation and cost the list exactly one more statement — not
	 * one per ticket and not one per ticket per field, which is the shape a custom-field
	 * feature arrives in when the values are fetched from wherever they are rendered.
	 * `CustomFieldRepository.valuesFor` is keyed by ticket id and joins nothing, because a
	 * jsonb scalar needs no definition to be decoded; the names are only wanted where a
	 * value is *written* or printed with a label, and both of those hold the definitions
	 * already.
	 *
	 * So a page of 200 tickets is four statements plus the one that found them, whether the
	 * team has defined no fields or thirty.
	 */
	@Transactional(readOnly = true)
	fun of(found: List<Ticket>): List<TicketDetail> {
		if (found.isEmpty()) return emptyList()
		val ids = found.map { it.id }
		val keys = teams.findAllById(found.mapNotNull { it.teamId }.toSet()).associate { it.id to it.key }
		val assignees = tickets.assigneeIdsFor(ids)
		val docsByTicket = tickets.docIdsFor(ids)
		val fieldValues = fields.valuesFor(ids)
		return found.map {
			TicketDetail(
				ticket = it,
				// Null for a ticket with no team, which is what makes its identifier null.
				teamKey = it.teamId?.let { teamId -> keys[teamId] ?: "?" },
				assigneeIds = assignees[it.id].orEmpty(),
				docIds = docsByTicket[it.id].orEmpty(),
				customFields = fieldValues[it.id].orEmpty(),
			)
		}
	}
}

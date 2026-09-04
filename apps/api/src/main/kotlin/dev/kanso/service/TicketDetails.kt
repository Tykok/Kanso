package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.github.GithubRepository
import dev.kanso.repo.CustomFieldRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
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
	private val github: GithubRepository,
	private val users: UserRepository,
) {

	/**
	 * One extra query per relation for the whole page, instead of two per row.
	 *
	 * `V35` made this the fourth relation and cost the list exactly one more statement — not
	 * one per ticket and not one per ticket per field, which is the shape a custom-field
	 * feature arrives in when the values are fetched from wherever they are rendered.
	 * `CustomFieldRepository.valuesFor` is keyed by ticket id and joins nothing, because a
	 * jsonb scalar needs no definition to be decoded; the names are only wanted where a
	 * value is *written* or printed with a label, and both of those hold the definitions
	 * already.
	 *
	 * `V36` made pull requests the fifth, on the same terms and for the same reason it is
	 * batched here rather than fetched where it is drawn: a board column asking "does this
	 * ticket have an open pull request" per card is 200 requests for a fact one statement
	 * answers. `GithubRepository.forTickets` returns nothing at all for an instance with no
	 * GitHub App, which is the common case and costs one query that matches no rows.
	 *
	 * So a page of 200 tickets is five statements plus the one that found them, whether the
	 * team has defined no fields or thirty.
	 *
	 * `KAN-74` adds at most a sixth, and only when it would find something: the authors of
	 * the page's pull requests who have linked their GitHub account. The `if` is not a
	 * micro-optimisation — it is what keeps the promise above true for the instances that
	 * have no GitHub App and for the far larger number whose pull request authors have
	 * simply never consented. One query for a page's people, the shape
	 * `ActivityService.forEntity` already uses for a feed's actors, because a page's authors
	 * repeat and a query per row would ask about the same five people thirty times.
	 */
	@Transactional(readOnly = true)
	fun of(found: List<Ticket>): List<TicketDetail> {
		if (found.isEmpty()) return emptyList()
		val ids = found.map { it.id }
		val keys = teams.findAllById(found.mapNotNull { it.teamId }.toSet()).associate { it.id to it.key }
		val assignees = tickets.assigneeIdsFor(ids)
		val docsByTicket = tickets.docIdsFor(ids)
		val fieldValues = fields.valuesFor(ids)
		val pullRequests = github.forTickets(ids)
		val authorIds = pullRequests.values.flatten().mapNotNull { it.authorUserId }.toSet()
		val authors = if (authorIds.isEmpty()) {
			emptyMap()
		} else {
			users.findAllById(authorIds).associateBy { it.id }
		}
		return found.map {
			TicketDetail(
				ticket = it,
				// Null for a ticket with no team, which is what makes its identifier null.
				teamKey = it.teamId?.let { teamId -> keys[teamId] ?: "?" },
				assigneeIds = assignees[it.id].orEmpty(),
				docIds = docsByTicket[it.id].orEmpty(),
				customFields = fieldValues[it.id].orEmpty(),
				pullRequests = pullRequests[it.id].orEmpty(),
				// The whole page's authors on every detail rather than a per-ticket slice:
				// the map is read by user id, so a ticket looking up an author it does not
				// have simply does not look, and slicing it would cost a pass per ticket to
				// save nothing a reader can measure.
				pullRequestAuthors = authors,
			)
		}
	}
}

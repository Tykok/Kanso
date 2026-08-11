package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Who may move a ticket.
 *
 * A service of its own rather than a method on [TicketService], because
 * [ScheduleService] asks the same question and a mutual dependency between the two
 * would be the wrong way to share three rules. [TimelineService] asks it too, in bulk.
 */
@Service
class TicketAccess(private val teams: TeamRepository) {

	@Transactional(readOnly = true)
	fun mayEdit(actor: User, ticket: Ticket): Boolean = mayEditTeam(actor, ticket.teamId)

	@Transactional(readOnly = true)
	fun require(actor: User, ticket: Ticket) {
		if (mayEdit(actor, ticket)) return
		throw AccessDeniedException("${nameOf(ticket.teamId)} is not one of your teams")
	}

	/** The destination side of a team move, which has no ticket row of its own yet. */
	@Transactional(readOnly = true)
	fun requireTeam(actor: User, teamId: UUID) {
		if (mayEditTeam(actor, teamId)) return
		throw AccessDeniedException("${nameOf(teamId)} is not one of your teams")
	}

	/**
	 * The subset of [teamIds] this actor may edit, in one pass.
	 *
	 * A timeline response carries up to `SCOPE_LIMIT` tickets, and asking per ticket
	 * would be two thousand ancestor walks. Per distinct team it is a few dozen.
	 */
	@Transactional(readOnly = true)
	fun editableTeams(actor: User, teamIds: Set<UUID>): Set<UUID> {
		if (teamIds.isEmpty()) return emptySet()
		if (actor.instanceRole.canConfigureInstance) return teamIds
		val mine = teams.teamIdsFor(actor.id).toSet()
		return teamIds.filterTo(mutableSetOf()) { id -> claimedBy(mine, id) }
	}

	private fun mayEditTeam(actor: User, teamId: UUID): Boolean {
		if (actor.instanceRole.canConfigureInstance) return true
		return claimedBy(teams.teamIdsFor(actor.id).toSet(), teamId)
	}

	/**
	 * The three-part rule, in the order the spec states it.
	 *
	 * The empty-team clause is the migration guarantee, not a convenience: every
	 * instance running today has an empty `team_members`, and without it deploying this
	 * locks every board behind a 403 whose only cure is a SQL prompt. An unclaimed team
	 * is an open team.
	 *
	 * Ancestry runs downwards only. A member of Product may move work in Product /
	 * Mobile; the reverse would make joining the smallest team in the instance a way to
	 * reach the largest.
	 */
	private fun claimedBy(actorTeamIds: Set<UUID>, teamId: UUID): Boolean {
		if (teamId in actorTeamIds) return true
		if (teams.members(teamId).isEmpty()) return true
		return teams.ancestorIds(teamId).any { it in actorTeamIds }
	}

	private fun nameOf(teamId: UUID): String = teams.findById(teamId)?.name ?: "That team"
}

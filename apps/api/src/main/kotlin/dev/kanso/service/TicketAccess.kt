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
	fun mayEdit(actor: User, ticket: Ticket): Boolean =
		ticket.teamId?.let { mayEditTeam(actor, it) } ?: mayEditDraft(actor, ticket)

	/**
	 * A ticket outside every team is outside the only boundary this product has.
	 *
	 * The three-part rule below cannot be asked: there is no team, so no membership, no
	 * ancestors, and — the trap — no chain to be silent, which means [claimedBy]'s
	 * open-chain clause would return true and hand every draft in the instance to
	 * everybody. That clause exists to keep an instance with an empty `team_members` usable;
	 * it is an argument about *unclaimed teams*, and a draft is not one.
	 *
	 * So the draft belongs to whoever wrote it, plus the instance admins. Strictly narrower
	 * than anything the team rule grants, which is the right direction for a state that is
	 * new: nothing that was reachable yesterday becomes unreachable, and nothing becomes
	 * reachable that was not.
	 *
	 * A null [Ticket.createdBy] — every row older than `V20`, and any whose author's account
	 * was closed — resolves to admins only. Inventing an owner for a row nobody signed would
	 * be handing out rights that were never given.
	 */
	private fun mayEditDraft(actor: User, ticket: Ticket): Boolean =
		actor.instanceRole.canConfigureInstance || (ticket.createdBy != null && ticket.createdBy == actor.id)

	/**
	 * Whether this ticket is one the actor may even be told about.
	 *
	 * Reads are otherwise open across teams — a ticket's *team* has never gated seeing it,
	 * only editing it — and this does not change that. It answers for the one case the team
	 * rule cannot: a draft is private to its author, so it is the only kind of ticket a
	 * reader can be refused.
	 */
	@Transactional(readOnly = true)
	fun mayRead(actor: User, ticket: Ticket): Boolean = ticket.teamId != null || mayEditDraft(actor, ticket)

	@Transactional(readOnly = true)
	fun require(actor: User, ticket: Ticket) {
		if (mayEdit(actor, ticket)) return
		val teamId = ticket.teamId
			?: throw AccessDeniedException("That ticket belongs to no team, and you did not write it")
		throw AccessDeniedException("${nameOf(teamId)} is not one of your teams")
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
	 * would be two thousand ancestor walks, each followed by a membership check per
	 * team in it. So every chain is gathered first, the membership of the whole lot is
	 * asked in one [TeamRepository.teamsWithMembers] call, and only then does the rule
	 * run — once per distinct team, against a prefetch it never has to go back for.
	 */
	@Transactional(readOnly = true)
	fun editableTeams(actor: User, teamIds: Set<UUID>): Set<UUID> {
		if (teamIds.isEmpty()) return emptySet()
		if (actor.instanceRole.canConfigureInstance) return teamIds
		val mine = teams.teamIdsFor(actor.id).toSet()
		val chains = teamIds.associateWith { teams.ancestorIds(it) }
		val everyTeamInPlay = chains.values.flatten().toSet() + teamIds
		val withMembers = teams.teamsWithMembers(everyTeamInPlay)
		return teamIds.filterTo(mutableSetOf()) { id -> claimedBy(mine, id, chains.getValue(id), withMembers) }
	}

	private fun mayEditTeam(actor: User, teamId: UUID): Boolean {
		if (actor.instanceRole.canConfigureInstance) return true
		val ancestors = teams.ancestorIds(teamId)
		val withMembers = teams.teamsWithMembers(ancestors + teamId)
		return claimedBy(teams.teamIdsFor(actor.id).toSet(), teamId, ancestors, withMembers)
	}

	/**
	 * The three-part rule, in the order the spec states it.
	 *
	 * [ancestors] is the chain above [teamId], nearest first, excluding [teamId] itself
	 * — the caller's own [TeamRepository.ancestorIds]. [withMembers] is the subset of
	 * `{teamId} ∪ ancestors` that [TeamRepository.teamsWithMembers] found holding a row;
	 * both are prefetched so this function never queries on its own.
	 *
	 * The open-chain clause exists because deploying against an empty `team_members`
	 * would otherwise 403 every board, with no cure short of a SQL prompt — every
	 * instance running today has exactly that empty table. But it is not a state
	 * instances leave behind — `TeamService.create` never enrols its creator, so an
	 * empty team is the state every team is *born* into, and stays in until somebody
	 * remembers to invite people. A single populated ancestor is enough to say somebody
	 * has claimed the work; nothing short of the whole chain being silent should open
	 * the door. That is why the clause walks every team between [teamId] and the root,
	 * not just [teamId] itself: a sub-team created under a populated parent is governed
	 * from the instant it exists, and only a chain that is unclaimed all the way up
	 * stays open — which a bare root with no members always is.
	 *
	 * Ancestry runs downwards only. A member of Product may move work in Product /
	 * Mobile; the reverse would make joining the smallest team in the instance a way to
	 * reach the largest.
	 */
	private fun claimedBy(
		actorTeamIds: Set<UUID>,
		teamId: UUID,
		ancestors: List<UUID>,
		withMembers: Set<UUID>,
	): Boolean {
		if (teamId in actorTeamIds) return true
		if (ancestors.any { it in actorTeamIds }) return true
		return (ancestors + teamId).none { it in withMembers }
	}

	private fun nameOf(teamId: UUID): String = teams.findById(teamId)?.name ?: "That team"
}

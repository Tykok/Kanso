package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
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
 *
 * There are two gates here and they are asked in this order: the seat, then the team. A
 * `VIEWER` fails the first whatever the second would have said, and the sentence they are
 * given says so — see [requireSeatThatWrites]. Everything downstream of this file inherits
 * that for free: comments, labels, cycles, saved views, bulk edits, dependencies, triage,
 * publication, doc folders, pages and blocks all reach a write through [require] or
 * [requireTeam], so none of them needed a clause of their own.
 */
@Service
class TicketAccess(
	private val teams: TeamRepository,
	private val tickets: TicketRepository,
) {

	/**
	 * Refuses a reader who may not see this ticket, the way a missing one is refused.
	 *
	 * A draft belongs to whoever wrote it until a team claims it, and [mayRead] says so —
	 * but only [TicketService.get] and the drafts list asked. The four reads that hang off
	 * a ticket id took no actor at all, so a draft's comments, its feed, its labels and its
	 * duration answered anybody holding the id.
	 *
	 * `NotFoundException`, with the sentence a genuinely missing row gets, and not
	 * `AccessDeniedException`: a 403 tells somebody walking UUIDs that the row is there,
	 * which is the one thing a private draft must not say. [TicketService.get] already
	 * answers this way; this is that answer for the readers that are not it.
	 */
	@Transactional(readOnly = true)
	fun requireReadable(actor: User, ticketId: UUID) {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		if (!mayRead(actor, ticket)) throw NotFoundException("No ticket $ticketId")
	}

	@Transactional(readOnly = true)
	fun mayEdit(actor: User, ticket: Ticket): Boolean =
		actor.instanceRole.mayWrite &&
			(ticket.teamId?.let { mayEditTeam(actor, it) } ?: mayEditDraft(actor, ticket))

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
	 *
	 * A read-only seat changes nothing here, which is the point of it: it delegates to
	 * [mayEditDraft] rather than to [mayEdit] precisely so the write rule cannot leak into
	 * the read one. A member demoted to `VIEWER` keeps seeing the drafts they wrote — they
	 * simply stop being able to change them.
	 */
	@Transactional(readOnly = true)
	fun mayRead(actor: User, ticket: Ticket): Boolean = ticket.teamId != null || mayEditDraft(actor, ticket)

	@Transactional(readOnly = true)
	fun require(actor: User, ticket: Ticket) {
		requireSeatThatWrites(actor)
		if (mayEdit(actor, ticket)) return
		val teamId = ticket.teamId
			?: throw AccessDeniedException("That ticket belongs to no team, and you did not write it")
		throw AccessDeniedException("${nameOf(teamId)} is not one of your teams")
	}

	/** The destination side of a team move, which has no ticket row of its own yet. */
	@Transactional(readOnly = true)
	fun requireTeam(actor: User, teamId: UUID) {
		requireSeatThatWrites(actor)
		if (mayEditTeam(actor, teamId)) return
		throw AccessDeniedException("${nameOf(teamId)} is not one of your teams")
	}

	/**
	 * Said before the team rule, and said differently, because it is a different refusal.
	 *
	 * "Design is not one of your teams" is a true sentence to tell a viewer and a useless
	 * one: it invites them to ask to be added to Design, which would change nothing. The
	 * seat is the reason, so the seat is what the message names — and it names it *first*,
	 * so a viewer who happens to be in the team is told the same thing as one who is not.
	 *
	 * This is the deep half of the read-only seat. `ReadOnlySeat` turns HTTP writes away at
	 * the door and catches endpoints this file has never heard of; this catches the callers
	 * that never touch HTTP — an MCP tool, the importer, a scheduled sweep — and it is what
	 * makes a viewer's agent refuse without a line of MCP-specific code, since every
	 * writing tool reaches a service that reaches here.
	 */
	private fun requireSeatThatWrites(actor: User) {
		if (actor.instanceRole.mayWrite) return
		throw AccessDeniedException(READS_NOT_WRITES)
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
		// Before the admin shortcut and before the queries: a viewer edits no team, so the
		// timeline draws every bar as fixed and asks the database nothing to find that out.
		if (!actor.instanceRole.mayWrite) return emptySet()
		if (actor.instanceRole.canConfigureInstance) return teamIds
		val mine = teams.teamIdsFor(actor.id).toSet()
		val chains = teamIds.associateWith { teams.ancestorIds(it) }
		val everyTeamInPlay = chains.values.flatten().toSet() + teamIds
		val withMembers = teams.teamsWithMembers(everyTeamInPlay)
		return teamIds.filterTo(mutableSetOf()) { id -> claimedBy(mine, id, chains.getValue(id), withMembers) }
	}

	/**
	 * The subset of [teamIds] this actor administers — by title, here or from above.
	 *
	 * [editableTeams]' sibling on the other axis. `MemberRole.ADMIN` gated nothing at all
	 * until this method existed — `TeamService.addMember` says as much where it refuses to
	 * hand the title to a read-only seat — and what it now gates is reading somebody else's
	 * figures. The ancestry is the one [claimedBy] documents and it runs in the same
	 * direction: an administrator of Product administers Product / Mobile, and never the
	 * reverse, or joining the smallest team in the instance would be a way to read the
	 * largest.
	 *
	 * **[claimedBy]'s third part is deliberately absent, and this is the whole difference
	 * between the two methods.** The open-chain clause exists so that an instance whose
	 * `team_members` is still empty is usable at all — a board nobody can move is a broken
	 * install. Applied to this question it would say the opposite of usable: every member of
	 * a fresh instance would be able to read every other member's productivity figures, on
	 * the grounds that nobody had got round to drawing the boundary yet. A boundary nobody
	 * has drawn is closed here, not open, and nobody is locked out of their own instance by
	 * that — an instance owner or admin still reads everything, and a person always reads
	 * themselves.
	 *
	 * The read-only seat is not consulted, unlike in [editableTeams]. That seat is a rule
	 * about writing and this is a read; the combination it would turn away — a viewer titled
	 * administrator of a team — cannot be created today anyway.
	 */
	@Transactional(readOnly = true)
	fun teamsLedBy(actor: User, teamIds: Set<UUID>): Set<UUID> {
		if (teamIds.isEmpty()) return emptySet()
		if (actor.instanceRole.canConfigureInstance) return teamIds
		// Before the ancestor walks: somebody titled administrator of nothing leads nothing,
		// whatever the shape of the tree above these teams.
		val titled = teams.adminTeamIdsFor(actor.id).toSet()
		if (titled.isEmpty()) return emptySet()
		return teamIds.filterTo(mutableSetOf()) { id ->
			id in titled || teams.ancestorIds(id).any { it in titled }
		}
	}

	private fun mayEditTeam(actor: User, teamId: UUID): Boolean {
		if (!actor.instanceRole.mayWrite) return false
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

	companion object {
		/**
		 * What a read-only seat is told, wherever it is turned away.
		 *
		 * One sentence and one constant, read by both doors — this file for the domain and
		 * `ReadOnlySeat` for HTTP — because a member refused by one and a member refused by
		 * the other are the same member for the same reason, and two sentences would be the
		 * first visible symptom of two rules.
		 */
		const val READS_NOT_WRITES = "Your seat on this instance reads; it does not write"
	}
}

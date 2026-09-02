package dev.kanso.service

import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Everybody who can read one person's figures, apart from that person.
 *
 * The second of the ticket's two product guard-rails, and the reason it is a response field
 * rather than a paragraph in the documentation: individual productivity stats readable
 * without the subject knowing is the kind of detail that decides whether a team adopts a
 * tool, and the only version of "we tell you" that survives a reorganisation is the one
 * computed from the same rule that grants the access.
 *
 * Both lists are *other people*. The subject is not in either, and neither is a team whose
 * only titled administrator is the subject themselves — "the administrators of Design can
 * read this" is a sentence that should not be printed to the sole administrator of Design.
 * An empty [instanceAdmins] and an empty [teams] together are the honest and common answer
 * on a small instance: nobody but you.
 */
data class ProgressReaders(
	/** Owners and admins of the instance, who read everything by [ProgressAccess]'s first rule. */
	val instanceAdmins: List<User>,
	/**
	 * The teams whose titled administrators read this page: the team the figures are scoped
	 * to, and the teams above it, since administration runs downwards.
	 */
	val teams: List<Team>,
)

/**
 * Who may read whose figures, and the sentence that says so.
 *
 * A file of its own rather than a rule inside `ProgressService`, and that is not tidiness:
 * that service takes its subject as an argument and reads no security context anywhere, on
 * purpose, which is what let this ticket add a route and a rule while moving no arithmetic.
 * Putting the rule back inside it would undo exactly that.
 *
 * Three branches, in the order the ticket states them:
 *
 * 1. **A person always reads themselves**, and it is the first branch so that nothing below
 *    can take it away. Their seat does not matter — a `VIEWER` demoted this morning still
 *    gets their own numbers — and neither does their membership of anything, which matters
 *    more than it looks: `TeamService.create` never enrols its creator, so an instance where
 *    nobody is a member of anything is normal, and a rule that asked about membership first
 *    would lock people out of their own page.
 * 2. **An instance owner or admin reads everybody, in every team.** [TicketAccess.teamsLedBy]
 *    answers this in its first line rather than this file asking twice.
 * 3. **A team's titled administrator reads that team and the teams under it.** Also
 *    [TicketAccess.teamsLedBy], which is where the ancestry and the refusal of the
 *    open-chain clause are written down.
 *
 * Everybody else is refused, and the refusal is a 403 rather than the 404 a private draft
 * gets. The difference is what a wrong answer would disclose: a draft's 403 would confirm
 * the row exists, which is the whole of what the reader was fishing for, while both inputs
 * here are already public to any authenticated reader — `/api/people` lists every account
 * with its instance role and `/api/teams` lists every team. So there is nothing for a 404
 * to protect, and a 403 naming the rule is the answer somebody can act on.
 */
@Service
class ProgressAccess(
	private val teams: TeamRepository,
	private val users: UserRepository,
	private val access: TicketAccess,
) {

	/**
	 * The rule for one subject's page, scoped to one team.
	 *
	 * [teamId] is what the rights are asked about, not the subject's membership. The figures
	 * are derived entirely from that team's cycles and that team's tickets — every one of
	 * which an administrator of it can already read row by row — so the team is the boundary
	 * the read actually crosses. Asking instead whether the subject is enrolled in it would
	 * refuse the common case of a colleague nobody remembered to add to `team_members`,
	 * while protecting a number that is public in the ticket list either way.
	 */
	@Transactional(readOnly = true)
	fun requireReadable(actor: User, subject: User, teamId: UUID) {
		if (actor.id == subject.id) return
		if (leads(actor, teamId)) return
		throw AccessDeniedException(
			"${subject.displayName}'s figures are readable by them, by an instance admin, and by an" +
				" administrator of ${nameOf(teamId)}"
		)
	}

	/**
	 * The rule for a team's aggregates, which is the same rule with the subject removed.
	 *
	 * There is no self branch here and there cannot be one: a team's velocity is not
	 * anybody's own figure, and letting a member read it because they are in the team would
	 * be the ranking chart arriving through the back door — a team total is one subtraction
	 * away from a colleague's total on a two-person team.
	 */
	@Transactional(readOnly = true)
	fun requireTeamReadable(actor: User, teamId: UUID) {
		if (leads(actor, teamId)) return
		throw AccessDeniedException(
			"${nameOf(teamId)}'s figures are readable by an instance admin and by an administrator of it"
		)
	}

	private fun leads(actor: User, teamId: UUID): Boolean =
		access.teamsLedBy(actor, setOf(teamId)).isNotEmpty()

	/**
	 * The same rule read backwards: not "may this person look" but "who is looking".
	 *
	 * Derived from the rule rather than described beside it, so the sentence on the page
	 * cannot fall out of step with the branches above. The chain is walked upwards because
	 * administration runs downwards — an administrator of the parent administers this team —
	 * which is the one part of this a reader could not have worked out for themselves.
	 */
	@Transactional(readOnly = true)
	fun readers(subject: User, teamId: UUID): ProgressReaders {
		val chain = listOf(teamId) + teams.ancestorIds(teamId)
		return ProgressReaders(
			// Closed accounts are excluded: an account that cannot sign in is not somebody
			// who can read this, and naming it would make the sentence longer and less true.
			instanceAdmins = users.findAll()
				.filter { it.active && it.instanceRole.canConfigureInstance && it.id != subject.id },
			teams = chain.mapNotNull { teams.findById(it) }
				.filter { team ->
					teams.members(team.id)
						.any { it.role == MemberRole.ADMIN && it.user.id != subject.id && it.user.active }
				},
		)
	}

	private fun nameOf(teamId: UUID): String = teams.findById(teamId)?.name ?: "that team"
}

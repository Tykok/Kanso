package dev.kanso.service

import dev.kanso.domain.TeamStatus
import dev.kanso.domain.User
import dev.kanso.domain.statusKeyOf
import dev.kanso.repo.TeamStatusRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A team's own words for its work, and the order it reads them in — `KAN-28`.
 *
 * Two writes, and neither touches the set of keys: `label`, and `position`. Adding and
 * removing a status is `KAN-90`, because `Ticket.status` is an enum across the domain and
 * the six keys staying fixed is exactly what lets this ticket leave twenty semantic sites
 * alone — the pull-request transition, four MCP vocabularies, the Notion select, the seven
 * `IN (...)` lists that filter by category in SQL.
 *
 * Reading is open to anybody who can see the team: a status is a word every screen prints,
 * and a member who could not read it would see keys. Changing it is a configurator's, like
 * renaming the team — the vocabulary is the team's shape rather than its content.
 */
@Service
class TeamStatusService(
	private val statuses: TeamStatusRepository,
	private val teams: TeamService,
) {

	@Transactional(readOnly = true)
	fun list(actor: User, teamId: UUID): List<TeamStatus> {
		// Through the service, so a team nobody can see answers 404 here as it does
		// everywhere else rather than answering an empty list.
		teams.get(teamId)
		return statuses.forTeam(teamId)
	}

	/**
	 * Several teams' words in one query — what a page drawing a list of teams needs.
	 *
	 * Through the service and not straight from the repository, because a controller has no
	 * transaction of its own: `TeamController` read the catalogue directly and every
	 * `GET /api/teams` answered 500 with `No transaction in context`. Every unit test
	 * passed, because a test class is `@Transactional` and a browser is not — which is what
	 * `e2e/29-team-statuses.spec.ts` is for.
	 *
	 * No actor and no access check: this answers what a team *calls* its statuses, for
	 * teams the caller has already been handed.
	 */
	@Transactional(readOnly = true)
	fun forTeams(ids: Collection<UUID>): Map<UUID, List<TeamStatus>> = statuses.forTeams(ids)

	@Transactional(readOnly = true)
	fun forTeam(id: UUID): List<TeamStatus> = statuses.forTeam(id)

	@Transactional
	fun rename(actor: User, teamId: UUID, key: String, label: String): TeamStatus {
		requireConfigurator(actor)
		val existing = list(actor, teamId)
		val row = existing.firstOrNull { it.key == key }
			?: throw BadRequestException("This team has no status '$key'")

		// `statusKeyOf` is called for its refusal, not its answer: a label with no letter
		// or digit in it is refused here as it would be on the way in, even though a
		// rename never writes a key. A row labelled `…` would be a row nobody can name.
		statusKeyOf(label)

		// Compared case-insensitively and against the *others*, which is what
		// `team_statuses_label_uniq` compares — so the pre-check cannot miss, and
		// renaming a status to the word it already reads is not a conflict with itself.
		if (existing.any { it.key != key && it.label.equals(label, ignoreCase = true) }) {
			throw ConflictException("""This team already has a status called "${label.lowercase()}"""")
		}

		statuses.rename(teamId, key, label)
		return row.copy(label = label)
	}

	@Transactional
	fun reorder(actor: User, teamId: UUID, keys: List<String>): List<TeamStatus> {
		requireConfigurator(actor)
		val existing = list(actor, teamId).map { it.key }

		// Refused rather than merged, and the comparison is on sorted lists so a list
		// naming one status twice is caught by the same line: a client that disagrees
		// about which statuses exist would otherwise have the difference placed for it,
		// somewhere nobody chose.
		if (keys.sorted() != existing.sorted()) {
			throw BadRequestException("A reorder has to name every status of the team, exactly once")
		}

		statuses.reposition(teamId, keys)
		return statuses.forTeam(teamId)
	}

	/**
	 * The same seat that may rename the team, and a sentence of its own.
	 *
	 * `TeamService` keeps its own copy of this check with its own message, deliberately:
	 * the refusal a reader gets should name what they tried to do, and "change teams" is
	 * not what somebody renaming a status was doing.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can change a team's statuses")
		}
	}
}

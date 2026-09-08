package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.StatusCategory
import dev.kanso.repo.TicketRepository
import dev.kanso.domain.TeamStatus
import dev.kanso.domain.User
import dev.kanso.domain.statusKeyOf
import dev.kanso.repo.TeamStatusRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A team's own words for its work, the order it reads them in, and which of them exist.
 *
 * `KAN-28` landed the first two — `label` and `position`, neither of which touches the set
 * of keys. `KAN-90` landed the third, once `Ticket.status` stopped being an enum: [add]
 * and [remove] change the vocabulary itself, and the twenty semantic sites that used to
 * depend on the six keys staying fixed now read `StatusCategories` instead.
 *
 * Reading is open to anybody who can see the team: a status is a word every screen prints,
 * and a member who could not read it would see keys. Changing it is a configurator's, like
 * renaming the team — the vocabulary is the team's shape rather than its content.
 */
@Service
class TeamStatusService(
	private val statuses: TeamStatusRepository,
	private val teams: TeamService,
	/**
	 * The repository and not `TicketService`, and this is the one dependency worth a note.
	 *
	 * `TicketService.create` and `patch` validate a status against the team's catalogue —
	 * they call `StatusCategories`, which reads `TeamStatusRepository` — so if this service
	 * reached for `TicketService` the two would need each other and the Spring context
	 * would refuse to start. What [remove] actually needs is narrower than a patch anyway:
	 * move the rows, write a line each. No mirror push, no notification, no cascade, and
	 * none of those is right here — a status disappearing is one administrative act, not N
	 * edits by whoever performed it.
	 */
	private val tickets: TicketRepository,
	private val activity: ActivityService,
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

	/**
	 * A seventh word, at the end of the list — `KAN-90`.
	 *
	 * [category] is asked for and never editable afterwards, which is the deliberate half.
	 * Moving a status between categories moves what every burndown, workload chart and
	 * roadmap column counts, with no edit to any of them and no line in any feed — so it
	 * is decided once, here, by somebody who is deciding what the word *means* rather
	 * than what it says. A team that got it wrong removes the status and adds it again,
	 * which is a gesture that says where its tickets go.
	 *
	 * Last by `position`, never inserted: adding a status must not restack a board
	 * somebody is looking at. Where it belongs is a reorder, which the team already has.
	 */
	@Transactional
	fun add(actor: User, teamId: UUID, label: String, category: StatusCategory): TeamStatus {
		requireConfigurator(actor)
		val existing = list(actor, teamId)

		// Derived, so a client never sends one — and called before the conflict check so a
		// label with nothing nameable in it is refused as itself rather than as a duplicate
		// of another unnameable one.
		val key = statusKeyOf(label)

		// By the word and case-insensitively, which is what `team_statuses_label_uniq`
		// compares — the same pre-check `rename` makes, for the same reason: the index
		// would otherwise surface as an opaque 409 from the driver.
		if (existing.any { it.label.equals(label, ignoreCase = true) }) {
			throw ConflictException("""This team already has a status called "${label.lowercase()}"""")
		}
		// The key too, separately: two different words can fold to one key — `En cours` and
		// `en-cours` — and that collision is the primary key's, which has no sentence.
		if (existing.any { it.key == key }) {
			throw ConflictException("""This team already has a status called "$key"""")
		}

		val row = TeamStatus(teamId, key, label, category, position = existing.size)
		statuses.insert(row)
		return row
	}

	/**
	 * One word gone, and its tickets somewhere the team named — `KAN-90`.
	 *
	 * [into] is required exactly when the status holds tickets, which is `KAN-4`'s
	 * disposition shape: a removal that would orphan rows has to say where they go, and
	 * one that would orphan none does not have to invent a destination. `tickets_status_fk`
	 * makes the alternative unavailable rather than merely unwise — the delete would be
	 * refused by the database after this method had returned.
	 *
	 * Archived tickets count, because the foreign key does not care whether a row is on a
	 * board.
	 */
	@Transactional
	fun remove(actor: User, teamId: UUID, key: String, into: String?) {
		requireConfigurator(actor)
		val existing = list(actor, teamId)
		existing.firstOrNull { it.key == key }
			?: throw BadRequestException("This team has no status '$key'")

		// Before anything moves. A team with no statuses could hold no tickets at all —
		// `tickets_status_fk` would have nothing to point at — so every write on that
		// team's screens would answer a foreign key error instead of a sentence.
		if (existing.size == 1) throw BadRequestException("A team keeps at least one status")

		val held = tickets.withStatus(teamId, key)
		if (held.isNotEmpty()) {
			val destination = into
				?: throw BadRequestException(
					"""Removing "$key" has to say where its ${held.size} ticket${if (held.size == 1) "" else "s"} go${if (held.size == 1) "es" else ""}""",
				)
			if (destination == key) {
				throw BadRequestException("""Removing "$key" cannot move its tickets into itself""")
			}
			if (existing.none { it.key == destination }) {
				throw BadRequestException("This team has no status '$destination'")
			}
			for (ticket in held) {
				tickets.moveStatus(ticket.id, destination)
				// The same `status_changed` an edit writes, and with the actor who removed
				// the status: the ticket did change status, and a burndown whose number
				// moved with no line behind it is a burndown nobody trusts.
				activity.record(
					ActivityEntity.TICKET,
					ticket.id,
					actor.id,
					ActivityKind.STATUS_CHANGED,
					mapOf("from" to key, "to" to destination),
				)
			}
		}

		statuses.delete(teamId, key)
		// Contiguous again, so the next `add` does not collide with a hole — `position`
		// has no unique index, but a list whose numbers skip reads as one that lost a row.
		statuses.reposition(teamId, statuses.forTeam(teamId).map { it.key })
	}

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

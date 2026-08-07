package dev.kanso.service

import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TeamMember
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.deletePayload
import dev.kanso.sync.SyncOperation
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TeamService(
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {

	@Transactional(readOnly = true)
	fun list(includeArchived: Boolean): List<Team> = teams.findAll(includeArchived)

	@Transactional(readOnly = true)
	fun get(id: UUID): Team = teams.findById(id) ?: throw NotFoundException("No team $id")

	@Transactional(readOnly = true)
	fun descendants(id: UUID): List<Team> {
		get(id)
		return teams.descendants(id)
	}

	// --- disposition ---------------------------------------------------------

	@Transactional(readOnly = true)
	fun contents(id: UUID): DispositionCounts {
		get(id)
		return countsOf(id)
	}

	/**
	 * What the team holds directly. A kept sub-team leaves with its own projects and
	 * tickets untouched, so counting those would describe a decision nobody was
	 * offered.
	 */
	private fun countsOf(id: UUID) = DispositionCounts(
		subTeams = teams.directChildIds(id).size,
		projects = projects.countByTeams(listOf(id)),
		tickets = tickets.countByTeams(listOf(id)),
	)

	@Transactional
	fun create(actor: User, name: String, key: String?, parentTeamId: UUID?): Team {
		requireConfigurator(actor)
		if (parentTeamId != null && teams.findById(parentTeamId) == null) {
			throw BadRequestException("Parent team $parentTeamId does not exist")
		}
		val team = teams.insert(name, resolveKey(name, key), parentTeamId)
		syncJobs.enqueue(SyncEntityType.TEAM, team.id, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.CREATED, team.id))
		return team
	}

	@Transactional
	fun update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?, archived: Boolean): Team {
		requireConfigurator(actor)
		val existing = get(id)
		if (parentTeamId != existing.parentTeamId) {
			if (parentTeamId == id) throw ConflictException("A team cannot be its own parent")
			if (parentTeamId != null && teams.findById(parentTeamId) == null) {
				throw BadRequestException("Parent team $parentTeamId does not exist")
			}
			if (teams.wouldCreateCycle(id, parentTeamId)) {
				throw ConflictException("Moving team $id under $parentTeamId would create a cycle")
			}
		}
		if (key != existing.key) validateKey(key)

		val updated = teams.update(id, name, key, parentTeamId, archived)
			?: throw NotFoundException("No team $id")
		syncJobs.enqueue(
			SyncEntityType.TEAM,
			id,
			if (archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, id))
		return updated
	}

	/**
	 * Deleting locally still archives in Notion: Notion has no hard delete worth
	 * relying on, and a page that silently disappears from the mirror is worse
	 * than one marked archived.
	 */
	@Transactional
	fun delete(id: UUID) {
		val existing = get(id)
		syncJobs.enqueue(
			SyncEntityType.TEAM,
			id,
			SyncOperation.DELETE,
			payload = deletePayload(existing.mirror.notionPageId),
		)
		teams.delete(id)
		events.publish(KansoEvent.team(ChangeKind.DELETED, id))
	}

	// --- members -------------------------------------------------------------

	@Transactional(readOnly = true)
	fun members(teamId: UUID): List<TeamMember> {
		get(teamId)
		return teams.members(teamId)
	}

	@Transactional
	fun addMember(actor: User, teamId: UUID, userId: UUID, role: MemberRole): List<TeamMember> {
		requireConfigurator(actor)
		get(teamId)
		users.findById(userId) ?: throw BadRequestException("No user $userId")
		teams.addMember(teamId, userId, role)
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
		return teams.members(teamId)
	}

	@Transactional
	fun removeMember(actor: User, teamId: UUID, userId: UUID) {
		requireConfigurator(actor)
		get(teamId)
		if (!teams.removeMember(teamId, userId)) {
			throw NotFoundException("User $userId is not a member of team $teamId")
		}
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
	}

	// --- permissions ---------------------------------------------------------

	/**
	 * Teams are instance configuration, not daily work: who reports to whom decides
	 * what everyone else sees. The client hides what it may not do from `/api/me`;
	 * this is the backstop, not the mechanism.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can change teams")
		}
	}

	// --- keys ----------------------------------------------------------------

	private fun validateKey(key: String) {
		if (!KEY_PATTERN.matches(key)) {
			throw BadRequestException("Team key '$key' must be 2-8 characters, A-Z or 0-9")
		}
		if (teams.findByKey(key) != null) throw ConflictException("Team key '$key' is already taken")
	}

	/**
	 * The key prefixes every ticket identifier, so it has to be short, stable and
	 * unique. When the caller doesn't pick one we derive it from the name and add
	 * digits until it's free.
	 */
	private fun resolveKey(name: String, requested: String?): String {
		if (requested != null) {
			val key = requested.uppercase()
			validateKey(key)
			return key
		}
		val base = name.uppercase().filter { it.isLetterOrDigit() }.take(3).ifBlank { "TEAM" }
		if (teams.findByKey(base) == null && KEY_PATTERN.matches(base)) return base
		for (suffix in 2..99) {
			val candidate = "${base.take(6)}$suffix"
			if (teams.findByKey(candidate) == null) return candidate
		}
		throw ConflictException("Could not derive a free team key from '$name'; pass one explicitly")
	}

	private companion object {
		val KEY_PATTERN = Regex("^[A-Z0-9]{2,8}$")
	}
}

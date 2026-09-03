package dev.kanso.repo

import dev.kanso.db.TeamMembers
import dev.kanso.db.Teams
import dev.kanso.db.Users
import dev.kanso.db.toTeam
import dev.kanso.db.toUser
import dev.kanso.domain.MemberRole
import dev.kanso.domain.SyncState
import dev.kanso.domain.Team
import dev.kanso.domain.TeamMember
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TeamRepository(private val jdbc: JdbcClient) {

	fun findById(id: UUID): Team? =
		Teams.selectAll().where { Teams.id eq id }.singleOrNull()?.toTeam()

	fun findByKey(key: String): Team? =
		Teams.selectAll().where { Teams.key eq key }.singleOrNull()?.toTeam()

	fun findByNotionPageId(pageId: String): Team? =
		Teams.selectAll().where { Teams.notionPageId eq pageId }.singleOrNull()?.toTeam()

	fun findAll(includeArchived: Boolean = false): List<Team> {
		val where: Op<Boolean> = if (includeArchived) Op.TRUE else (Teams.archived eq false)
		return Teams.selectAll().where(where).orderBy(Teams.name to SortOrder.ASC).map { it.toTeam() }
	}

	fun findAllById(ids: Collection<UUID>): List<Team> =
		if (ids.isEmpty()) emptyList()
		else Teams.selectAll().where { Teams.id inList ids }.map { it.toTeam() }

	fun insert(name: String, key: String, parentTeamId: UUID?): Team {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		Teams.insert {
			it[Teams.id] = id
			it[Teams.name] = name
			it[Teams.key] = key
			it[Teams.parentTeamId] = parentTeamId
			it[archived] = false
			it[ticketCounter] = 0
			it[syncState] = SyncState.PENDING.wire
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id)) { "team $id vanished right after insert" }
	}

	/** Business fields only. Mirror columns belong to the sync engine. */
	fun update(id: UUID, name: String, key: String, parentTeamId: UUID?, archived: Boolean): Team? {
		val changed = Teams.update({ Teams.id eq id }) {
			it[Teams.name] = name
			it[Teams.key] = key
			it[Teams.parentTeamId] = parentTeamId
			it[Teams.archived] = archived
		}
		return if (changed == 0) null else findById(id)
	}

	fun delete(id: UUID): Boolean = Teams.deleteWhere { Teams.id eq id } > 0

	/**
	 * The team and everything under it.
	 *
	 * Exposed has no `WITH RECURSIVE`, so this one is raw SQL. It runs on the
	 * connection Spring already holds, inside the caller's transaction.
	 */
	fun descendantIds(rootId: UUID): List<UUID> = jdbc.sql(
		"""
		WITH RECURSIVE descendants AS (
		    SELECT id FROM teams WHERE id = :root
		    UNION ALL
		    SELECT t.id FROM teams t JOIN descendants d ON t.parent_team_id = d.id
		)
		SELECT id FROM descendants
		""".trimIndent()
	).param("root", rootId).query(UUID::class.java).list().filterNotNull()

	fun descendants(rootId: UUID): List<Team> = findAllById(descendantIds(rootId))

	/**
	 * True when re-parenting [teamId] under [newParentId] would close a loop —
	 * onto itself or onto one of its own descendants. Notion's self-referencing
	 * relation accepts a cycle without complaint; Postgres is where we refuse
	 * it, both on the way in and on the way back from the mirror.
	 */
	fun wouldCreateCycle(teamId: UUID, newParentId: UUID?): Boolean =
		newParentId != null && newParentId in descendantIds(teamId)

	/**
	 * Reserves the next ticket number for a team. The row lock this UPDATE takes
	 * serialises concurrent allocations, so numbers come out dense and unique
	 * without a per-team sequence to keep in step.
	 */
	fun nextTicketNumber(teamId: UUID): Int = jdbc.sql(
		"UPDATE teams SET ticket_counter = ticket_counter + 1 WHERE id = :id RETURNING ticket_counter"
	).param("id", teamId).query(Int::class.java).single()

	/**
	 * Reserves [count] consecutive numbers in one statement and returns them in order.
	 *
	 * One statement rather than [count] of them: the row lock is held for the same
	 * span either way, so the loop would only add a round trip per ticket inside it —
	 * time every concurrent "new ticket" in this team spends waiting.
	 */
	fun nextTicketNumbers(teamId: UUID, count: Int): List<Int> {
		require(count >= 0) { "Cannot reserve a negative number of ticket numbers ($count)" }
		if (count == 0) return emptyList()
		val last = jdbc.sql(
			"UPDATE teams SET ticket_counter = ticket_counter + :count WHERE id = :id RETURNING ticket_counter"
		).param("count", count).param("id", teamId).query(Int::class.java).single()
		return ((last - count + 1)..last).toList()
	}

	/** Only the teams whose parent is [id] — the ones a plan can actually re-home. */
	fun directChildIds(id: UUID): List<UUID> =
		Teams.select(Teams.id).where { Teams.parentTeamId eq id }.map { it[Teams.id] }

	/**
	 * The chain above [id], nearest first, excluding [id] itself.
	 *
	 * Raw SQL for the same reason [descendantIds] is: Exposed has no `WITH RECURSIVE`.
	 * Cycles are impossible — [wouldCreateCycle] refuses them on the way in — so the
	 * walk always terminates at a root.
	 */
	fun ancestorIds(id: UUID): List<UUID> = jdbc.sql(
		"""
		WITH RECURSIVE ancestors AS (
		    SELECT parent_team_id AS id, 1 AS depth
		      FROM teams WHERE id = :id AND parent_team_id IS NOT NULL
		    UNION ALL
		    SELECT t.parent_team_id, a.depth + 1
		      FROM teams t JOIN ancestors a ON t.id = a.id
		     WHERE t.parent_team_id IS NOT NULL
		)
		SELECT id FROM ancestors ORDER BY depth
		""".trimIndent()
	).param("id", id).query(UUID::class.java).list().filterNotNull()

	fun setParent(id: UUID, parentTeamId: UUID?): Boolean =
		Teams.update({ Teams.id eq id }) { it[Teams.parentTeamId] = parentTeamId } > 0

	fun setArchived(ids: Collection<UUID>, archived: Boolean): Int =
		if (ids.isEmpty()) 0
		else Teams.update({ Teams.id inList ids }) { it[Teams.archived] = archived }

	// --- members -------------------------------------------------------------

	fun members(teamId: UUID): List<TeamMember> =
		TeamMembers.join(Users, JoinType.INNER, TeamMembers.userId, Users.id)
			.selectAll().where { TeamMembers.teamId eq teamId }
			.orderBy(Users.displayName to SortOrder.ASC)
			.map { TeamMember(teamId, it.toUser(), MemberRole.from(it[TeamMembers.role])) }

	fun addMember(teamId: UUID, userId: UUID, role: MemberRole) {
		TeamMembers.upsert(TeamMembers.teamId, TeamMembers.userId) {
			it[TeamMembers.teamId] = teamId
			it[TeamMembers.userId] = userId
			it[TeamMembers.role] = role.wire
		}
	}

	fun removeMember(teamId: UUID, userId: UUID): Boolean =
		TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) } > 0

	fun teamIdsFor(userId: UUID): List<UUID> =
		TeamMembers.select(TeamMembers.teamId).where { TeamMembers.userId eq userId }
			.map { it[TeamMembers.teamId] }

	/**
	 * The teams this person is titled `admin` of.
	 *
	 * Two callers, asking it for opposite reasons. `AccountService` refuses to put somebody
	 * on a read-only seat while a team still calls them its administrator, and it names the
	 * teams rather than saying no and leaving the caller to go looking. `TicketAccess`
	 * asks it as the seed of the one thing the title grants: reading another person's
	 * figures in a team, and in the teams under it.
	 */
	fun adminTeamIdsFor(userId: UUID): List<UUID> =
		TeamMembers.select(TeamMembers.teamId)
			.where { (TeamMembers.userId eq userId) and (TeamMembers.role eq MemberRole.ADMIN.wire) }
			.map { it[TeamMembers.teamId] }

	/**
	 * Exactly the teams in [ids] that hold at least one member row.
	 *
	 * `TicketAccess` needs this per chain rather than per team — one query for however
	 * many ancestors a batch of tickets touches, instead of a `members()` call each.
	 */
	fun teamsWithMembers(ids: Collection<UUID>): Set<UUID> =
		if (ids.isEmpty()) emptySet()
		else TeamMembers.select(TeamMembers.teamId).where { TeamMembers.teamId inList ids }
			.withDistinct()
			.map { it[TeamMembers.teamId] }
			.toSet()

	// --- mirror bookkeeping --------------------------------------------------

	fun markSynced(id: UUID, notionPageId: String, notionLastEdited: OffsetDateTime?) {
		Teams.update({ Teams.id eq id }) {
			it[Teams.notionPageId] = notionPageId
			it[syncState] = SyncState.SYNCED.wire
			it[notionSyncedAt] = OffsetDateTime.now()
			it[notionLastEditedTime] = notionLastEdited
		}
	}

	fun markSyncState(id: UUID, state: SyncState) {
		Teams.update({ Teams.id eq id }) { it[syncState] = state.wire }
	}
}

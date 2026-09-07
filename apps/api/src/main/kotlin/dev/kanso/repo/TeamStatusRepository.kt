package dev.kanso.repo

import dev.kanso.db.TeamStatuses
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * A team's statuses, as rows — `KAN-28`.
 *
 * Beside `LabelRepository`, which is the same shape: a team-scoped catalogue somebody
 * edits, read whole because every read of it is a list a screen draws.
 */
@Repository
class TeamStatusRepository {

	/**
	 * Sorted by `(position, key)`.
	 *
	 * The key breaks a tie deliberately: `V41` gives `position` no unique index, because a
	 * reorder is a swap and a unique index would refuse one halfway through. A tie is
	 * therefore possible for as long as a reorder takes, and the second column is what
	 * makes it invisible instead of making the order depend on how the disk enumerated.
	 */
	fun forTeam(teamId: UUID): List<TeamStatus> =
		TeamStatuses.selectAll().where { TeamStatuses.teamId eq teamId }
			.orderBy(TeamStatuses.position to SortOrder.ASC, TeamStatuses.key to SortOrder.ASC)
			.map { it.toTeamStatus() }

	/**
	 * One query for several teams — the shape a scope spanning teams needs.
	 *
	 * Not a loop over [forTeam] at the call site: the list screen's scope can hold a
	 * parent and every descendant, and a query per team is the N+1 that grouped pages
	 * were built to avoid.
	 */
	fun forTeams(ids: Collection<UUID>): Map<UUID, List<TeamStatus>> =
		if (ids.isEmpty()) emptyMap()
		else TeamStatuses.selectAll().where { TeamStatuses.teamId inList ids.toSet() }
			.orderBy(TeamStatuses.position to SortOrder.ASC, TeamStatuses.key to SortOrder.ASC)
			.map { it.toTeamStatus() }
			.groupBy { it.teamId }

	fun insert(status: TeamStatus) {
		TeamStatuses.insert {
			it[teamId] = status.teamId
			it[key] = status.key
			it[label] = status.label
			it[category] = status.category.wire
			it[position] = status.position
		}
	}

	/** The label, and nothing else: the key is immutable and the position is a reorder's. */
	fun rename(teamId: UUID, key: String, label: String): Boolean =
		TeamStatuses.update({ (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) }) {
			it[TeamStatuses.label] = label
		} > 0

	/**
	 * The whole order, rewritten from the list's indices.
	 *
	 * Positions are assigned rather than swapped pairwise, so the result is contiguous
	 * whatever it was before, and the caller sending a partial list is refused by the
	 * service above rather than half-applied here.
	 */
	fun reposition(teamId: UUID, keysInOrder: List<String>) {
		keysInOrder.forEachIndexed { index, key ->
			TeamStatuses.update({ (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) }) {
				it[position] = index
			}
		}
	}

	fun delete(teamId: UUID, key: String): Boolean =
		TeamStatuses.deleteWhere { (TeamStatuses.teamId eq teamId) and (TeamStatuses.key eq key) } > 0

	private fun ResultRow.toTeamStatus() = TeamStatus(
		teamId = this[TeamStatuses.teamId],
		key = this[TeamStatuses.key],
		label = this[TeamStatuses.label],
		category = StatusCategory.from(this[TeamStatuses.category]),
		position = this[TeamStatuses.position],
	)
}

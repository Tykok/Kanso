package dev.kanso.repo

import dev.kanso.db.ProjectUpdates
import dev.kanso.domain.ProjectHealth
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** One update as stored; the author is an id until the service resolves it. */
data class ProjectUpdateRecord(
	val id: UUID,
	val projectId: UUID,
	val health: ProjectHealth,
	val body: String,
	val authorId: UUID?,
	val at: OffsetDateTime,
)

/**
 * The history of what people have said about a project, and the one read everything
 * else is built out of: **the current health is the newest row, computed here.**
 *
 * There is no `projects.health` for this to keep in step with, deliberately — `V23`
 * carries the argument, which is `V10`'s: a stored current value is wrong from the moment
 * the next update lands, and wrong invisibly, since nothing on the project row changes to
 * show that the copy has aged.
 */
@Repository
class ProjectUpdateRepository {

	/**
	 * Newest first, like every other feed in this codebase and unlike a comment thread —
	 * the reader wants what is true now, and the rest is context under it.
	 *
	 * `at DESC, id DESC` and not `at DESC` alone: two updates posted inside the same clock
	 * tick would otherwise come back in whatever order the scan produced, and a list whose
	 * order changes between two reads of the same rows is a list that cannot be paged or
	 * trusted. The id decides nothing meaningful; it only makes the answer the same twice.
	 */
	fun forProject(projectId: UUID, limit: Int): List<ProjectUpdateRecord> =
		ProjectUpdates.selectAll().where { ProjectUpdates.projectId eq projectId }
			.orderBy(ProjectUpdates.at to SortOrder.DESC, ProjectUpdates.id to SortOrder.DESC)
			.limit(limit)
			.map { it.toRecord() }

	fun latest(projectId: UUID): ProjectUpdateRecord? = forProject(projectId, 1).firstOrNull()

	/**
	 * The current health of a whole list of projects, in one query.
	 *
	 * `DISTINCT ON (project_id) … ORDER BY project_id, at DESC` walks
	 * `project_updates_project_idx` and stops at the first row of each project's run, so
	 * the sidebar's read costs one index scan rather than one query per project — and the
	 * same cost as a column would have, which is what makes deriving it affordable enough
	 * to keep doing.
	 *
	 * A project absent from the result is a project nobody has assessed. It is left out of
	 * the map rather than mapped to a value, so no caller can accidentally read "nobody has
	 * said" as a health.
	 */
	fun latestHealthFor(projectIds: Collection<UUID>): Map<UUID, ProjectHealth> {
		if (projectIds.isEmpty()) return emptyMap()
		return ProjectUpdates
			.select(ProjectUpdates.projectId, ProjectUpdates.health)
			.where { ProjectUpdates.projectId inList projectIds }
			.withDistinctOn(ProjectUpdates.projectId to SortOrder.ASC)
			.orderBy(ProjectUpdates.at to SortOrder.DESC, ProjectUpdates.id to SortOrder.DESC)
			.associate { it[ProjectUpdates.projectId] to ProjectHealth.from(it[ProjectUpdates.health]) }
	}

	fun insert(
		id: UUID,
		projectId: UUID,
		health: ProjectHealth,
		body: String,
		authorId: UUID?,
		at: OffsetDateTime,
	): ProjectUpdateRecord {
		ProjectUpdates.insert {
			it[ProjectUpdates.id] = id
			it[ProjectUpdates.projectId] = projectId
			it[ProjectUpdates.health] = health.wire
			it[ProjectUpdates.body] = body
			it[ProjectUpdates.authorId] = authorId
			it[ProjectUpdates.at] = at
		}
		return ProjectUpdateRecord(id, projectId, health, body, authorId, at)
	}

	private fun ResultRow.toRecord() = ProjectUpdateRecord(
		id = this[ProjectUpdates.id],
		projectId = this[ProjectUpdates.projectId],
		health = ProjectHealth.from(this[ProjectUpdates.health]),
		body = this[ProjectUpdates.body],
		authorId = this[ProjectUpdates.authorId],
		at = this[ProjectUpdates.at],
	)
}

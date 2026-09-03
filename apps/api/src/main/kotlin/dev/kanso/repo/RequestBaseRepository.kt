package dev.kanso.repo

import dev.kanso.db.NotionRequestBases
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A Notion base Kanso siphons requests out of, and the team whose queue they land in. */
data class RequestBase(
	val dataSourceId: String,
	val databaseId: String,
	val teamId: UUID,
)

/**
 * Which bases are requests bases — kept apart from [NotionMetaRepository] on purpose.
 *
 * That class answers "where does the mirror publish", and every id it hands out ends up in
 * `client.createPage`. This one answers "what does Kanso read and never write", and nothing
 * that pushes is allowed to depend on it. Two questions, two tables, two repositories: the
 * one-way rule is then something you would have to work to break rather than something you
 * have to remember. See `V37`.
 */
@Repository
class RequestBaseRepository {

	fun findAll(): List<RequestBase> = NotionRequestBases.selectAll().map { it.toRequestBase() }

	fun find(dataSourceId: String): RequestBase? =
		NotionRequestBases.selectAll().where { NotionRequestBases.dataSourceId eq dataSourceId }
			.singleOrNull()?.toRequestBase()

	/**
	 * Registers a base, or re-points an existing one at another team.
	 *
	 * An upsert rather than an insert because re-registering is how the team is changed, and
	 * the primary key already refuses the thing worth refusing: two teams siphoning one
	 * base. Pages already adopted stay where they landed — `notion_import_origin` is what
	 * decides that, and moving the base does not un-triage anybody's work.
	 */
	fun save(dataSourceId: String, databaseId: String, teamId: UUID) {
		NotionRequestBases.upsert(NotionRequestBases.dataSourceId) {
			it[NotionRequestBases.dataSourceId] = dataSourceId
			it[NotionRequestBases.databaseId] = databaseId
			it[NotionRequestBases.teamId] = teamId
			it[registeredAt] = OffsetDateTime.now()
			it[updatedAt] = OffsetDateTime.now()
		}
	}

	/**
	 * Stops the siphon. The poll cursor is left where it is, deliberately: `V37` argues that
	 * re-registering must not re-walk the base from the beginning of time, and the ledger
	 * would refuse every page it re-read anyway.
	 */
	fun remove(dataSourceId: String): Boolean =
		NotionRequestBases.deleteWhere { NotionRequestBases.dataSourceId eq dataSourceId } > 0

	private fun ResultRow.toRequestBase() = RequestBase(
		dataSourceId = this[NotionRequestBases.dataSourceId],
		databaseId = this[NotionRequestBases.databaseId],
		teamId = this[NotionRequestBases.teamId],
	)
}

package dev.kanso.repo

import dev.kanso.db.SavedViews
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** [filters] is the jsonb document as text; the service parses and validates it. */
data class SavedViewRow(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val shared: Boolean,
	val filters: String,
	val groupBy: String,
	val sortBy: String,
	val createdBy: UUID?,
)

@Repository
class SavedViewRepository(private val jdbc: JdbcClient) {

	fun findById(id: UUID): SavedViewRow? =
		SavedViews.selectAll().where { SavedViews.id eq id }.singleOrNull()?.toViewRow()

	fun findByTeam(teamId: UUID): List<SavedViewRow> =
		SavedViews.selectAll().where { SavedViews.teamId eq teamId }
			.orderBy(SavedViews.name to SortOrder.ASC)
			.map { it.toViewRow() }

	fun findByTeamAndName(teamId: UUID, name: String): SavedViewRow? =
		SavedViews.selectAll().where { (SavedViews.teamId eq teamId) and (SavedViews.name eq name) }
			.singleOrNull()?.toViewRow()

	/**
	 * Raw SQL for the insert and the update because `filters` is jsonb: the driver refuses
	 * a varchar parameter for that column, so the cast has to be written out. The same
	 * split `sync_jobs.payload` already lives with.
	 */
	fun insert(
		id: UUID,
		teamId: UUID,
		name: String,
		shared: Boolean,
		filters: String,
		groupBy: String,
		sortBy: String,
		createdBy: UUID?,
	): SavedViewRow {
		jdbc.sql(
			"""
			INSERT INTO saved_views (id, team_id, name, shared, filters, group_by, sort_by, created_by)
			VALUES (:id, :teamId, :name, :shared, CAST(:filters AS jsonb), :groupBy, :sortBy, :createdBy)
			""".trimIndent()
		)
			.param("id", id)
			.param("teamId", teamId)
			.param("name", name)
			.param("shared", shared)
			.param("filters", filters)
			.param("groupBy", groupBy)
			.param("sortBy", sortBy)
			.param("createdBy", createdBy)
			.update()
		return requireNotNull(findById(id))
	}

	fun update(
		id: UUID,
		name: String,
		shared: Boolean,
		filters: String,
		groupBy: String,
		sortBy: String,
	): SavedViewRow? {
		val changed = jdbc.sql(
			"""
			UPDATE saved_views
			   SET name = :name, shared = :shared, filters = CAST(:filters AS jsonb),
			       group_by = :groupBy, sort_by = :sortBy
			 WHERE id = :id
			""".trimIndent()
		)
			.param("id", id)
			.param("name", name)
			.param("shared", shared)
			.param("filters", filters)
			.param("groupBy", groupBy)
			.param("sortBy", sortBy)
			.update()
		return if (changed == 0) null else findById(id)
	}

	fun delete(id: UUID): Boolean = SavedViews.deleteWhere { SavedViews.id eq id } > 0

	fun deleteByTeams(teamIds: Collection<UUID>): Int =
		if (teamIds.isEmpty()) 0 else SavedViews.deleteWhere { SavedViews.teamId inList teamIds }
}

private fun ResultRow.toViewRow() = SavedViewRow(
	id = this[SavedViews.id],
	teamId = this[SavedViews.teamId],
	name = this[SavedViews.name],
	shared = this[SavedViews.shared],
	// `text("filters")` over a jsonb column: Postgres hands it back as a PGobject whose
	// toString is the document, which is what Exposed's text reader ends up with.
	filters = this[SavedViews.filters],
	groupBy = this[SavedViews.groupBy],
	sortBy = this[SavedViews.sortBy],
	createdBy = this[SavedViews.createdBy],
)

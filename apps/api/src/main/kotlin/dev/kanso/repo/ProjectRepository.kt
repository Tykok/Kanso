package dev.kanso.repo

import dev.kanso.db.ProjectDocs
import dev.kanso.db.Projects
import dev.kanso.db.toProject
import dev.kanso.domain.Project
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.SyncState
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ProjectRepository {

	fun findById(id: UUID): Project? =
		Projects.selectAll().where { Projects.id eq id }.singleOrNull()?.toProject()

	fun findByNotionPageId(pageId: String): Project? =
		Projects.selectAll().where { Projects.notionPageId eq pageId }.singleOrNull()?.toProject()

	fun findAllById(ids: Collection<UUID>): List<Project> =
		if (ids.isEmpty()) emptyList()
		else Projects.selectAll().where { Projects.id inList ids }.map { it.toProject() }

	fun search(teamIds: Collection<UUID>?, includeArchived: Boolean): List<Project> {
		val conditions = buildList {
			if (!includeArchived) add(Projects.archived eq false)
			if (teamIds != null) add(Projects.teamId inList teamIds)
		}
		val where = if (conditions.isEmpty()) Op.TRUE else conditions.compoundAnd()
		return Projects.selectAll().where(where)
			.orderBy(Projects.name to SortOrder.ASC)
			.map { it.toProject() }
	}

	fun insert(
		name: String,
		status: ProjectStatus,
		startDate: LocalDate?,
		endDate: LocalDate?,
		leadUserId: UUID?,
		teamId: UUID?,
	): Project {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		Projects.insert {
			it[Projects.id] = id
			it[Projects.name] = name
			it[Projects.status] = status.wire
			it[Projects.startDate] = startDate
			it[Projects.endDate] = endDate
			it[Projects.leadUserId] = leadUserId
			it[Projects.teamId] = teamId
			it[archived] = false
			it[syncState] = SyncState.PENDING.wire
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun update(
		id: UUID,
		name: String,
		status: ProjectStatus,
		startDate: LocalDate?,
		endDate: LocalDate?,
		leadUserId: UUID?,
		teamId: UUID?,
		archived: Boolean,
	): Project? {
		val changed = Projects.update({ Projects.id eq id }) {
			it[Projects.name] = name
			it[Projects.status] = status.wire
			it[Projects.startDate] = startDate
			it[Projects.endDate] = endDate
			it[Projects.leadUserId] = leadUserId
			it[Projects.teamId] = teamId
			it[Projects.archived] = archived
		}
		return if (changed == 0) null else findById(id)
	}

	fun delete(id: UUID): Boolean = Projects.deleteWhere { Projects.id eq id } > 0

	// --- bulk reads ----------------------------------------------------------

	fun countByTeams(teamIds: Collection<UUID>, includeArchived: Boolean = true): Int {
		if (teamIds.isEmpty()) return 0
		val where =
			if (includeArchived) Projects.teamId inList teamIds
			else (Projects.teamId inList teamIds) and (Projects.archived eq false)
		return Projects.selectAll().where(where).count().toInt()
	}

	fun idsByTeam(teamId: UUID): List<UUID> =
		Projects.select(Projects.id).where { Projects.teamId eq teamId }.map { it[Projects.id] }

	/** Null is a real destination: a project with no team is the transverse case. */
	fun setTeam(projectId: UUID, teamId: UUID?): Boolean =
		Projects.update({ Projects.id eq projectId }) { it[Projects.teamId] = teamId } > 0

	fun setArchivedByTeams(teamIds: Collection<UUID>, archived: Boolean): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Projects.select(Projects.id)
			.where { (Projects.teamId inList teamIds) and (Projects.archived neq archived) }
			.map { it[Projects.id] }
		if (ids.isNotEmpty()) Projects.update({ Projects.id inList ids }) { it[Projects.archived] = archived }
		return ids
	}

	fun deleteByTeams(teamIds: Collection<UUID>): List<UUID> {
		if (teamIds.isEmpty()) return emptyList()
		val ids = Projects.select(Projects.id).where { Projects.teamId inList teamIds }.map { it[Projects.id] }
		if (ids.isNotEmpty()) Projects.deleteWhere { Projects.id inList ids }
		return ids
	}

	// --- docs ----------------------------------------------------------------

	fun docIds(projectId: UUID): List<UUID> =
		ProjectDocs.select(ProjectDocs.docId).where { ProjectDocs.projectId eq projectId }
			.map { it[ProjectDocs.docId] }

	fun docIdsFor(projectIds: Collection<UUID>): Map<UUID, List<UUID>> =
		if (projectIds.isEmpty()) emptyMap()
		else ProjectDocs.selectAll().where { ProjectDocs.projectId inList projectIds }
			.groupBy({ it[ProjectDocs.projectId] }, { it[ProjectDocs.docId] })

	/** Replaces the whole set — the mirror writes relations wholesale anyway. */
	fun setDocs(projectId: UUID, docIds: Collection<UUID>) {
		ProjectDocs.deleteWhere { ProjectDocs.projectId eq projectId }
		if (docIds.isNotEmpty()) {
			ProjectDocs.batchInsert(docIds.distinct()) { docId ->
				this[ProjectDocs.projectId] = projectId
				this[ProjectDocs.docId] = docId
			}
		}
	}

	// --- mirror bookkeeping --------------------------------------------------

	fun markSynced(id: UUID, notionPageId: String, notionLastEdited: OffsetDateTime?) {
		Projects.update({ Projects.id eq id }) {
			it[Projects.notionPageId] = notionPageId
			it[syncState] = SyncState.SYNCED.wire
			it[notionSyncedAt] = OffsetDateTime.now()
			it[notionLastEditedTime] = notionLastEdited
		}
	}

	fun markSyncState(id: UUID, state: SyncState) {
		Projects.update({ Projects.id eq id }) { it[syncState] = state.wire }
	}
}

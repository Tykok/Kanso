package dev.kanso.docs

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocFolderRepository {

	fun findById(id: UUID): DocFolder? =
		DocFolders.selectAll().where { DocFolders.id eq id }.singleOrNull()?.toFolder()

	/**
	 * The whole tree, flat. The client nests it — a read per level would cost N queries
	 * to answer what one ordered list already answers, and `sidebar-tree.tsx` already
	 * builds the team tree from a flat list exactly this way.
	 */
	fun findByTeam(teamId: UUID?): List<DocFolder> =
		DocFolders.selectAll()
			.where { if (teamId == null) Op.TRUE else DocFolders.teamId eq teamId }
			.orderBy(DocFolders.position to SortOrder.ASC, DocFolders.name to SortOrder.ASC)
			.map { it.toFolder() }

	fun insert(teamId: UUID, parentId: UUID?, name: String): DocFolder {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		DocFolders.insert {
			it[DocFolders.id] = id
			it[DocFolders.teamId] = teamId
			it[DocFolders.parentId] = parentId
			it[DocFolders.name] = name
			it[position] = nextPosition(teamId, parentId)
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun update(id: UUID, name: String, parentId: UUID?): DocFolder? {
		val changed = DocFolders.update({ DocFolders.id eq id }) {
			it[DocFolders.name] = name
			it[DocFolders.parentId] = parentId
			it[updatedAt] = OffsetDateTime.now()
		}
		return if (changed == 0) null else findById(id)
	}

	fun delete(id: UUID): Boolean = DocFolders.deleteWhere { DocFolders.id eq id } > 0

	/** Nearest first, excluding [id] — the shape `TeamRepository.ancestorIds` returns. */
	fun ancestorIds(id: UUID): List<UUID> {
		val chain = mutableListOf<UUID>()
		var parent = findById(id)?.parentId
		// A visited check rather than a depth cap: the schema forbids self-parenting and
		// the service forbids the longer cycles, so a loop reaching here means the data
		// already disagrees with both — and the walk still has to terminate.
		while (parent != null && parent !in chain) {
			chain += parent
			parent = findById(parent)?.parentId
		}
		return chain
	}

	/** Appends. Sibling counts are in the tens, so the positions are read, not aggregated. */
	private fun nextPosition(teamId: UUID, parentId: UUID?): Int {
		val siblings = DocFolders.select(DocFolders.position)
			.where {
				if (parentId == null) (DocFolders.teamId eq teamId) and DocFolders.parentId.isNull()
				else DocFolders.parentId eq parentId
			}
			.map { it[DocFolders.position] }
		return (siblings.maxOrNull() ?: -1) + 1
	}
}

internal fun ResultRow.toFolder() = DocFolder(
	id = this[DocFolders.id],
	teamId = this[DocFolders.teamId],
	parentId = this[DocFolders.parentId],
	name = this[DocFolders.name],
	position = this[DocFolders.position],
)

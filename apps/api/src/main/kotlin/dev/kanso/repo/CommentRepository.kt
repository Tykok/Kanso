package dev.kanso.repo

import dev.kanso.db.CommentMentions
import dev.kanso.db.Comments
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A comment as stored: the author and the people it named are ids until the service resolves them. */
data class CommentRecord(
	val id: UUID,
	val ticketId: UUID?,
	val docId: UUID?,
	val authorId: UUID,
	val body: String,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

@Repository
class CommentRepository {

	fun findById(id: UUID): CommentRecord? =
		Comments.selectAll().where { Comments.id eq id }.singleOrNull()?.toRecord()

	/**
	 * Oldest first. A thread is read in the order it was written — the opposite of the
	 * activity feed beside it, which is a list of the latest things to happen.
	 */
	fun forTicket(ticketId: UUID): List<CommentRecord> =
		Comments.selectAll().where { Comments.ticketId eq ticketId }
			.orderBy(Comments.createdAt to SortOrder.ASC)
			.map { it.toRecord() }

	fun insert(
		id: UUID,
		ticketId: UUID?,
		docId: UUID?,
		authorId: UUID,
		body: String,
		now: OffsetDateTime,
	): CommentRecord {
		Comments.insert {
			it[Comments.id] = id
			it[Comments.ticketId] = ticketId
			it[Comments.docId] = docId
			it[Comments.authorId] = authorId
			it[Comments.body] = body
			it[createdAt] = now
			it[updatedAt] = now
		}
		return requireNotNull(findById(id))
	}

	fun delete(id: UUID): Boolean = Comments.deleteWhere { Comments.id eq id } > 0

	// --- mentions ------------------------------------------------------------

	/** [userIds] is already distinct and already resolved; the primary key is the backstop. */
	fun setMentions(commentId: UUID, userIds: Collection<UUID>) {
		CommentMentions.deleteWhere { CommentMentions.commentId eq commentId }
		if (userIds.isNotEmpty()) {
			CommentMentions.batchInsert(userIds.distinct()) { userId ->
				this[CommentMentions.commentId] = commentId
				this[CommentMentions.userId] = userId
			}
		}
	}

	/** One query for a whole thread's mentions, instead of one per comment. */
	fun mentionsFor(commentIds: Collection<UUID>): Map<UUID, List<UUID>> =
		if (commentIds.isEmpty()) emptyMap()
		else CommentMentions.selectAll().where { CommentMentions.commentId inList commentIds }
			.groupBy({ it[CommentMentions.commentId] }, { it[CommentMentions.userId] })

	private fun ResultRow.toRecord() = CommentRecord(
		id = this[Comments.id],
		ticketId = this[Comments.ticketId],
		docId = this[Comments.docId],
		authorId = this[Comments.authorId],
		body = this[Comments.body],
		createdAt = this[Comments.createdAt],
		updatedAt = this[Comments.updatedAt],
	)
}

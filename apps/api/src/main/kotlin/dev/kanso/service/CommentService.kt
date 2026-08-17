package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.User
import dev.kanso.repo.CommentRecord
import dev.kanso.repo.CommentRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What a write carries. Exactly one parent, which the service refuses to guess: the
 * `num_nonnulls` check would refuse it too, but as a 500 from the driver rather than as
 * the 400 it always was.
 */
data class CreateComment(
	val ticketId: UUID? = null,
	val docId: UUID? = null,
	val body: String,
)

/** A comment with its people resolved — the author, and whoever it named. */
data class CommentRow(
	val id: UUID,
	val author: User,
	val body: String,
	val mentions: List<User>,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
)

/**
 * Comments, and the mentions they carry.
 *
 * Mentions are resolved here, on the way in, and stored. Re-parsing the body on read
 * would make a mention vanish the day its holder's address changes — after it had
 * already been delivered — and would put the resolution rule in every reader.
 *
 * `notion_docs` carries no team, so there is nothing for [TicketAccess] to scope a
 * comment on a document against. The column exists because `V8` is shared, but this
 * service refuses `docId` rather than opening one unscoped write: slice B, which gives
 * a document an owner, is where that stops being a 400.
 */
@Service
class CommentService(
	private val comments: CommentRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val access: TicketAccess,
	private val activity: ActivityService,
) {

	@Transactional(readOnly = true)
	fun forTicket(ticketId: UUID): List<CommentRow> = decorate(comments.forTicket(ticketId))

	@Transactional
	fun create(actor: User, request: CreateComment): CommentRow {
		// The parent decides which team the write belongs to, so it is read first — but
		// nothing is written, and nothing else is even validated, until `requireTicket`
		// has answered. Same order as `TicketService.create`, for the same reason.
		val ticketId = requireOneParent(request)
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)

		val body = request.body.trim()
		if (body.isEmpty()) throw BadRequestException("A comment needs something in it")

		val comment = comments.insert(
			id = UUID.randomUUID(),
			ticketId = ticketId,
			docId = null,
			authorId = actor.id,
			body = body,
			now = OffsetDateTime.now(),
		)
		val mentioned = resolveMentions(body)
		comments.setMentions(comment.id, mentioned.map { it.id })

		// The log names the comment rather than quoting it: an activity row outlives what
		// it describes, and a feed carrying a body would be a second copy to keep in step.
		activity.record(
			ActivityEntity.TICKET,
			ticketId,
			actor.id,
			ActivityKind.COMMENTED,
			mapOf("commentId" to comment.id.toString()),
		)
		return CommentRow(
			id = comment.id,
			author = actor,
			body = comment.body,
			mentions = mentioned,
			createdAt = comment.createdAt,
			updatedAt = comment.updatedAt,
		)
	}

	/**
	 * The team scope decides who may touch the ticket; it does not decide who may retract
	 * somebody else's sentence. So both hold: the actor passes [TicketAccess] *and* is
	 * either the author or somebody who configures the instance, which is the only
	 * moderation this schema can express — there is no edit history to fall back on.
	 */
	@Transactional
	fun delete(actor: User, id: UUID) {
		val comment = comments.findById(id) ?: throw NotFoundException("No comment $id")
		val ticketId = comment.ticketId ?: throw BadRequestException("Comment $id belongs to no ticket")
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)
		if (comment.authorId != actor.id && !actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the author can remove that comment")
		}
		comments.delete(id)
	}

	// --- helpers -------------------------------------------------------------

	private fun requireOneParent(request: CreateComment): UUID {
		if (request.docId != null) {
			throw BadRequestException(
				"Commenting on a document is not available yet: a document has no team to scope the write against",
			)
		}
		return request.ticketId
			?: throw BadRequestException("A comment belongs to one thing; name a ticketId")
	}

	/**
	 * An `@handle` nobody holds stays plain text: somebody typing an address wrong, or
	 * writing `@here`, has still said something worth keeping, and refusing the write is
	 * the one outcome that loses it.
	 *
	 * An *ambiguous* handle resolves to nobody, for the harder reason: two addresses can
	 * share a local part, and notifying the wrong person is worse than notifying no one.
	 */
	private fun resolveMentions(body: String): List<User> {
		val handles = HANDLE.findAll(body).map { it.groupValues[1].lowercase() }.toSet()
		if (handles.isEmpty()) return emptyList()
		val candidates = users.findByHandles(handles).groupBy { it.email.substringBefore('@').lowercase() }
		return handles.mapNotNull { handle -> candidates[handle]?.singleOrNull() }
	}

	private fun decorate(records: List<CommentRecord>): List<CommentRow> {
		if (records.isEmpty()) return emptyList()
		val mentions = comments.mentionsFor(records.map { it.id })
		// One query for every person on the page — authors and mentions together, because
		// the same few people are usually both.
		val ids = records.map { it.authorId }.toSet() + mentions.values.flatten()
		val people = users.findAllById(ids).associateBy { it.id }
		return records.map { record ->
			CommentRow(
				id = record.id,
				author = people.getValue(record.authorId),
				body = record.body,
				mentions = mentions[record.id].orEmpty().mapNotNull { people[it] },
				createdAt = record.createdAt,
				updatedAt = record.updatedAt,
			)
		}
	}

	private companion object {
		/**
		 * `@` followed by the characters an address's local part may hold. Deliberately
		 * not the full RFC grammar: what this has to match is what somebody types into a
		 * comment box, and a quoted local part with a space in it is not that. `%` is
		 * legal in an address and left out anyway — [UserRepository.findByHandles] looks
		 * a handle up with `LIKE`, and this class is what keeps a wildcard out of it.
		 */
		val HANDLE = Regex("""@([A-Za-z0-9._+\-]{1,64})""")
	}
}

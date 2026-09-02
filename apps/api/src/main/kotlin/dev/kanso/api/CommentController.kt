package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.TicketAccess
import dev.kanso.service.CommentRow
import dev.kanso.service.CommentService
import dev.kanso.service.CreateComment
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `docId` is on the wire because the table has it, and the service refuses it until
 * documents have a team to scope a write against — slice B's job, not this one's.
 */
data class CommentRequest(
	val ticketId: UUID? = null,
	val docId: UUID? = null,
	@field:NotBlank val body: String,
)

data class CommentResponse(
	val id: UUID,
	val author: UserResponse,
	val body: String,
	/** Resolved when the comment was written, not re-parsed from [body] now. */
	val mentions: List<UserResponse>,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(row: CommentRow) = CommentResponse(
			id = row.id,
			author = UserResponse.of(row.author),
			body = row.body,
			mentions = row.mentions.map(UserResponse::of),
			createdAt = row.createdAt,
			updatedAt = row.updatedAt,
		)
	}
}

@RestController
@RequestMapping("/api/comments")
class CommentController(
	private val comments: CommentService,
	private val currentUser: CurrentUser,
	private val access: TicketAccess,
) {

	/** Open, like every other read. A thread comes back oldest first. */
	@GetMapping
	fun list(@RequestParam ticketId: UUID): List<CommentResponse> {
		// The discussion on a draft is the draft: `create` beside this has always taken the
		// actor, and this did not.
		access.requireReadable(currentUser.require(), ticketId)
		return comments.forTicket(ticketId).map(CommentResponse::of)
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: CommentRequest): CommentResponse = CommentResponse.of(
		comments.create(
			actor = currentUser.require(),
			request = CreateComment(ticketId = request.ticketId, docId = request.docId, body = request.body),
		)
	)

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = comments.delete(currentUser.require(), id)
}

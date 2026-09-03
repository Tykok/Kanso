package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.SubTicketProgress
import dev.kanso.service.SubTicketService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Null names the top level: "this is not part of anything any more". */
data class ParentRequest(val parentId: UUID?)

/**
 * [total] already excludes cancelled children, and the two point fields are null
 * together whenever any counted child is unestimated — so a client draws a count when it
 * has one and points only when the whole parent is sized.
 */
data class SubTicketProgressResponse(
	val total: Int,
	val done: Int,
	val donePoints: Int?,
	val totalPoints: Int?,
) {
	companion object {
		fun of(progress: SubTicketProgress) = SubTicketProgressResponse(
			total = progress.total,
			done = progress.done,
			donePoints = progress.donePoints,
			totalPoints = progress.totalPoints,
		)
	}
}

/**
 * Sub-tickets. A door of its own, like the links one, and for the same reason: none of
 * this is on the create/patch path, so none of it belongs in a body that already carries
 * fifteen optional fields.
 *
 * There is no `POST` that creates a child. A sub-ticket is a ticket — it is created the
 * way every ticket is and then hung under a parent, which is one gesture more and one
 * write path fewer.
 */
@RestController
@RequestMapping("/api/tickets")
class SubTicketController(
	private val subTickets: SubTicketService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/{id}/children")
	fun children(@PathVariable id: UUID): List<TicketResponse> =
		subTickets.children(currentUser.require(), id).map(TicketResponse::of)

	/**
	 * Absent rather than zeroed when the ticket has no children: "0 of 0" is a number
	 * about nothing, and the client draws nothing when the body is null.
	 */
	@GetMapping("/{id}/progress")
	fun progress(@PathVariable id: UUID): SubTicketProgressResponse? =
		subTickets.progress(listOf(id))[id]?.let(SubTicketProgressResponse::of)

	@PutMapping("/{id}/parent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun setParent(@PathVariable id: UUID, @RequestBody request: ParentRequest) {
		subTickets.setParent(currentUser.require(), id, request.parentId)
	}

	/** Promotion to top level, spelled as erasing the relationship rather than as a null. */
	@DeleteMapping("/{id}/parent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun clearParent(@PathVariable id: UUID) {
		subTickets.setParent(currentUser.require(), id, null)
	}
}

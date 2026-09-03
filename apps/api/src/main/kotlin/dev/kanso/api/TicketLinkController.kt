package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TicketLinkType
import dev.kanso.service.TicketLinkService
import dev.kanso.service.TicketLinkView
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TicketLinkRequest(val otherId: UUID, val type: String)

/**
 * [outgoing] is what the client turns into two different sentences from one row —
 * "blocks" against "blocked by". It says nothing for a symmetric type, and the client
 * knows which those are from [symmetric] rather than from a list of its own.
 */
data class TicketLinkResponse(
	val ticket: TicketResponse,
	val type: String,
	val outgoing: Boolean,
	val symmetric: Boolean,
) {
	companion object {
		fun of(view: TicketLinkView) = TicketLinkResponse(
			ticket = TicketResponse.of(view.other),
			type = view.type.wire,
			outgoing = view.outgoing,
			symmetric = view.type.symmetric,
		)
	}
}

/**
 * The navigable ticket graph, all three kinds of edge.
 *
 * A door of its own rather than more routes on `TicketController`, and separate from the
 * `/{id}/dependencies` pair that is already there. Those two stay exactly as they are:
 * the timeline draws arrows with them, they are the scheduler's own vocabulary
 * (`predecessorId`, a `CascadeResponse`), and a route that had to mean both "order these
 * two" and "these are vaguely related" would end up with a body where half the fields
 * are ignored depending on the value of another.
 *
 * `type=blocks` here is not a second implementation of them — [TicketLinkService] hands
 * it to `ScheduleService.link`, so it refuses the same cycles and runs the same cascade.
 */
@RestController
@RequestMapping("/api/tickets")
class TicketLinkController(
	private val links: TicketLinkService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/{id}/links")
	fun list(@PathVariable id: UUID): List<TicketLinkResponse> =
		links.of(currentUser.require(), id).map(TicketLinkResponse::of)

	/**
	 * `{id}` is the `from` end — the ticket whose page the gesture was made on. For
	 * `duplicates` that is the ticket being retired, not the survivor: "this duplicates
	 * that".
	 */
	@PostMapping("/{id}/links")
	@ResponseStatus(HttpStatus.CREATED)
	fun add(@PathVariable id: UUID, @RequestBody request: TicketLinkRequest): CascadeResponse =
		CascadeResponse(links.link(currentUser.require(), id, request.otherId, linkType(request.type)))

	@DeleteMapping("/{id}/links/{type}/{otherId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(@PathVariable id: UUID, @PathVariable type: String, @PathVariable otherId: UUID) {
		links.unlink(currentUser.require(), id, otherId, linkType(type))
	}

	/**
	 * Parsed here rather than bound as an enum by Spring, which answers an unknown value
	 * with a 400 whose body names the Kotlin type and its constants. `TicketLinkType.from`
	 * throws `IllegalArgumentException` with the sentence the rest of Kanso's vocabularies
	 * answer with, and the same handler turns it into the same 400.
	 */
	private fun linkType(raw: String): TicketLinkType = TicketLinkType.from(raw)
}

package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.Accent
import dev.kanso.domain.Label
import dev.kanso.service.LabelService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `colour` is a token name from the accent vocabulary, not a hex string. */
data class LabelRequest(
	@field:NotBlank val name: String,
	val colour: String = Accent.INDIGO.wire,
)

data class LabelResponse(val id: UUID, val teamId: UUID, val name: String, val colour: String) {
	companion object {
		fun of(label: Label) = LabelResponse(label.id, label.teamId, label.name, label.colour.wire)
	}
}

/**
 * Two paths rather than one prefix: a label is owned by a team and worn by a ticket, and
 * the URL says which of the two a request is about. Reads are open, writes are scoped
 * through `TicketAccess` inside the service.
 */
@RestController
@RequestMapping("/api")
class LabelController(
	private val labels: LabelService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/teams/{teamId}/labels")
	fun forTeam(@PathVariable teamId: UUID): List<LabelResponse> =
		labels.list(teamId).map(LabelResponse::of)

	@PostMapping("/teams/{teamId}/labels")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@PathVariable teamId: UUID, @Valid @RequestBody request: LabelRequest): LabelResponse =
		LabelResponse.of(labels.create(currentUser.require(), teamId, request.name, request.colour))

	@GetMapping("/tickets/{ticketId}/labels")
	fun forTicket(@PathVariable ticketId: UUID): List<LabelResponse> =
		labels.forTicket(ticketId).map(LabelResponse::of)

	/**
	 * The whole set, like `PUT /api/tickets/{id}/assignees` beside it: the pill row knows
	 * what it wants the ticket to wear, and a pair of add/remove calls would let two
	 * clicks land in the wrong order.
	 */
	@PutMapping("/tickets/{ticketId}/labels")
	fun set(@PathVariable ticketId: UUID, @RequestBody labelIds: List<UUID>): List<LabelResponse> =
		labels.set(currentUser.require(), ticketId, labelIds).map(LabelResponse::of)
}

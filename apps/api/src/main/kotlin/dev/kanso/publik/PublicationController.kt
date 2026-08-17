package dev.kanso.publik

import dev.kanso.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * How a ticket gets into the shop window, and how it gets its "where to look" list.
 *
 * Under `/api/tickets/{id}` rather than under `/api/public`, because that is what these
 * are: two more scoped writes on a ticket, gated by the same `TicketAccess` as every
 * other one. Nothing about them is public except their consequence, and putting them
 * beside the anonymous routes would have invited somebody to open the whole prefix.
 */
@RestController
@RequestMapping("/api/tickets/{id}")
class PublicationController(
	private val publication: PublicationService,
	private val currentUser: CurrentUser,
) {

	@PatchMapping("/publication")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun publish(@PathVariable id: UUID, @RequestBody request: PublicationRequest) {
		publication.publish(currentUser.require(), id, request.public)
	}

	@PutMapping("/where-to-look")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun whereToLook(@PathVariable id: UUID, @Valid @RequestBody request: WhereToLookRequest) {
		publication.whereToLook(currentUser.require(), id, request.files.map { it.toDomain() })
	}
}

package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.FieldInUse
import dev.kanso.service.CustomFieldService
import dev.kanso.service.TicketFieldService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `type` is a token from the closed vocabulary — `text`, `number`, `boolean`, `select` — and
 * `options` is required for the last of those and refused for the other three.
 */
data class CustomFieldRequest(
	@field:NotBlank val name: String,
	@field:NotBlank val type: String,
	val required: Boolean = false,
	val options: List<String> = emptyList(),
)

/**
 * The same body without the type, because a field cannot be retyped — see
 * `CustomFieldRepository.update`. [type] is still accepted so a client may send back the
 * object it read; sending a *different* one is refused rather than ignored.
 */
data class CustomFieldPatchRequest(
	@field:NotBlank val name: String,
	val type: String? = null,
	val required: Boolean = false,
	val options: List<String> = emptyList(),
)

data class CustomFieldResponse(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val type: String,
	val required: Boolean,
	val options: List<String>,
	/**
	 * How many tickets already hold a value for this field. Derived on read, stored nowhere.
	 *
	 * It is here so the delete confirmation can say what it is about to destroy: removing a
	 * definition cascades to its values, and a dialog that cannot name a number is asking for
	 * a signature on a blank cheque.
	 */
	val valueCount: Int,
) {
	companion object {
		fun of(inUse: FieldInUse) = CustomFieldResponse(
			id = inUse.field.id,
			teamId = inUse.field.teamId,
			name = inUse.field.name,
			type = inUse.field.type.wire,
			required = inUse.field.required,
			options = inUse.field.options,
			valueCount = inUse.valueCount,
		)
	}
}

/**
 * Two paths rather than one prefix, the shape `LabelController` already has: a field is
 * *owned by a team* and *valued on a ticket*, and the URL says which of the two a request is
 * about. Reads are open, writes are scoped through `TicketAccess` inside the services.
 *
 * The definition's own writes hang off `/api/fields/{fieldId}` and not off
 * `/api/teams/{teamId}/fields/{fieldId}`. A field id is unique and already names its team, so
 * the longer path would carry a second answer to "which team" that a request could get wrong
 * — and the server would then have to decide which of the two to believe.
 *
 * **Nothing here is in `ReadOnlySeat`, and that is the whole of the seat's story for this
 * feature.** That list is an allow-list of the writes a reader may still make; deny is the
 * default, so these four writes are refused to a viewer by a file this one never mentions,
 * including the two that did not exist when it was written.
 */
@RestController
@RequestMapping("/api")
class CustomFieldController(
	private val definitions: CustomFieldService,
	private val values: TicketFieldService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/teams/{teamId}/fields")
	fun forTeam(@PathVariable teamId: UUID): List<CustomFieldResponse> =
		definitions.list(teamId).map(CustomFieldResponse::of)

	@PostMapping("/teams/{teamId}/fields")
	@ResponseStatus(HttpStatus.CREATED)
	fun define(
		@PathVariable teamId: UUID,
		@Valid @RequestBody request: CustomFieldRequest,
	): CustomFieldResponse {
		val field = definitions.define(
			actor = currentUser.require(),
			teamId = teamId,
			name = request.name,
			type = request.type,
			required = request.required,
			options = request.options,
		)
		// Freshly defined, so nothing can hold a value for it yet — said as a literal rather
		// than counted, because a query that can only answer zero is a query worth not making.
		return CustomFieldResponse.of(FieldInUse(field, valueCount = 0))
	}

	@PutMapping("/fields/{fieldId}")
	fun redefine(
		@PathVariable fieldId: UUID,
		@Valid @RequestBody request: CustomFieldPatchRequest,
	): CustomFieldResponse {
		val field = definitions.redefine(
			actor = currentUser.require(),
			fieldId = fieldId,
			name = request.name,
			type = request.type,
			required = request.required,
			options = request.options,
		)
		return definitions.list(field.teamId).first { it.field.id == field.id }.let(CustomFieldResponse::of)
	}

	/** The values go with it. `valueCount` on the read above is what the confirmation prints. */
	@DeleteMapping("/fields/{fieldId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(@PathVariable fieldId: UUID) {
		definitions.remove(currentUser.require(), fieldId)
	}

	/** Keyed by field id, like every other id-bearing shape the server sends. */
	@GetMapping("/tickets/{ticketId}/fields")
	fun forTicket(@PathVariable ticketId: UUID): Map<String, Any> =
		wire(values.valuesOf(currentUser.require(), ticketId))

	/**
	 * The fields named in the body, and only those: an absent key is untouched and a null one
	 * is cleared. `TicketFieldService.setValues` carries the argument for why this is a
	 * partial write where the labels beside it are a whole-set replace.
	 */
	@PutMapping("/tickets/{ticketId}/fields")
	fun setForTicket(
		@PathVariable ticketId: UUID,
		@RequestBody wanted: Map<String, Any?>,
	): Map<String, Any> = wire(values.setValues(currentUser.require(), ticketId, wanted))

	/** UUID keys as text, because a JSON object has no other kind. */
	private fun wire(values: Map<UUID, Any>): Map<String, Any> =
		values.entries.associate { (id, value) -> id.toString() to value }
}

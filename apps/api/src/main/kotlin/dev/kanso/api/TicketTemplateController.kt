package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketTemplate
import dev.kanso.service.ResolvedTemplate
import dev.kanso.service.TemplateBodyCodec
import dev.kanso.service.TicketTemplateService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The body arrives as the same document it is stored as, and goes through
 * [TemplateBodyCodec] on the way in — so a malformed template is refused with the
 * vocabulary named, at the edge, rather than by a driver error at the insert.
 */
data class TemplateRequest(
	/** Null is the instance level. Read on create; an update never moves a level. */
	val teamId: UUID? = null,
	@field:NotBlank val name: String,
	val summary: String? = null,
	val body: Map<String, Any?> = emptyMap(),
	val categories: List<String> = emptyList(),
)

data class TemplateResponse(
	val id: UUID,
	/** Null means Kanso ships it. The picker's two groups are drawn off this and nothing else. */
	val teamId: UUID?,
	val name: String,
	val summary: String?,
	val body: Map<String, Any?>,
	val categories: List<String>,
)

data class UnresolvedResponse(val labels: List<String>, val fields: List<String>)

data class ResolvedResponse(
	val title: String?,
	val description: String?,
	val priority: String?,
	val estimate: Int?,
	val labelIds: List<UUID>,
	/** Field id to value, ready for `PUT /api/tickets/{id}/fields`. */
	val fieldValues: Map<UUID, Any>,
	val unresolved: UnresolvedResponse,
)

/**
 * Under `/api/tickets/templates` rather than `/api/templates`, because `templates` alone is a
 * word `doc_templates` already has a claim on — `/api/docs/templates` is live — and a bare
 * `/api/templates` would be the third thing in this product called a template with nothing in
 * the path to say which.
 *
 * **Nothing here goes in `ReadOnlySeat`.** That list is an allow-list of the writes a reader
 * may still make; deny is the default, so the three writes below are refused to a viewer by a
 * file this one never mentions — and `ReadOnlySeatLeakTest` proves it by enumerating off
 * `RequestMappingHandlerMapping` rather than off a list somebody typed here.
 *
 * Nothing here needs a `docker/Caddyfile` entry either: every route is under `/api`, which the
 * routing table already covers wholesale.
 */
@RestController
@RequestMapping("/api/tickets/templates")
class TicketTemplateController(
	private val templates: TicketTemplateService,
	private val codec: TemplateBodyCodec,
	private val currentUser: CurrentUser,
) {

	/**
	 * Without a `teamId` this is the instance catalogue alone, which is what the settings screen
	 * asks for when an admin is editing the shipped templates rather than filing a ticket. With
	 * one it is that catalogue plus the team's own.
	 */
	@GetMapping
	fun list(@RequestParam(required = false) teamId: UUID?): List<TemplateResponse> =
		templates.list(teamId).map { it.toResponse() }

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TemplateResponse = templates.get(id).toResponse()

	@GetMapping("/{id}/resolved")
	fun resolved(
		@PathVariable id: UUID,
		@RequestParam(required = false) teamId: UUID?,
	): ResolvedResponse = templates.resolved(id, teamId).toResponse()

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TemplateRequest): TemplateResponse =
		templates.create(
			actor = currentUser.require(),
			teamId = request.teamId,
			name = request.name,
			summary = request.summary,
			body = request.decodedBody(),
			categories = request.categories,
		).toResponse()

	/**
	 * [TemplateRequest.teamId] is ignored here rather than refused, unlike a custom field's
	 * type. A client sending back the object it read is the ordinary case and the level it
	 * carries is the level the row already has; the one gesture that would *change* it does not
	 * exist — see `TicketTemplateService.update`.
	 */
	@PutMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: TemplateRequest,
	): TemplateResponse =
		templates.update(
			actor = currentUser.require(),
			id = id,
			name = request.name,
			summary = request.summary,
			body = request.decodedBody(),
			categories = request.categories,
		).toResponse()

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = templates.delete(currentUser.require(), id)

	private fun TemplateRequest.decodedBody(): TemplateBody = codec.decode(codec.encodeRaw(body))

	private fun TicketTemplate.toResponse() = TemplateResponse(
		id = id,
		teamId = teamId,
		name = name,
		summary = summary,
		body = codec.toMap(body),
		categories = categories,
	)

	private fun ResolvedTemplate.toResponse() = ResolvedResponse(
		title = title,
		description = description,
		priority = priority?.wire,
		estimate = estimate,
		labelIds = labelIds,
		fieldValues = fieldValues,
		unresolved = UnresolvedResponse(unresolved.labels, unresolved.fields),
	)
}

package dev.kanso.api

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/tickets")
class TicketController(private val tickets: TicketService) {

	@GetMapping
	fun list(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(defaultValue = "false") includeDescendants: Boolean,
		@RequestParam(required = false) projectId: UUID?,
		@RequestParam(required = false) status: List<String>?,
		@RequestParam(required = false) assigneeId: UUID?,
		@RequestParam(defaultValue = "false") includeArchived: Boolean,
		@RequestParam(defaultValue = "200") limit: Int,
		@RequestParam(defaultValue = "0") offset: Long,
	): List<TicketResponse> = tickets.search(
		teamId = teamId,
		includeDescendants = includeDescendants,
		projectId = projectId,
		statuses = status.orEmpty().map(TicketStatus::from),
		assigneeId = assigneeId,
		includeArchived = includeArchived,
		limit = limit.coerceIn(1, 500),
		offset = offset.coerceAtLeast(0),
	).map(TicketResponse::of)

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TicketResponse = TicketResponse.of(tickets.get(id))

	/** Lookup by the identifier people actually use: `/api/tickets/by-key/KAN/142`. */
	@GetMapping("/by-key/{teamKey}/{number}")
	fun getByKey(@PathVariable teamKey: String, @PathVariable number: Int): TicketResponse =
		TicketResponse.of(tickets.getByIdentifier(teamKey, number))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TicketCreateRequest): TicketResponse = TicketResponse.of(
		tickets.create(
			teamId = request.teamId,
			title = request.title,
			description = request.description,
			status = TicketStatus.from(request.status),
			priority = TicketPriority.from(request.priority),
			startDate = request.startDate,
			dueDate = request.dueDate,
			projectId = request.projectId,
			assigneeIds = request.assigneeIds,
			docIds = request.docIds,
		)
	)

	@PatchMapping("/{id}")
	fun patch(@PathVariable id: UUID, @RequestBody request: TicketPatchRequest): TicketResponse {
		val patch = request.validated().let {
			TicketPatch(
				title = it.title,
				description = it.description,
				status = it.status?.let(TicketStatus::from),
				priority = it.priority?.let(TicketPriority::from),
				startDate = it.startDate,
				dueDate = it.dueDate,
				projectId = it.projectId,
				teamId = it.teamId,
				archived = it.archived,
				assigneeIds = it.assigneeIds,
				docIds = it.docIds,
				unset = it.unset,
			)
		}
		return TicketResponse.of(tickets.patch(id, patch))
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = tickets.delete(id)

	@PutMapping("/{id}/assignees")
	fun setAssignees(@PathVariable id: UUID, @RequestBody userIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setAssignees(id, userIds))

	@PutMapping("/{id}/docs")
	fun setDocs(@PathVariable id: UUID, @RequestBody docIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setDocs(id, docIds))
}

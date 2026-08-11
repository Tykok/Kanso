package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/tickets")
class TicketController(
	private val tickets: TicketService,
	private val schedule: ScheduleService,
	private val currentUser: CurrentUser,
) {

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
			start = request.start?.toDomain(),
			due = request.due?.toDomain(),
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
				start = it.start?.toDomain(),
				due = it.due?.toDomain(),
				projectId = it.projectId,
				teamId = it.teamId,
				archived = it.archived,
				assigneeIds = it.assigneeIds,
				docIds = it.docIds,
				unset = it.unset,
			)
		}
		return TicketResponse.of(tickets.patch(currentUser.require(), id, patch))
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = tickets.delete(currentUser.require(), id)

	@PutMapping("/{id}/assignees")
	fun setAssignees(@PathVariable id: UUID, @RequestBody userIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setAssignees(currentUser.require(), id, userIds))

	@PutMapping("/{id}/docs")
	fun setDocs(@PathVariable id: UUID, @RequestBody docIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setDocs(currentUser.require(), id, docIds))

	/**
	 * `POST /api/tickets/{id}/dependencies` — `{id}` is the successor, the body names
	 * the predecessor, which is the direction the arrow is drawn in the timeline.
	 */
	@PostMapping("/{id}/dependencies")
	@ResponseStatus(HttpStatus.CREATED)
	fun addDependency(
		@PathVariable id: UUID,
		@RequestBody request: DependencyRequest,
	): CascadeResponse = CascadeResponse(schedule.link(currentUser.require(), request.predecessorId, id))

	@DeleteMapping("/{id}/dependencies/{predecessorId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeDependency(@PathVariable id: UUID, @PathVariable predecessorId: UUID) {
		schedule.unlink(currentUser.require(), predecessorId, id)
	}
}

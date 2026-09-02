package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.ProjectHealth
import dev.kanso.domain.ProjectStatus
import dev.kanso.service.ProjectService
import dev.kanso.service.ProjectUpdateRow
import dev.kanso.service.ProjectUpdateService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What posting an update carries. No `at`: an update is dated when it is written, and a
 * caller-supplied date would let somebody backfill a history nobody lived through — the
 * one thing that would make this table unreadable as evidence.
 *
 * `health` is a raw string parsed at this edge by [ProjectHealth.from], the same way
 * `status` is, so an unknown word is a 400 naming the vocabulary rather than a 500 from
 * the CHECK underneath.
 */
data class ProjectUpdateRequest(
	val health: String,
	@field:NotBlank val body: String,
)

data class ProjectUpdateResponse(
	val id: UUID,
	val projectId: UUID,
	val health: String,
	val body: String,
	/** Null once the account is gone. The assessment it left behind is not. */
	val author: UserResponse?,
	val at: OffsetDateTime,
) {
	companion object {
		fun of(row: ProjectUpdateRow) = ProjectUpdateResponse(
			id = row.id,
			projectId = row.projectId,
			health = row.health.wire,
			body = row.body,
			author = row.author?.let(UserResponse::of),
			at = row.at,
		)
	}
}

@RestController
@RequestMapping("/api/projects")
class ProjectController(
	private val projects: ProjectService,
	private val updates: ProjectUpdateService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(
		@RequestParam(required = false) teamId: UUID?,
		/** Include projects of nested teams — the recursive hierarchy in practice. */
		@RequestParam(defaultValue = "false") includeDescendants: Boolean,
		@RequestParam(defaultValue = "false") includeArchived: Boolean,
	): List<ProjectResponse> =
		projects.list(teamId, includeDescendants, includeArchived).map(ProjectResponse::of)

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): ProjectResponse = ProjectResponse.of(projects.get(id))

	@GetMapping("/{id}/contents")
	fun contents(@PathVariable id: UUID): DispositionContentsResponse =
		DispositionContentsResponse.of(projects.contents(id))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: ProjectRequest): ProjectResponse = ProjectResponse.of(
		projects.create(
			actor = currentUser.require(),
			name = request.name,
			status = ProjectStatus.from(request.status),
			start = request.start?.toDomain(),
			end = request.end?.toDomain(),
			leadUserId = request.leadUserId,
			teamId = request.teamId,
			docIds = request.docIds.orEmpty(),
		)
	)

	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: ProjectRequest): ProjectResponse =
		ProjectResponse.of(
			projects.update(
				actor = currentUser.require(),
				id = id,
				name = request.name,
				status = ProjectStatus.from(request.status),
				start = request.start?.toDomain(),
				end = request.end?.toDomain(),
				leadUserId = request.leadUserId,
				teamId = request.teamId,
				docIds = request.docIds,
			)
		)

	@PutMapping("/{id}/archive")
	fun archive(@PathVariable id: UUID, @RequestBody request: DispositionPlanRequest): ProjectResponse =
		ProjectResponse.of(projects.archive(currentUser.require(), id, request.toPlan()))

	@PostMapping("/{id}/unarchive")
	fun unarchive(@PathVariable id: UUID): ProjectResponse =
		ProjectResponse.of(projects.unarchive(currentUser.require(), id))

	/** Body optional so a missing one yields the service's message, not Spring's. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID, @RequestBody(required = false) request: DispositionPlanRequest?) =
		projects.delete(currentUser.require(), id, (request ?: DispositionPlanRequest()).toPlan())

	// --- health updates ------------------------------------------------------
	//
	// A sub-resource of the project rather than a top-level `/api/project-updates`: an
	// update belongs to exactly one project and is never read across projects — the one
	// cross-project question, "which of these is in trouble", is answered by the `health`
	// on the project itself, which `GET /api/projects` already carries.

	/** Newest first, and open like every other GET here. */
	@GetMapping("/{id}/updates")
	fun updates(@PathVariable id: UUID): List<ProjectUpdateResponse> =
		updates.forProject(id).map(ProjectUpdateResponse::of)

	@PostMapping("/{id}/updates")
	@ResponseStatus(HttpStatus.CREATED)
	fun postUpdate(
		@PathVariable id: UUID,
		@Valid @RequestBody request: ProjectUpdateRequest,
	): ProjectUpdateResponse = ProjectUpdateResponse.of(
		updates.post(
			actor = currentUser.require(),
			projectId = id,
			health = ProjectHealth.from(request.health),
			body = request.body,
		)
	)
}

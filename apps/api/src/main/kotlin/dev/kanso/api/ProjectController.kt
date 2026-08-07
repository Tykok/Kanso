package dev.kanso.api

import dev.kanso.domain.ProjectStatus
import dev.kanso.service.ProjectService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/projects")
class ProjectController(private val projects: ProjectService) {

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
	fun contents(@PathVariable id: UUID): DispositionCountsResponse =
		DispositionCountsResponse.of(projects.contents(id))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: ProjectRequest): ProjectResponse = ProjectResponse.of(
		projects.create(
			name = request.name,
			status = ProjectStatus.from(request.status),
			startDate = request.startDate,
			endDate = request.endDate,
			leadUserId = request.leadUserId,
			teamId = request.teamId,
			docIds = request.docIds.orEmpty(),
		)
	)

	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: ProjectRequest): ProjectResponse =
		ProjectResponse.of(
			projects.update(
				id = id,
				name = request.name,
				status = ProjectStatus.from(request.status),
				startDate = request.startDate,
				endDate = request.endDate,
				leadUserId = request.leadUserId,
				teamId = request.teamId,
				archived = request.archived,
				docIds = request.docIds,
			)
		)

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = projects.delete(id)
}

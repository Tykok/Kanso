package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.MemberRole
import dev.kanso.service.TeamService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/teams")
class TeamController(
	private val teams: TeamService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(@RequestParam(defaultValue = "false") includeArchived: Boolean): List<TeamResponse> =
		teams.list(includeArchived).map(TeamResponse::of)

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TeamResponse = TeamResponse.of(teams.get(id))

	/** The team and every team under it, at any depth. */
	@GetMapping("/{id}/descendants")
	fun descendants(@PathVariable id: UUID): List<TeamResponse> =
		teams.descendants(id).map(TeamResponse::of)

	/** What the modal shows before anyone chooses anything. */
	@GetMapping("/{id}/contents")
	fun contents(@PathVariable id: UUID): DispositionContentsResponse =
		DispositionContentsResponse.of(teams.contents(id))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TeamRequest): TeamResponse = TeamResponse.of(
		teams.create(currentUser.require(), request.name, request.key?.uppercase(), request.parentTeamId)
	)

	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: TeamRequest): TeamResponse {
		val actor = currentUser.require()
		val current = teams.get(id)
		return TeamResponse.of(
			teams.update(
				actor = actor,
				id = id,
				name = request.name,
				key = request.key?.uppercase() ?: current.key,
				parentTeamId = request.parentTeamId,
			)
		)
	}

	@PutMapping("/{id}/archive")
	fun archive(@PathVariable id: UUID, @RequestBody request: DispositionPlanRequest): TeamResponse =
		TeamResponse.of(teams.archive(currentUser.require(), id, request.toPlan()))

	@PostMapping("/{id}/unarchive")
	fun unarchive(@PathVariable id: UUID): TeamResponse =
		TeamResponse.of(teams.unarchive(currentUser.require(), id))

	/**
	 * The body is optional at this layer so a request without one gets the service's
	 * own message about the missing counts rather than Spring's "required request body
	 * is missing".
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID, @RequestBody(required = false) request: DispositionPlanRequest?) =
		teams.delete(currentUser.require(), id, (request ?: DispositionPlanRequest()).toPlan())

	@GetMapping("/{id}/members")
	fun members(@PathVariable id: UUID): List<MemberResponse> =
		teams.members(id).map(MemberResponse::of)

	@PostMapping("/{id}/members")
	fun addMember(@PathVariable id: UUID, @RequestBody request: AddMemberRequest): List<MemberResponse> =
		teams.addMember(currentUser.require(), id, request.userId, MemberRole.from(request.role))
			.map(MemberResponse::of)

	@DeleteMapping("/{id}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeMember(@PathVariable id: UUID, @PathVariable userId: UUID) =
		teams.removeMember(currentUser.require(), id, userId)
}

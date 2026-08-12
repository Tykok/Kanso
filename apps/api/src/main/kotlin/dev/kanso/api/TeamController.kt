package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.User
import dev.kanso.service.TeamService
import dev.kanso.service.TicketAccess
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/teams")
class TeamController(
	private val teams: TeamService,
	private val access: TicketAccess,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(@RequestParam(defaultValue = "false") includeArchived: Boolean): List<TeamResponse> {
		val actor = currentUser.require()
		val found = teams.list(includeArchived)
		// One editableTeams call for the whole page, not one per row — the same batching
		// TimelineService does for `TimelineTicketResponse.editable`.
		val editable = access.editableTeams(actor, found.map { it.id }.toSet())
		return found.map { TeamResponse.of(it, it.id in editable) }
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TeamResponse {
		val actor = currentUser.require()
		val team = teams.get(id)
		return TeamResponse.of(team, editableOf(actor, team))
	}

	/** The team and every team under it, at any depth. */
	@GetMapping("/{id}/descendants")
	fun descendants(@PathVariable id: UUID): List<TeamResponse> {
		val actor = currentUser.require()
		val found = teams.descendants(id)
		val editable = access.editableTeams(actor, found.map { it.id }.toSet())
		return found.map { TeamResponse.of(it, it.id in editable) }
	}

	/** What the modal shows before anyone chooses anything. */
	@GetMapping("/{id}/contents")
	fun contents(@PathVariable id: UUID): DispositionContentsResponse =
		DispositionContentsResponse.of(teams.contents(id))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TeamRequest): TeamResponse {
		val actor = currentUser.require()
		val team = teams.create(actor, request.name, request.key?.uppercase(), request.parentTeamId)
		return TeamResponse.of(team, editableOf(actor, team))
	}

	@PutMapping("/{id}")
	fun update(@PathVariable id: UUID, @Valid @RequestBody request: TeamRequest): TeamResponse {
		val actor = currentUser.require()
		val current = teams.get(id)
		val updated = teams.update(
			actor = actor,
			id = id,
			name = request.name,
			key = request.key?.uppercase() ?: current.key,
			parentTeamId = request.parentTeamId,
		)
		return TeamResponse.of(updated, editableOf(actor, updated))
	}

	@PutMapping("/{id}/archive")
	fun archive(@PathVariable id: UUID, @RequestBody request: DispositionPlanRequest): TeamResponse {
		val actor = currentUser.require()
		val archived = teams.archive(actor, id, request.toPlan())
		return TeamResponse.of(archived, editableOf(actor, archived))
	}

	@PostMapping("/{id}/unarchive")
	fun unarchive(@PathVariable id: UUID): TeamResponse {
		val actor = currentUser.require()
		val unarchived = teams.unarchive(actor, id)
		return TeamResponse.of(unarchived, editableOf(actor, unarchived))
	}

	private fun editableOf(actor: User, team: Team): Boolean =
		access.editableTeams(actor, setOf(team.id)).contains(team.id)

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

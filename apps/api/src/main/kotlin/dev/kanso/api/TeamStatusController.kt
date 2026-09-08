package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import dev.kanso.service.TeamStatusService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A team's words — `KAN-28`. Beside `LabelController`, which is the same shape for the
 * same reason: a team-scoped catalogue somebody edits, read whole because every read of it
 * is a list a screen draws.
 *
 * `POST` and `DELETE` arrived with `KAN-90`, once `Ticket.status` became the key of one of
 * the team's own statuses rather than an enum. The rest writes a word or an order.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/statuses")
class TeamStatusController(
	private val statuses: TeamStatusService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(@PathVariable teamId: UUID): List<TeamStatusDto> =
		statuses.list(currentUser.require(), teamId).map(TeamStatusDto::of)

	/**
	 * A word and what it means. The key is derived and never sent — see `statusKeyOf`.
	 *
	 * `category` is required rather than defaulted, and that is the shape carrying the
	 * decision: `TeamStatusService.add` will not let it change afterwards, so the one
	 * moment it can be chosen is the one where somebody is deciding what the word means.
	 * A default would answer that question for them, silently, in whichever way the
	 * server's author guessed.
	 */
	@PostMapping
	fun add(
		@PathVariable teamId: UUID,
		@Valid @RequestBody request: TeamStatusAddRequest,
	): TeamStatusDto = TeamStatusDto.of(
		statuses.add(currentUser.require(), teamId, request.label, StatusCategory.from(request.category)),
	)

	/**
	 * `into` as a query parameter rather than a body, because a `DELETE` with a body is
	 * refused or dropped by enough proxies that it is not a shape to rely on — and because
	 * it reads as what it is: not part of the thing being deleted, but where its tickets
	 * go. Absent is a real answer, and the service accepts it exactly when the status
	 * holds nothing.
	 */
	@DeleteMapping("/{key}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun remove(
		@PathVariable teamId: UUID,
		@PathVariable key: String,
		@RequestParam(required = false) into: String?,
	) = statuses.remove(currentUser.require(), teamId, key, into)

	@PatchMapping("/{key}")
	fun rename(
		@PathVariable teamId: UUID,
		@PathVariable key: String,
		@Valid @RequestBody request: TeamStatusRenameRequest,
	): TeamStatusDto = TeamStatusDto.of(statuses.rename(currentUser.require(), teamId, key, request.label))

	/**
	 * The whole order, at `/order` rather than as a `position` on each row.
	 *
	 * A per-row position would be several requests for one gesture, and the service refuses
	 * a partial order anyway — so the endpoint that cannot express one is the honest shape.
	 */
	@PutMapping("/order")
	fun reorder(
		@PathVariable teamId: UUID,
		@Valid @RequestBody request: TeamStatusOrderRequest,
	): List<TeamStatusDto> =
		statuses.reorder(currentUser.require(), teamId, request.keys).map(TeamStatusDto::of)
}

data class TeamStatusAddRequest(
	/** 40 because a bucket header has to fit on a board column — [TeamStatusRenameRequest]. */
	@field:NotBlank @field:Size(max = 40) val label: String,
	/** One of `StatusCategory`'s five. Parsed, so an unknown one is a 400 with the list. */
	@field:NotBlank val category: String,
)

data class TeamStatusRenameRequest(
	/** 40 because a bucket header has to fit on a board column. */
	@field:NotBlank @field:Size(max = 40) val label: String,
)

data class TeamStatusOrderRequest(val keys: List<String>)

/** The catalogue row as every payload carries it — including `TeamResponse`. */
data class TeamStatusDto(
	val key: String,
	val label: String,
	val category: String,
	val position: Int,
) {
	companion object {
		fun of(status: TeamStatus) =
			TeamStatusDto(status.key, status.label, status.category.wire, status.position)
	}
}

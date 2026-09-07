package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TeamStatus
import dev.kanso.service.TeamStatusService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A team's words — `KAN-28`. Beside `LabelController`, which is the same shape for the
 * same reason: a team-scoped catalogue somebody edits, read whole because every read of it
 * is a list a screen draws.
 *
 * There is no `POST` and no `DELETE`, and their absence is the ticket's boundary rather
 * than an omission: adding or removing a status makes `Ticket.status` unrepresentable as
 * an enum, which is `KAN-90`. What is here writes a word or an order.
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

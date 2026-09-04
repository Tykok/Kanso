package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.sync.inbound.RequestBaseService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One registered requests base.
 *
 * The ids travel because this route is the configurator's and they are the whole content of
 * the setting — the same reading `SyncAdminController.detail` makes, guarded the same way
 * and for the same reason `status` had to stop making it: an id names a page in a workspace
 * the instance does not own. There is no member-facing summary beside this one because
 * nothing member-facing asks the question; a member sees the *tickets*, in the queue.
 */
data class RequestBaseResponse(val dataSourceId: String, val databaseId: String, val teamId: String)

data class RegisterRequestBaseRequest(
	val dataSourceId: String,
	val databaseId: String,
	val teamId: UUID,
)

/**
 * Which Notion base Kanso siphons requests out of.
 *
 * Its own controller rather than four more methods on `SyncAdminController`: that class is
 * the *mirror's* operational surface — what it published, what is queued, what Notion
 * refused — and a requests base is the one Notion relationship it has nothing to say about.
 * `V37` keeps the two apart in the schema for a stronger version of the same reason.
 */
@RestController
@RequestMapping("/api/admin/notion/requests")
class RequestBaseController(
	private val bases: RequestBaseService,
	private val currentUser: CurrentUser,
) {

	@GetMapping
	fun list(): List<RequestBaseResponse> {
		requireInstanceAdmin()
		return bases.all().map { RequestBaseResponse(it.dataSourceId, it.databaseId, it.teamId.toString()) }
	}

	@PostMapping
	fun register(@RequestBody request: RegisterRequestBaseRequest): RequestBaseResponse {
		requireInstanceAdmin()
		val base = bases.register(request.dataSourceId, request.databaseId, request.teamId)
		return RequestBaseResponse(base.dataSourceId, base.databaseId, base.teamId.toString())
	}

	/**
	 * `204`, like every other delete in this package — and it had to be said out loud rather
	 * than left to Spring.
	 *
	 * A `Unit`-returning handler with no status annotation answers `200` with an empty body,
	 * and `lib/api/core.ts`'s `request` short-circuits on `204` alone: on `200` it calls
	 * `response.json()`, which throws on nothing at all. So the row disappeared from Postgres
	 * and stayed on screen, the mutation having been told it failed. It was invisible while
	 * this route had no caller but `curl`, which is what `KAN-21` shipping the API alone left
	 * behind, and it went red the first time a button was wired to it.
	 */
	@DeleteMapping("/{dataSourceId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun unregister(@PathVariable dataSourceId: String) {
		requireInstanceAdmin()
		bases.unregister(dataSourceId)
	}

	/** `SyncAdminController`'s sentence, said the same way, for the same one rule. */
	private fun requireInstanceAdmin() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the instance owner or an admin can change these settings")
		}
	}
}

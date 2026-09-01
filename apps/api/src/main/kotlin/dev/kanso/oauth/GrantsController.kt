package dev.kanso.oauth

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * "Connected applications", the screen behind the only word that matters here: Revoke.
 *
 * Under `/api`, so it is authenticated by the session like every other Kanso endpoint and
 * needs no entry in [OAuthRoutes] — the open routes are the ones a machine calls without
 * a browser, and this is the opposite of that. Deliberately unreachable by an agent
 * holding a bearer token as well: an application that could revoke the grants of the
 * member who installed it is an application that can lock them out of removing it.
 *
 * No logic. The queries are [GrantService]'s and the isolation is too — the service reads
 * the member from the security context rather than from anything this file passes it.
 */
@RestController
@RequestMapping("/api/oauth/grants")
class GrantsController(private val grants: GrantService) {

	@GetMapping
	fun list(): List<GrantSummary> = grants.list()

	/**
	 * The client id, not a Kanso row id: it is what the member sees, what their agent's
	 * configuration prints, and the only handle either of them has on this grant.
	 */
	@DeleteMapping("/{clientId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun revoke(@PathVariable clientId: String) = grants.revoke(clientId)
}

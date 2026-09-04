package dev.kanso.github

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.service.BadRequestException
import dev.kanso.settings.InstanceSettingsService
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.time.OffsetDateTime

/** The App's OAuth client, pasted rather than received from a manifest conversion. */
data class GithubAppRequest(val clientId: String, val clientSecret: String? = null)

/** Where to send the browser, and the URI the App has to have registered. */
data class GithubAuthorizeResponse(val url: String, val redirectUri: String)

/**
 * A member's GitHub link, as a screen needs to read it.
 *
 * **[linked] is always present and everything about the link is absent when it is false**,
 * which is the shape `TicketResponse.pullRequests` argues for at length and the trap this
 * repository has already paid for once. The shared mapper is configured
 * `default-property-inclusion: non_null`, so a null field is *missing from the JSON*, not
 * `null` in it — a hand-written TypeScript type saying `login: string | null` would be
 * wrong in a way no compiler catches, and the last time that happened a screen read
 * "Last used Invalid Date". So the TypeScript mirror spells these `login?: string`, and
 * "is there a link at all" has exactly one spelling.
 */
data class GithubLinkResponse(
	/** Whether an App exists for consent to be asked *through*. Not whether anybody consented. */
	val appConfigured: Boolean,
	val appManagedByEnvironment: Boolean,
	val linked: Boolean,
	val login: String?,
	/** [GithubTokenState], computed on read. Absent when there is no link. */
	val tokenState: String?,
	/** Absent for a token GitHub issued without an expiry, which is a real shape. */
	val expiresAt: OffsetDateTime?,
	val linkedAt: OffsetDateTime?,
) {
	companion object {
		fun of(
			appConfigured: Boolean,
			appManagedByEnvironment: Boolean,
			account: GithubAccount?,
			now: OffsetDateTime,
		) = GithubLinkResponse(
			appConfigured = appConfigured,
			appManagedByEnvironment = appManagedByEnvironment,
			linked = account != null,
			login = account?.githubLogin,
			tokenState = account?.tokenState(now)?.wire,
			expiresAt = account?.expiresAt,
			linkedAt = account?.linkedAt,
		)
	}
}

/**
 * The member's own consent flow, from a button to a row in `github_accounts`.
 *
 * Every route here is behind the member's own session and none is opened in
 * `SecurityConfig`, including the callback — which is the part worth stating, because the
 * neighbouring OAuth work needed the opposite. `ConsentPage.kt` records that a session
 * cookie is `SameSite=Lax` and therefore sends *nothing* on a cross-site POST; a callback
 * from `github.com` is a top-level GET navigation, which `Lax` does send. So this flow
 * needs no widening of the open-route list, and adding one would hand a stranger the
 * ability to write somebody's link.
 *
 * The callback is a **GET that writes**, which `UnguardedWriteTest` does not sweep for —
 * it enumerates POST, PUT, PATCH and DELETE. It cannot be a POST: the request is made by
 * GitHub redirecting a browser. The protection is therefore the session plus the state
 * comparison, and `CurrentUser` is held here so that the sweep's rule is satisfied for the
 * routes it does see and so that no route in this file can forget whose link it is
 * touching.
 */
@RestController
@RequestMapping("/api/github")
class GithubLinkController(
	private val currentUser: CurrentUser,
	private val settings: InstanceSettingsService,
	private val oauth: GithubUserOAuth,
	private val accounts: GithubAccountRepository,
	private val props: KansoProperties,
) {

	private val log = LoggerFactory.getLogger(GithubLinkController::class.java)

	/**
	 * The App's OAuth client, saved by hand.
	 *
	 * This is the fallback the design names and blesses: the manifest flow hands every
	 * credential over the wire, but it is **unverified against GitHub from this repository**
	 * and everything downstream reads its values from `InstanceSettingsService` and does not
	 * care how they arrived. Two values rather than the App's six, because two are what a
	 * member's consent screen is built from — the app id and the private key sign an
	 * installation JWT, which nothing here does.
	 */
	@PostMapping("/app")
	fun saveApp(@RequestBody request: GithubAppRequest): GithubLinkResponse {
		requireInstanceAdmin()
		settings.saveGithubApp(request.clientId, request.clientSecret)
		return link()
	}

	/** Whether this member has linked, and whether there is an App to link through. */
	@GetMapping("/link")
	fun link(): GithubLinkResponse {
		val resolved = settings.resolved()
		return GithubLinkResponse.of(
			appConfigured = settings.githubApp() != null,
			appManagedByEnvironment = resolved.githubAppManagedByEnvironment,
			account = accounts.find(currentUser.requireId()),
			now = OffsetDateTime.now(),
		)
	}

	/**
	 * Starts the flow, and answers with a URL rather than a redirect.
	 *
	 * `NotionConnectController.authorize` reached the same conclusion and its reason is the
	 * whole of this one: a 302 from an endpoint called by `fetch` is followed by `fetch`,
	 * not by the window, and lands in the client as an opaque CORS failure. The browser has
	 * to navigate itself.
	 */
	@PostMapping("/link/authorize")
	fun authorize(session: HttpSession): GithubAuthorizeResponse {
		// Reading the member is not decoration here: it is what makes an unauthenticated
		// call to this route a 403 rather than a state stored against an anonymous session.
		currentUser.requireId()
		val redirectUri = callbackUri()
		val state = oauth.newState()
		session.setAttribute(STATE_ATTRIBUTE, state)
		return GithubAuthorizeResponse(oauth.authorizeUrl(redirectUri, state), redirectUri)
	}

	/**
	 * Where GitHub sends the browser back, and the only writer of `github_accounts`.
	 *
	 * Every outcome is a 302 to the settings screen carrying either a login or a sentence,
	 * because the visitor here is a browser mid-navigation and a JSON body would be
	 * rendered as text in the address bar's place.
	 */
	@GetMapping("/link/callback")
	fun callback(
		@RequestParam(required = false) code: String?,
		@RequestParam(required = false) state: String?,
		@RequestParam(required = false) error: String?,
		session: HttpSession,
	): ResponseEntity<Void> {
		val userId = currentUser.requireId()

		val expected = session.getAttribute(STATE_ATTRIBUTE) as? String
		// Spent either way: a state that has been compared once must never be comparable
		// again, whatever the comparison said.
		session.removeAttribute(STATE_ATTRIBUTE)

		// Declining on GitHub's screen is a normal answer, not a failure — the flow is
		// skippable by design — so it is reported as itself and writes nothing.
		if (error != null) return back(ERROR_PARAM, error)

		if (code.isNullOrBlank()) return back(ERROR_PARAM, "GitHub returned no authorization code.")
		if (expected == null || state == null || expected != state) {
			log.warn("GitHub link callback state did not match the one this session issued")
			return back(ERROR_PARAM, "This connection did not start in this browser session. Try again.")
		}

		return try {
			val token = oauth.exchange(code, callbackUri())
			val identity = oauth.identify(token.accessToken)

			// The `UNIQUE` on `github_user_id` is the real guard; this is what turns it into
			// a sentence instead of a constraint violation. Check-then-act has a race, and
			// the constraint underneath is why that is acceptable: two members consenting to
			// the same GitHub identity in the same instant get one link and one refusal
			// rather than a table that can name the wrong person on a feed line.
			val owner = accounts.ownerOf(identity.githubUserId)
			if (owner != null && owner != userId) {
				return back(
					ERROR_PARAM,
					"@${identity.login} is already linked to another member of this instance. " +
						"They have to unlink it before you can.",
				)
			}

			accounts.link(userId, identity.githubUserId, identity.login, token)
			back(LINKED_PARAM, identity.login)
		} catch (e: BadRequestException) {
			back(ERROR_PARAM, e.message ?: "The connection could not be completed.")
		}
	}

	/**
	 * Withdraws this member's consent.
	 *
	 * No path variable and no member in the signature, which is `GrantService`'s discipline
	 * and its argument: the isolation is a property of the signature, so there is nothing a
	 * caller could pass that would unlink somebody else. 204 whether or not there was a row
	 * — unlinking twice is not an error, and telling a caller which it was tells them
	 * nothing they can act on.
	 */
	@DeleteMapping("/link")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun unlink() {
		accounts.unlink(currentUser.requireId())
	}

	private fun requireInstanceAdmin() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can configure this instance")
		}
	}

	/**
	 * Derived from the request rather than from configuration, following
	 * `NotionConnectController`: an instance reached on two hostnames must send GitHub the
	 * one the browser is actually on, or the redirect comes back to the wrong place.
	 */
	private fun callbackUri(): String = ServletUriComponentsBuilder.fromCurrentContextPath()
		.path("/api/github/link/callback")
		.build()
		.toUriString()

	/**
	 * Back to the settings screen, on the section this flow belongs to.
	 *
	 * `.encode()` is not optional and the neighbouring controller paid for the lesson:
	 * `queryParam` takes the value as given, so every message with a space in it — which is
	 * every message worth reading — builds an illegal URI and returns a 400 instead of the
	 * redirect. `section` is carried too, because a member who lands on Appearance never
	 * reads the note that says whether their link worked.
	 */
	private fun back(key: String, value: String): ResponseEntity<Void> {
		val target = ServletUriComponentsBuilder.fromUriString(props.webOrigin)
			.path("/settings")
			.queryParam("section", "github")
			.queryParam(key, value)
			.build()
			.encode()
			.toUriString()
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
	}

	private companion object {
		const val STATE_ATTRIBUTE = "kanso.github.link.state"
		const val LINKED_PARAM = "github_linked"
		const val ERROR_PARAM = "github_error"
	}
}

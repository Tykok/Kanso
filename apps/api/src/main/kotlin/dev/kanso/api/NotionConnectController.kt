package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.service.BadRequestException
import dev.kanso.settings.InstanceSettingsService
import dev.kanso.setup.NotionOAuth
import dev.kanso.sync.notion.ReloadableNotionClient
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

data class NotionAppRequest(val clientId: String, val clientSecret: String? = null)

/** Where to send the browser, and the URI the integration has to have registered. */
data class NotionAuthorizeResponse(val url: String, val redirectUri: String)

/**
 * Connecting Notion with a button instead of a paste.
 *
 * Its own controller rather than four more methods on [SetupController]: everything
 * there answers a fetch with JSON, and these two are a browser round trip — one hands
 * out a URL to navigate to, the other is navigated *to* by Notion and answers with a
 * redirect. Mixing the two shapes in one class is how a callback ends up behind a CORS
 * rule written for an API.
 *
 * The callback is reached by the browser, at the API's own origin, carrying the session
 * cookie that was set there — which is what lets it be admin-only like everything else
 * in setup, and what makes the CSRF state check meaningful: the state is kept in that
 * session and compared on return.
 */
@RestController
@RequestMapping("/api/setup/notion")
class NotionConnectController(
	private val settings: InstanceSettingsService,
	private val oauth: NotionOAuth,
	private val notionClient: ReloadableNotionClient,
	private val currentUser: CurrentUser,
	private val props: KansoProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** The integration's own credentials — created once in Notion, pasted once here. */
	@PostMapping("/app")
	fun saveApp(@RequestBody request: NotionAppRequest): SetupStateResponse {
		requireInstanceAdmin()
		settings.saveNotionApp(request.clientId, request.clientSecret)
		return state()
	}

	/**
	 * Answers with the consent URL rather than redirecting to it.
	 *
	 * A redirect would be followed by `fetch`, not by the window, and land in the
	 * client as an opaque CORS failure — the browser has to navigate itself. So the
	 * caller does `window.location.assign(url)` and this stays a JSON endpoint like its
	 * neighbours.
	 */
	@PostMapping("/authorize")
	fun authorize(session: HttpSession): NotionAuthorizeResponse {
		requireInstanceAdmin()
		val redirectUri = callbackUri()
		val state = oauth.newState()
		session.setAttribute(STATE_ATTRIBUTE, state)
		return NotionAuthorizeResponse(oauth.authorizeUrl(redirectUri, state), redirectUri)
	}

	/**
	 * Where Notion sends the browser back.
	 *
	 * Answers with a redirect to the web app in every case, including every failure:
	 * whoever is looking at this is in the middle of a wizard, and a JSON error body
	 * rendered as a bare page would strand them at an API URL with no way back. The
	 * reason travels as a query parameter and the screen prints it.
	 */
	@GetMapping("/callback")
	fun callback(
		@RequestParam(required = false) code: String?,
		@RequestParam(required = false) state: String?,
		@RequestParam(required = false) error: String?,
		session: HttpSession,
	): ResponseEntity<Void> {
		requireInstanceAdmin()

		val expected = session.getAttribute(STATE_ATTRIBUTE) as? String
		// Spent either way: a state that has been compared once must never be comparable
		// again, whatever the comparison said.
		session.removeAttribute(STATE_ATTRIBUTE)

		// Declining on Notion's screen is a normal answer, not a failure — it arrives as
		// `error=access_denied` with no code — so it is reported as itself.
		if (error != null) return back("notion_error", error)

		if (code.isNullOrBlank()) return back("notion_error", "Notion returned no authorization code.")
		if (expected == null || state == null || expected != state) {
			log.warn("Notion callback state did not match the one this session issued")
			return back("notion_error", "This connection did not start in this browser session. Try again.")
		}

		return try {
			val grant = oauth.exchange(code, callbackUri())
			settings.saveNotionGrant(grant.accessToken, grant.workspaceId, grant.workspaceName, grant.botId)
			// The mirror is built from the token, so it has to be rebuilt now rather than
			// at the next restart — the same reload `saveNotion` already does.
			notionClient.reload()
			back("notion_connected", grant.workspaceName ?: "")
		} catch (e: BadRequestException) {
			back("notion_error", e.message ?: "The connection could not be completed.")
		}
	}

	/**
	 * The URI both halves must agree on, built from the request that arrived rather than
	 * from configuration — so what Notion was told and what Notion is answered are the
	 * same string by construction, and there is no knob to set wrong. Behind a reverse
	 * proxy this needs forwarded headers to be honoured, which is the one deployment note.
	 */
	private fun callbackUri(): String = ServletUriComponentsBuilder.fromCurrentContextPath()
		.path("/api/setup/notion/callback")
		.build()
		.toUriString()

	private fun back(key: String, value: String): ResponseEntity<Void> {
		val target = ServletUriComponentsBuilder.fromUriString(props.webOrigin)
			.path("/settings")
			.queryParam(key, value)
			.build()
			.toUriString()
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
	}

	private fun state(): SetupStateResponse = settings.state().let {
		SetupStateResponse(
			needsOwner = !settings.hasOwner(),
			setupCompletedAt = it.setupCompletedAt,
			notion = it.notion,
			google = it.google,
		)
	}

	private fun requireInstanceAdmin() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can configure this instance")
		}
	}

	private companion object {
		const val STATE_ATTRIBUTE = "kanso.notion.oauth.state"
	}
}

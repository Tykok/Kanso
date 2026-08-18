package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.auth.DynamicClientRegistrationRepository
import dev.kanso.auth.GoogleCredentialProbe
import dev.kanso.auth.GoogleProbeResult
import dev.kanso.auth.OidcRegistrations
import dev.kanso.config.KansoProperties
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.settings.GoogleSettingsState
import dev.kanso.settings.InstanceSettingsService
import dev.kanso.settings.NotionSettingsState
import dev.kanso.setup.NotionParentPages
import dev.kanso.setup.ParentPageOptions
import dev.kanso.sync.notion.HttpNotionClient
import dev.kanso.sync.notion.NoopNotionClient
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionRateLimited
import dev.kanso.sync.notion.RateLimiter
import dev.kanso.sync.notion.ReloadableNotionClient
import kotlinx.coroutines.runBlocking
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

data class SetupStateResponse(
	/** True until someone claims the instance; the wizard starts at step 0. */
	val needsOwner: Boolean,
	val setupCompletedAt: OffsetDateTime?,
	val notion: NotionSettingsState,
	val google: GoogleSettingsState,
)

data class NotionSetupRequest(
	/** Absent or blank keeps the stored token — the UI never receives it to send back. */
	val token: String? = null,
	val parentPageId: String = "",
)

data class NotionTestRequest(val token: String? = null, val parentPageId: String? = null)

data class NotionTestResponse(val ok: Boolean, val detail: String)

data class GoogleSetupRequest(val clientId: String, val clientSecret: String? = null)

/** Both absent means "check what is stored", the same way [NotionTestRequest] does. */
data class GoogleTestRequest(val clientId: String? = null, val clientSecret: String? = null)

/**
 * The first-run wizard, and the settings screen it becomes afterwards.
 *
 * Everything here is reconfigurable at runtime; that is the whole point. Saving a
 * Notion token rebuilds the client and saving Google credentials rebuilds the
 * OAuth registrations, so a self-hosted instance is set up from the app rather
 * than from a `.env` file and a restart.
 */
@RestController
@RequestMapping("/api/setup")
class SetupController(
	private val settings: InstanceSettingsService,
	private val notionClient: ReloadableNotionClient,
	private val registrations: DynamicClientRegistrationRepository,
	private val meta: NotionMetaRepository,
	private val currentUser: CurrentUser,
	private val googleProbe: GoogleCredentialProbe,
	private val props: KansoProperties,
	private val objectMapper: ObjectMapper,
	private val rateLimiter: RateLimiter,
	private val tx: TransactionTemplate,
) {

	/** Unauthenticated: the sign-in screen has to know whether to offer the wizard. */
	@GetMapping("/state")
	fun state(): SetupStateResponse = currentState()

	@PostMapping("/notion")
	fun saveNotion(@RequestBody request: NotionSetupRequest): SetupStateResponse {
		requireInstanceAdmin()
		settings.saveNotion(request.token, request.parentPageId)
		notionClient.reload()
		return currentState()
	}

	/**
	 * A real round trip against the submitted values, saving nothing.
	 *
	 * Without it a typo'd token is discovered three screens later, as a queue of
	 * failing sync jobs, which is the worst possible moment to learn about it.
	 */
	@PostMapping("/notion/test")
	fun testNotion(@RequestBody(required = false) request: NotionTestRequest?): NotionTestResponse {
		requireInstanceAdmin()
		val submitted = request ?: NotionTestRequest()
		val token = tokenFrom(submitted.token)
		if (token.isNullOrBlank()) {
			return NotionTestResponse(false, "No token to test: none was submitted and none is stored.")
		}
		val parentPageId = submitted.parentPageId?.trim()?.takeIf { it.isNotBlank() }
			?: settings.notionParentPageId()

		val probe = probeFor(token)
		return runBlocking {
			try {
				val botId = probe.botUserId()
				when {
					botId == null ->
						NotionTestResponse(false, "Notion accepted the token but returned no integration user.")

					parentPageId.isNullOrBlank() ->
						NotionTestResponse(true, "Token valid (integration $botId). No parent page to check.")

					probe.retrievePage(parentPageId) == null -> NotionTestResponse(
						false,
						"Token valid, but page $parentPageId was not found. Check the id, and share the " +
							"page with the integration from its ••• menu in Notion.",
					)

					else -> NotionTestResponse(true, "Token valid and the parent page is shared with the integration.")
				}
			} catch (e: NotionRateLimited) {
				NotionTestResponse(false, "Notion is rate limiting this integration; retry in ${e.retryAfter.toSeconds()}s.")
			} catch (e: NotionApiException) {
				NotionTestResponse(false, describe(e))
			}
		}
	}

	/**
	 * The pages Kanso may create its databases under, for the picker that replaced
	 * "32 hex characters from the page URL".
	 *
	 * Reads the submitted token before the stored one for the reason `/notion/test` does:
	 * the wizard has to be able to pick a page before it has saved anything. A GET has no
	 * body to carry that token in, and a query parameter would print the integration
	 * secret into the access log, the proxy log and the browser history — so it travels as
	 * a header, and its absence means "use the stored one".
	 *
	 * With no token at all the no-op client answers, rather than this method growing a
	 * second sentence saying what that client already says.
	 */
	@GetMapping("/notion/pages")
	fun notionPages(
		@RequestHeader(NOTION_TOKEN_HEADER, required = false) submitted: String?,
	): ParentPageOptions {
		requireInstanceAdmin()
		val token = tokenFrom(submitted)
		return NotionParentPages.list(if (token.isNullOrBlank()) NoopNotionClient() else probeFor(token))
	}

	@PostMapping("/google")
	fun saveGoogle(@RequestBody request: GoogleSetupRequest): SetupStateResponse {
		requireInstanceAdmin()
		settings.saveGoogle(request.clientId, request.clientSecret)
		reloadOAuthRegistrations()
		return currentState()
	}

	/**
	 * The same favour Notion's test does, for the credentials that are harder to get wrong
	 * *and* worse to get wrong.
	 *
	 * A mistyped Google secret is otherwise found at the first attempt to sign in — which,
	 * on a fresh instance being set up by its only admin, can be the moment they lock
	 * themselves out of the thing they were configuring. See [GoogleCredentialProbe] for
	 * how an id and secret are checked without a user, and why the pass arrives as an
	 * HTTP 400.
	 */
	@PostMapping("/google/test")
	fun testGoogle(@RequestBody(required = false) request: GoogleTestRequest?): GoogleProbeResult {
		requireInstanceAdmin()
		val submitted = request ?: GoogleTestRequest()
		val resolved = settings.resolved()
		// Submitted wins over stored, so the wizard can check before it saves.
		val clientId = submitted.clientId?.trim()?.takeIf { it.isNotBlank() } ?: resolved.googleClientId
		val clientSecret = submitted.clientSecret?.trim()?.takeIf { it.isNotBlank() }
			?: resolved.googleClientSecret

		// Refused with a sentence rather than sent to Google half-filled: "invalid_client"
		// would be the answer, and it would read as "your credentials are wrong".
		if (clientId.isNullOrBlank()) {
			return GoogleProbeResult(false, "No client id to test: none was submitted and none is stored.")
		}
		if (clientSecret.isNullOrBlank()) {
			return GoogleProbeResult(false, "No client secret to test: none was submitted and none is stored.")
		}

		return googleProbe.check(clientId, clientSecret, googleRedirectUri())
	}

	/** Skipping is finishing: the banner stops, nothing is decided permanently. */
	@PostMapping("/complete")
	fun complete(): SetupStateResponse {
		requireInstanceAdmin()
		settings.markSetupCompleted()
		return currentState()
	}

	// --- internals -----------------------------------------------------------

	private fun currentState(): SetupStateResponse {
		val state = settings.state()
		// Four databases means the mirror has somewhere to push. The read needs a
		// transaction of its own: nothing here is inside one, deliberately, so that
		// a save commits before the client is rebuilt from it.
		val bootstrapped = tx.execute { meta.findAll().size >= MIRRORED_DATABASES } ?: false
		return SetupStateResponse(
			needsOwner = !settings.hasOwner(),
			setupCompletedAt = state.setupCompletedAt,
			notion = state.notion.copy(bootstrapped = bootstrapped),
			google = state.google,
		)
	}

	/**
	 * Rebuilds the whole list rather than only Google: `reload` replaces the
	 * repository's contents, so a GitHub registration coming from the environment
	 * would otherwise disappear the moment someone saves Google credentials.
	 */
	private fun reloadOAuthRegistrations() {
		val resolved = settings.resolved()
		registrations.reload(
			OidcRegistrations.from(
				props.auth.copy(
					google = KansoProperties.Provider(
						clientId = resolved.googleClientId.orEmpty(),
						clientSecret = resolved.googleClientSecret.orEmpty(),
					),
				)
			)
		)
	}

	/**
	 * Where Spring Security registers Google's callback — a fixed path, so it is derived
	 * rather than configured, and built from the request that arrived rather than from a
	 * property nobody would remember to change. The same string `google-step.tsx` offers
	 * to copy into Google Cloud.
	 */
	private fun googleRedirectUri(): String = ServletUriComponentsBuilder.fromCurrentContextPath()
		.path("/login/oauth2/code/google")
		.build()
		.toUriString()

	/**
	 * Submitted wins over stored. That order is what lets the wizard test a token, and
	 * pick a page with it, before anything has been saved.
	 */
	private fun tokenFrom(submitted: String?): String? =
		submitted?.trim()?.takeIf { it.isNotBlank() } ?: settings.notionToken()

	/**
	 * Throwaway client, but the shared limiter: a test or a page search is a request to
	 * Notion like any other and counts against the same per-integration budget.
	 */
	private fun probeFor(token: String) =
		HttpNotionClient(props.notion.copy(token = token), objectMapper, rateLimiter)

	private fun requireInstanceAdmin() {
		val role = settings.instanceRoleOf(currentUser.requireId())
		if (role?.canConfigureInstance != true) {
			throw AccessDeniedException("Only the instance owner or an admin can change these settings")
		}
	}

	private fun describe(e: NotionApiException): String = when (e.status) {
		401 -> "Notion rejected the token. Copy the internal integration secret from notion.so/my-integrations."
		403 -> "The token is valid but not allowed to do this. Check the integration's capabilities."
		else -> e.message ?: "Notion refused the request (${e.status})."
	}

	private companion object {
		const val MIRRORED_DATABASES = 4

		/** Where a not-yet-saved token travels on a GET. See [notionPages]. */
		const val NOTION_TOKEN_HEADER = "X-Notion-Token"
	}
}

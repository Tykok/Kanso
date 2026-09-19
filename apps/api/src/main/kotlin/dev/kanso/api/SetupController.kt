package dev.kanso.api

import dev.kanso.auth.ConfiguredProviders
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.GoogleCredentialProbe
import dev.kanso.auth.GoogleProbeResult
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
	private val providers: ConfiguredProviders,
	private val meta: NotionMetaRepository,
	private val currentUser: CurrentUser,
	private val googleProbe: GoogleCredentialProbe,
	private val props: KansoProperties,
	private val objectMapper: ObjectMapper,
	private val rateLimiter: RateLimiter,
	private val tx: TransactionTemplate,
) {

	/**
	 * Unauthenticated: the sign-in screen has to know whether to offer the wizard.
	 *
	 * *That* is what is unauthenticated — `needsOwner` and the booleans around it — and the
	 * three strings this route used to hand out with them are not. `notion.parentPageId`
	 * names a page in a workspace this instance does not own, `notion.workspaceName` names
	 * the organisation, and `google.clientId` names its OAuth client; `curl` against any
	 * reachable Kanso returned all three. `SyncAdminController` split `status` from `detail`
	 * to keep ids like these from *signed-in members*, and the argument in its header — "an
	 * id names a page in a workspace this instance does not control" — applies with more
	 * force to a stranger. [currentState] redacts them for anyone who could not change them
	 * anyway.
	 *
	 * Redaction rather than a second route because of who reads what: every screen that
	 * wants the three strings is admin-facing already — `connections-section` and the
	 * `notion-connect` it draws are the only ones left, now that the wizard's own Notion
	 * step is gone — while `app-shell` and the onboarding checklist read only booleans and
	 * are reached by every member. One route keeps those callers as they are; a split would
	 * have moved six of them for the benefit of three fields.
	 *
	 * `needsOwner` itself stays open, and stays a real disclosure: it says an instance is
	 * deployed and unclaimed, which is the window `POST /api/setup/owner` is open in. That
	 * is not closeable from here — the wizard cannot offer itself to whoever is about to
	 * claim the instance without admitting there is something to claim. What limits it is
	 * the `users_single_owner` index, which makes the race a race that only one caller wins.
	 */
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
	 *
	 * Owner or admin, while `NotionImportController`'s four Notion reads answer any member —
	 * the two are not the same question. This one picks where Kanso will create its four
	 * databases, which is a setting, and it will read the workspace using a token off the
	 * request before anything has been decided at all. Previewing the base you are importing
	 * asks about work already inside the mirror's scope. The argument for the open four is
	 * written there, on each of them.
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
		// The same rebuild the next startup will do — see [ConfiguredProviders]. Here it is
		// what makes the button appear without a restart.
		providers.refresh()
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

	/**
	 * For an instance claimed before this branch shipped, not for anything in this app.
	 *
	 * Claiming the instance is finishing it now — [LocalAuthService.claimOwner] calls
	 * [InstanceSettingsService.markSetupCompleted] itself, at the moment that is true — so
	 * this route has no caller anywhere in the front end, and calling it on an instance
	 * claimed since then is a no-op: the column is already set. What it is still for is the
	 * instance claimed *before*, back when `setup_completed_at` was written by the wizard's
	 * last step rather than by claiming — an owner exists there with the column still null,
	 * and an operator who wants it backfilled calls this by hand.
	 */
	@PostMapping("/complete")
	fun complete(): SetupStateResponse {
		requireInstanceAdmin()
		settings.markSetupCompleted()
		return currentState()
	}

	// --- internals -----------------------------------------------------------

	/**
	 * [isInstanceAdmin] decides how much of this a caller is shown — see [state] for which
	 * fields and why. Every other route on this controller has already called
	 * [requireInstanceAdmin] before it gets here, so for them the answer is true and the
	 * check is one repository read they were going to pay for.
	 */
	private fun currentState(): SetupStateResponse {
		val state = settings.state()
		// Four databases means the mirror has somewhere to push. The read needs a
		// transaction of its own: nothing here is inside one, deliberately, so that
		// a save commits before the client is rebuilt from it.
		val bootstrapped = tx.execute { meta.findAll().size >= MIRRORED_DATABASES } ?: false
		val full = isInstanceAdmin()
		val notion = state.notion.copy(bootstrapped = bootstrapped)
		return SetupStateResponse(
			needsOwner = !settings.hasOwner(),
			setupCompletedAt = state.setupCompletedAt,
			notion = if (full) notion else notion.copy(parentPageId = null, workspaceName = null),
			google = if (full) state.google else state.google.copy(clientId = null),
		)
	}

	/**
	 * Where Spring Security registers Google's callback — a fixed path, so it is derived
	 * rather than configured, and built from the request that arrived rather than from a
	 * property nobody would remember to change. The same string `connections-section.tsx`
	 * offers to copy into Google Cloud.
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

	/**
	 * The same question as [requireInstanceAdmin], asked where there may be nobody to ask
	 * about: `idOrNull` rather than `requireId`, because [state] is reached anonymously and
	 * the honest answer for a caller with no session is "no" rather than a 401.
	 */
	private fun isInstanceAdmin(): Boolean {
		val id = currentUser.idOrNull() ?: return false
		return settings.instanceRoleOf(id)?.canConfigureInstance == true
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

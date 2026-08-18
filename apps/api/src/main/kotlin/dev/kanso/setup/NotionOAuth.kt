package dev.kanso.setup

import tools.jackson.databind.ObjectMapper
import dev.kanso.service.BadRequestException
import dev.kanso.settings.InstanceSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * What one completed consent screen granted.
 *
 * `workspaceName` and `botId` are nullable because Notion documents them as optional
 * and a grant is still usable without them: they are what the settings screen prints,
 * not what the mirror runs on.
 */
data class NotionGrant(
	val accessToken: String,
	val workspaceId: String?,
	val workspaceName: String?,
	val botId: String?,
)

/**
 * Connecting Notion by consent.
 *
 * The exchange is a plain HTTPS round trip rather than Spring's OAuth2 client, and
 * deliberately: `spring-security-oauth2-client` models a provider you *sign in with*,
 * where the result is an authenticated principal and a session. This is the other
 * kind — an instance-wide integration token that outlives every session and belongs
 * to no user — so routing it through the login machinery would mean a registration
 * that no one may log in through, and a principal nobody wants. Two dozen lines of
 * `HttpClient` says what is happening; `HttpNotionClient` already builds its calls the
 * same way.
 *
 * Notion's own quirks, both load-bearing:
 *
 *  - the token endpoint authenticates the *client* with HTTP Basic, not with body
 *    parameters, so the id and secret never appear in a form body;
 *  - `owner=user` in the authorize URL is what makes the consent screen offer the page
 *    picker. Without it Notion asks for nothing and grants nothing useful.
 */
@Service
class NotionOAuth(
	private val settings: InstanceSettingsService,
	private val objectMapper: ObjectMapper,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val http: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build()

	private val random = SecureRandom()

	/** An unguessable value the callback compares against the one it stored. */
	fun newState(): String = ByteArray(32).let {
		random.nextBytes(it)
		Base64.getUrlEncoder().withoutPadding().encodeToString(it)
	}

	/**
	 * Where to send the browser, or a refusal naming what is missing.
	 *
	 * The refusal is a 400 with a sentence rather than a redirect to Notion that would
	 * fail there: a consent screen that rejects an unknown client id explains it in
	 * Notion's words, on Notion's page, to somebody who was trying to configure Kanso.
	 */
	fun authorizeUrl(redirectUri: String, state: String): String {
		val (clientId, _) = settings.notionApp()
			?: throw BadRequestException(
				"No Notion integration is configured yet. Create a public integration in Notion, then save its " +
					"client id and secret here — after that this button is all it takes."
			)

		val query = listOf(
			"client_id" to clientId,
			"response_type" to "code",
			// The page picker. See the class comment.
			"owner" to "user",
			"redirect_uri" to redirectUri,
			"state" to state,
		).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

		return "$AUTHORIZE_URL?$query"
	}

	/**
	 * Trades the code for a token.
	 *
	 * Every failure here is reported with Notion's own message where there is one. The
	 * two that actually happen are a redirect URI that does not match the one registered
	 * on the integration, and a code replayed after it was already spent — and both are
	 * indistinguishable from "it did not work" unless the message survives.
	 */
	fun exchange(code: String, redirectUri: String): NotionGrant {
		val (clientId, clientSecret) = settings.notionApp()
			?: throw BadRequestException("No Notion integration is configured; nothing to exchange this code with.")

		val basic = Base64.getEncoder()
			.encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))

		val body = objectMapper.writeValueAsString(
			mapOf(
				"grant_type" to "authorization_code",
				"code" to code,
				"redirect_uri" to redirectUri,
			)
		)

		val request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
			.timeout(Duration.ofSeconds(20))
			.header("Authorization", "Basic $basic")
			.header("Content-Type", "application/json")
			.header("Notion-Version", NOTION_VERSION)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build()

		val response = try {
			http.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (e: Exception) {
			throw BadRequestException("Could not reach Notion to complete the connection: ${e.message}")
		}

		val parsed = runCatching { objectMapper.readTree(response.body()) }.getOrNull()

		if (response.statusCode() !in 200..299) {
			val detail = parsed?.path("error_description")?.asText(null)
				?: parsed?.path("error")?.asText(null)
				?: "HTTP ${response.statusCode()}"
			// The token is not in a failed body, so the body is safe to log. It is also the
			// only place the reason exists when the sentence below reaches a browser.
			log.warn("Notion rejected the authorization code: {}", detail)
			throw BadRequestException("Notion refused the connection: $detail")
		}

		val token = parsed?.path("access_token")?.asText(null)
			?: throw BadRequestException("Notion accepted the code but returned no access token.")

		return NotionGrant(
			accessToken = token,
			workspaceId = parsed.path("workspace_id").asText(null),
			workspaceName = parsed.path("workspace_name").asText(null),
			botId = parsed.path("bot_id").asText(null),
		)
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private companion object {
		const val AUTHORIZE_URL = "https://api.notion.com/v1/oauth/authorize"
		const val TOKEN_URL = "https://api.notion.com/v1/oauth/token"

		/**
		 * The exchange is versioned like every other Notion call. Pinned here rather than
		 * read from `KansoProperties`: this endpoint predates the data-source split and is
		 * unaffected by it, so tying it to the version the mirror negotiates would couple
		 * connecting to a schema decision it has nothing to do with.
		 */
		const val NOTION_VERSION = "2022-06-28"
	}
}

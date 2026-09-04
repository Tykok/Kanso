package dev.kanso.github

import dev.kanso.service.BadRequestException
import dev.kanso.settings.InstanceSettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

/** Who GitHub says the browser that just consented belongs to. */
data class GithubIdentity(val githubUserId: Long, val login: String)

/**
 * The member's own consent — GitHub's user-to-server flow, which is the second grant and
 * not the first.
 *
 * Installing the App grants Kanso access to *repositories*. It does not grant Kanso the
 * right to know that `@tykok` on a payload is a person with a Kanso account, and it
 * certainly does not grant the right to act as them. That is this flow, it is per member,
 * and it is skippable: a member who never walks it is not degraded, they are the
 * documented case — see [GithubAccountRepository.memberFor].
 *
 * A plain `java.net.http.HttpClient` rather than Spring's OAuth2 client, the argument
 * `setup/NotionOAuth.kt` makes at length and which holds here for a second reason.
 * `spring-security-oauth2-client` models a provider you *sign in with*, whose result is an
 * authenticated principal — and GitHub already is one of those in this codebase
 * (`kanso.auth.github`, `OidcRegistrations`). Reusing that machinery would tangle the two,
 * so that a member who signed in with Google could not link a GitHub account and a rotated
 * sign-in secret would break the link. Two dozen lines of `HttpClient` keeps them apart.
 */
@Service
class GithubUserOAuth(
	private val settings: InstanceSettingsService,
	private val objectMapper: ObjectMapper,
) {

	private val log = LoggerFactory.getLogger(GithubUserOAuth::class.java)

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
	 * Where to send the browser.
	 *
	 * **No `scope` parameter, and its absence is a decision.** A GitHub *App*'s
	 * user-to-server token carries the permissions the App was installed with, intersected
	 * with what the member can do — there is no scope list to ask for, and sending one
	 * would be asking for OAuth-App scopes from an endpoint that does not grant them. The
	 * consent screen GitHub draws is therefore truthful without Kanso composing it, which
	 * is the opposite of the `kanso:github` problem the spec worries about for its own
	 * tokens.
	 */
	fun authorizeUrl(redirectUri: String, state: String): String {
		val (clientId, _) = settings.githubApp()
			?: throw BadRequestException(
				"No GitHub App is configured on this instance yet, so there is nothing to ask consent through. " +
					"An owner sets one up in Settings › Connections."
			)

		val query = listOf(
			"client_id" to clientId,
			"redirect_uri" to redirectUri,
			"state" to state,
		).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

		return "$AUTHORIZE_URL?$query"
	}

	/**
	 * The code for a token.
	 *
	 * **Both of GitHub's token shapes come out of here intact**, which is the whole reason
	 * this method reads four fields rather than one:
	 *
	 *  * An App that did not opt into expiring tokens answers with `access_token` alone. No
	 *    `expires_in`, no `refresh_token` — so both are null, the row stores nulls, and
	 *    [GithubTokenState.PERMANENT] is what a screen reads back.
	 *  * An App that did opt in answers with all three, and `expires_in` is *seconds* —
	 *    turned into an instant here rather than stored as a duration, because a duration in
	 *    a column is only meaningful beside the moment it was measured from, and that moment
	 *    is `now()` at exactly this line and nowhere else.
	 *
	 * The mixture GitHub does not document — an expiry with no refresh token — is passed
	 * through as it arrived rather than repaired. `V36` made the two columns independent on
	 * the argument that what GitHub sends is not ours to constrain, and inventing a refresh
	 * token or discarding an expiry would both be a lie the next reader inherits.
	 *
	 * `Accept: application/json` is not optional: without it this endpoint answers
	 * `application/x-www-form-urlencoded`, which parses as JSON into nothing at all and
	 * presents as "GitHub accepted the code but returned no access token".
	 */
	fun exchange(code: String, redirectUri: String): GithubToken {
		val (clientId, clientSecret) = settings.githubApp()
			?: throw BadRequestException("No GitHub App is configured; nothing to exchange this code with.")

		val form = listOf(
			"client_id" to clientId,
			"client_secret" to clientSecret,
			"code" to code,
			"redirect_uri" to redirectUri,
		).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

		val request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
			.timeout(Duration.ofSeconds(20))
			.header("Accept", "application/json")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build()

		val parsed = send(request, "complete the connection")

		// GitHub answers 200 with an `error` body for a spent or wrong code, so the status
		// code is not the test — this is the one provider behaviour that makes a
		// `statusCode() !in 200..299` check on its own insufficient.
		val error = parsed.path("error").asText(null)
		if (error != null) {
			val detail = parsed.path("error_description").asText(null) ?: error
			// A refused exchange carries no token, so the body is safe to log.
			log.warn("GitHub refused the authorization code: {}", detail)
			throw BadRequestException("GitHub refused the connection: $detail")
		}

		val token = parsed.path("access_token").asText(null)
			?: throw BadRequestException("GitHub accepted the code but returned no access token.")

		val expiresIn = parsed.path("expires_in").asLong(0L)

		return GithubToken(
			accessToken = token,
			refreshToken = parsed.path("refresh_token").asText(null),
			expiresAt = expiresIn.takeIf { it > 0 }?.let { OffsetDateTime.now().plusSeconds(it) },
		)
	}

	/**
	 * Who that token belongs to, asked of GitHub rather than inferred.
	 *
	 * The `id` is what `github_accounts.github_user_id` stores and what a payload resolves
	 * through, because a login is renameable and an inbound event that arrives after a
	 * rename must still reach the right member. The login is stored beside it for the
	 * payloads that carry no id and for the screen, which shows a person `@tykok` and not
	 * `583917`.
	 *
	 * A separate call and not a claim on the token, because a user-to-server token is
	 * opaque: there is no id token here to read, which is precisely how this flow differs
	 * from the OIDC sign-in that already exists for GitHub in this codebase.
	 */
	fun identify(accessToken: String): GithubIdentity {
		val request = HttpRequest.newBuilder(URI.create(USER_URL))
			.timeout(Duration.ofSeconds(20))
			.header("Accept", "application/vnd.github+json")
			.header("Authorization", "Bearer $accessToken")
			.header("X-GitHub-Api-Version", API_VERSION)
			.GET()
			.build()

		val parsed = send(request, "read the account this consent belongs to")

		val id = parsed.path("id").asLong(0L).takeIf { it != 0L }
			?: throw BadRequestException("GitHub returned an account with no id, so there is nothing to link.")
		val login = parsed.path("login").asText(null)
			?: throw BadRequestException("GitHub returned an account with no login, so there is nothing to show.")

		return GithubIdentity(githubUserId = id, login = login)
	}

	/**
	 * One round trip, one place that turns a network failure into a sentence.
	 *
	 * The response body is parsed leniently — a `null` tree rather than an exception — so a
	 * gateway's HTML error page becomes "GitHub refused" rather than a Jackson stack trace
	 * in the middle of somebody's consent flow.
	 */
	private fun send(request: HttpRequest, what: String): JsonNode {
		val response = try {
			http.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (e: Exception) {
			throw BadRequestException("Could not reach GitHub to $what: ${e.message}")
		}
		val parsed = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
		if (response.statusCode() !in 200..299) {
			val detail = parsed?.path("message")?.asText(null) ?: "HTTP ${response.statusCode()}"
			log.warn("GitHub answered {} when asked to {}: {}", response.statusCode(), what, detail)
			throw BadRequestException("GitHub refused: $detail")
		}
		return parsed ?: throw BadRequestException("GitHub answered with something that is not JSON.")
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private companion object {
		const val AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
		const val TOKEN_URL = "https://github.com/login/oauth/access_token"
		const val USER_URL = "https://api.github.com/user"

		/**
		 * Pinned, for the reason `NotionOAuth` pins `Notion-Version`: a version read from
		 * configuration is a version nobody has tested this code against, and the failure
		 * arrives as a changed field shape rather than as a refusal.
		 */
		const val API_VERSION = "2022-11-28"
	}
}

package dev.kanso.auth

import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Google's answer as it arrived: a status and a body nobody has interpreted yet. */
data class GoogleTokenReply(val status: Int, val body: String)

/** Whether the credentials work, and a sentence a person can act on. */
data class GoogleProbeResult(val ok: Boolean, val detail: String)

/**
 * The one HTTP call, behind an interface.
 *
 * Same seam `NotionClient` has, for the same reason: Google is not reachable from a
 * test and must not be, or the suite passes and fails on somebody else's uptime.
 * Implementations throw on a transport failure; [GoogleCredentialProbe] turns that
 * into a sentence.
 */
fun interface GoogleTokenEndpoint {
	fun post(tokenUri: String, form: String): GoogleTokenReply
}

@Component
class HttpGoogleTokenEndpoint : GoogleTokenEndpoint {

	private val http: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build()

	override fun post(tokenUri: String, form: String): GoogleTokenReply {
		val request = HttpRequest.newBuilder(URI.create(tokenUri))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build()
		// Nothing about this request is logged, ever: the form body carries the client
		// secret, and a debug line written once outlives the debugging session.
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		return GoogleTokenReply(response.statusCode(), response.body())
	}
}

/**
 * Checks a Google client id and secret without a user, a browser or a consent screen.
 *
 * Google's token endpoint authenticates the *client* before it looks at the grant, so a
 * deliberately invalid code separates the two failures cleanly:
 *
 *  - **401 `invalid_client`** — the id and the secret do not go together. This is the
 *    finding the endpoint exists for, and it is the one an admin otherwise discovers at
 *    the first attempt to sign in, possibly to the instance they were configuring.
 *  - **400 `invalid_grant`** — Google authenticated the client and *then* rejected the
 *    code, which is exactly what a bogus code should produce. **This is the pass.**
 *  - anything else — reported with Google's own status and `error_description`, verbatim.
 *    A test that invents a diagnosis is worse than one that says "Google answered 403: …".
 *
 * The success case looking like an HTTP error is the part a future reader deletes, which
 * is why it is written down here rather than left to be re-derived.
 */
@Service
class GoogleCredentialProbe(
	private val endpoint: GoogleTokenEndpoint,
	private val objectMapper: ObjectMapper,
) {

	fun check(clientId: String, clientSecret: String, redirectUri: String): GoogleProbeResult {
		val form = listOf(
			"grant_type" to "authorization_code",
			// Not a code Google could ever have issued, and named so that anyone reading
			// Google Cloud's own logs can see what asked.
			"code" to PROBE_CODE,
			"client_id" to clientId,
			"client_secret" to clientSecret,
			"redirect_uri" to redirectUri,
		).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

		val reply = try {
			endpoint.post(OidcRegistrations.googleTokenUri, form)
		} catch (e: Exception) {
			return GoogleProbeResult(
				false,
				"Could not reach Google to check the credentials: ${e.message ?: e.javaClass.simpleName}",
			)
		}

		val parsed = runCatching { objectMapper.readTree(reply.body) }.getOrNull()
		val error = parsed?.path("error")?.asText(null)
		val description = parsed?.path("error_description")?.asText(null)

		val result = when {
			reply.status == 401 && error == INVALID_CLIENT -> GoogleProbeResult(
				false,
				"Google rejected this client id and secret as a pair. Re-copy the secret from the same " +
					"OAuth client in Google Cloud → APIs & Services → Credentials: a secret belongs to " +
					"one client id and cannot be used with another.",
			)

			reply.status == 400 && error == INVALID_GRANT -> GoogleProbeResult(
				true,
				"Google accepted the client id and secret, then refused the throwaway code this check " +
					"sends — which is what a working pair looks like from here. Sign-in itself still " +
					"needs the redirect URI above registered on the client.",
			)

			else -> GoogleProbeResult(
				false,
				"Google answered ${reply.status}: ${description ?: error ?: reply.body.take(BODY_EXCERPT)}",
			)
		}

		// Some OAuth error bodies quote the offending request back at you. Redacted at the
		// single exit rather than per branch, so a sentence added later cannot skip it.
		return result.copy(detail = result.detail.replace(clientSecret, "[redacted]"))
	}

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private companion object {
		const val PROBE_CODE = "kanso-credential-check-not-a-real-code"
		const val INVALID_CLIENT = "invalid_client"
		const val INVALID_GRANT = "invalid_grant"
		const val BODY_EXCERPT = 200
	}
}

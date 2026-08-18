package dev.kanso.auth

import dev.kanso.config.KansoProperties
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The probe, with Google standing still.
 *
 * Google is not reachable from a test and must not be: a test that POSTs to
 * `oauth2.googleapis.com` passes or fails on somebody else's uptime. So the one HTTP
 * call is an interface here, answered by hand — the same seam `NotionClient` has, for
 * the same reason.
 */
class GoogleCredentialProbeTest {

	/** Records what was sent and answers what the test wants back. */
	private class FakeEndpoint(
		private val reply: GoogleTokenReply? = null,
		private val failure: Exception? = null,
	) : GoogleTokenEndpoint {
		var uri: String? = null
		var form: String? = null

		override fun post(tokenUri: String, form: String): GoogleTokenReply {
			this.uri = tokenUri
			this.form = form
			failure?.let { throw it }
			return reply!!
		}
	}

	private val id = "1234-probe.apps.googleusercontent.com"

	/** Never printed, never asserted by name — only that it went nowhere it should not. */
	private val secret = "GOCSPX-stand-in-value"

	private fun probe(
		reply: GoogleTokenReply? = null,
		failure: Exception? = null,
	): Pair<GoogleCredentialProbe, FakeEndpoint> {
		val endpoint = FakeEndpoint(reply, failure)
		return GoogleCredentialProbe(endpoint, ObjectMapper()) to endpoint
	}

	private fun check(probe: GoogleCredentialProbe) =
		probe.check(id, secret, "https://kanso.example/login/oauth2/code/google")

	@Test
	fun `an id and secret that do not go together are named as the problem`() {
		val (probe, _) = probe(
			GoogleTokenReply(401, """{"error":"invalid_client","error_description":"Unauthorized"}""")
		)

		val result = check(probe)

		assertFalse(result.ok)
		// The one finding worth a whole endpoint: the pair is wrong, and it is discovered
		// here rather than at the first attempt to sign in.
		assertContains(result.detail.lowercase(), "secret")
	}

	@Test
	fun `a code Google refuses is the pass`() {
		// Google authenticates the client before it looks at the grant, so a rejected
		// bogus code means the id and secret got past it. The success case looking like
		// an HTTP error is the whole trick.
		val (probe, _) = probe(
			GoogleTokenReply(400, """{"error":"invalid_grant","error_description":"Malformed auth code."}""")
		)

		val result = check(probe)

		assertTrue(result.ok, result.detail)
	}

	@Test
	fun `a third answer is reported in Google's own words rather than diagnosed`() {
		val (probe, _) = probe(
			GoogleTokenReply(403, """{"error":"access_denied","error_description":"Token endpoint disabled"}""")
		)

		val result = check(probe)

		assertFalse(result.ok)
		assertContains(result.detail, "403")
		// Verbatim: a test that invents a diagnosis is worse than one that quotes.
		assertContains(result.detail, "Token endpoint disabled")
	}

	@Test
	fun `an answer with no JSON in it still reports its status`() {
		val (probe, _) = probe(GoogleTokenReply(502, "<html>Bad Gateway</html>"))

		val result = check(probe)

		assertFalse(result.ok)
		assertContains(result.detail, "502")
	}

	@Test
	fun `a Google that cannot be reached is reported as that, not as a bad credential`() {
		val (probe, _) = probe(failure = java.io.IOException("connect timed out"))

		val result = check(probe)

		assertFalse(result.ok)
		assertContains(result.detail.lowercase(), "reach")
		assertContains(result.detail, "connect timed out")
	}

	@Test
	fun `the probe posts an authorization code grant to the endpoint sign-in itself uses`() {
		val (probe, endpoint) = probe(GoogleTokenReply(400, """{"error":"invalid_grant"}"""))

		check(probe)

		// The URI the login flow will actually use, read from `CommonOAuth2Provider.GOOGLE`
		// rather than written out twice. If these two ever differ, a green test says
		// nothing about the flow it stands in for.
		val login = OidcRegistrations.from(
			KansoProperties.Auth(google = KansoProperties.Provider(id, secret))
		).single { it.registrationId == "google" }
		assertEquals(login.providerDetails.tokenUri, endpoint.uri)

		val form = endpoint.form!!
		assertContains(form, "grant_type=authorization_code")
		assertContains(form, "code=")
		// The client has to be authenticated for the answer to mean anything.
		assertContains(form, "client_id=")
		assertContains(form, "client_secret=")
	}

	@Test
	fun `nothing Google is told comes back out in what a person is shown`() {
		val (probe, _) = probe(
			// Worst case: Google echoes the request back in its own error text.
			GoogleTokenReply(400, """{"error":"invalid_request","error_description":"client_secret=$secret"}""")
		)

		val result = check(probe)

		assertFalse(result.detail.contains(secret), "the credential must never reach a screen or a log")
	}
}

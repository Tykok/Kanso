package dev.kanso.oauth

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 9207 — `iss` on every authorisation response, errors included.
 *
 * A MUST in the MCP specification, and the library does not implement it: there is no
 * builder method on `AuthorizationServerSettings`, no name in `ConfigurationSettingNames`,
 * no `iss` on the 302 and no `authorization_response_iss_parameter_supported` in the
 * metadata document. All four were checked against the jar. So it is ours.
 *
 * What it buys: a client that receives a code can tell *which* server sent it, which is
 * how a mix-up attack is detected. Without it a code from a malicious server is
 * indistinguishable from a code from this one.
 */
class IssuerParameterTest {

	private val issuer = "https://kanso.example.com"

	private fun query(location: String): Map<String, String> =
		UriComponentsBuilder.fromUriString(location).build().queryParams
			.mapValues { (_, values) -> values.first().orEmpty() }

	private fun granted(state: String?) = OAuth2AuthorizationCodeRequestAuthenticationToken(
		"$issuer/oauth2/authorize",
		"client-1",
		UsernamePasswordAuthenticationToken("a-user-id", null, emptyList()),
		OAuth2AuthorizationCode("the-code", Instant.now(), Instant.now().plus(1, ChronoUnit.MINUTES)),
		"http://127.0.0.1:9000/callback",
		state,
		setOf(OAuthScopes.READ),
	)

	@Test
	fun `a granted authorisation redirects with code, state and iss`() {
		val response = MockHttpServletResponse()
		IssuerAppendingSuccessHandler(issuer = { issuer })
			.onAuthenticationSuccess(MockHttpServletRequest(), response, granted("state-1"))

		val parameters = query(response.redirectedUrl!!)
		assertEquals("the-code", parameters["code"])
		assertEquals("state-1", parameters["state"])
		assertEquals(issuer, parameters["iss"], "without iss a client cannot detect a mix-up")
	}

	@Test
	fun `no state is sent when the client sent none`() {
		val response = MockHttpServletResponse()
		IssuerAppendingSuccessHandler(issuer = { issuer })
			.onAuthenticationSuccess(MockHttpServletRequest(), response, granted(null))

		val parameters = query(response.redirectedUrl!!)
		assertNull(parameters["state"], "echoing an empty state is not the same as echoing none")
		assertEquals(issuer, parameters["iss"])
	}

	@Test
	fun `a refused authorisation carries iss too, because the MUST includes errors`() {
		val response = MockHttpServletResponse()
		val failure = OAuth2AuthorizationCodeRequestAuthenticationException(
			OAuth2Error("access_denied", "The member said no.", null),
			granted("state-1"),
		)
		IssuerAppendingFailureHandler(issuer = { issuer })
			.onAuthenticationFailure(MockHttpServletRequest(), response, failure)

		val parameters = query(response.redirectedUrl!!)
		assertEquals("access_denied", parameters["error"])
		assertEquals("state-1", parameters["state"])
		assertEquals(issuer, parameters["iss"])
	}

	@Test
	fun `an error with no usable redirect uri is answered, not redirected`() {
		// The library refuses a bad redirect_uri before this handler runs, and sending an
		// error to an address we just rejected would be the open redirect the whole rule
		// exists to prevent.
		val response = MockHttpServletResponse()
		val token = OAuth2AuthorizationCodeRequestAuthenticationToken(
			"$issuer/oauth2/authorize",
			"client-1",
			UsernamePasswordAuthenticationToken("a-user-id", null, emptyList()),
			null,
			null,
			setOf(OAuthScopes.READ),
			emptyMap(),
		)
		IssuerAppendingFailureHandler(issuer = { issuer }).onAuthenticationFailure(
			MockHttpServletRequest(),
			response,
			OAuth2AuthorizationCodeRequestAuthenticationException(
				OAuth2Error("invalid_request", "No redirect_uri.", null),
				token,
			),
		)

		assertNull(response.redirectedUrl, "nothing is redirected anywhere")
		assertEquals(400, response.status)
		assertTrue(response.contentAsString.contains("invalid_request"))
	}

	@Test
	fun `the metadata advertises the parameter, or a client never checks it`() {
		val metadata = org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata
			.builder()
			.issuer(issuer)
			.authorizationEndpoint("$issuer/oauth2/authorize")
			.tokenEndpoint("$issuer/oauth2/token")
			.responseType("code")
			.apply { ISS_PARAMETER_ADVERTISED.accept(this) }
			.build()

		assertEquals(true, metadata.claims["authorization_response_iss_parameter_supported"])
	}
}

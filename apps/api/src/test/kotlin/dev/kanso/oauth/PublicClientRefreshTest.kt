package dev.kanso.oauth

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three pieces that keep a connector alive past its first hour.
 *
 * No Spring context: each is a function of its argument, and
 * [dev.kanso.oauth.OidcChainWiringTest] owns the question of whether they are installed.
 * What is asserted here is the part a reviewer will want to be sure of — that the way in
 * this file opens is *narrow*, and closes again for every request that carries a
 * credential or is not a refresh.
 */
class PublicClientRefreshTest {

	private val publicClient = RegisteredClient.withId(UUID.randomUUID().toString())
		.clientId("claude-code")
		.clientName("Claude Code")
		.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
		.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
		.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
		.redirectUri("http://127.0.0.1:8765/callback")
		.scope(OAuthScopes.READ)
		.tokenSettings(TokenSettings.builder().refreshTokenTimeToLive(Duration.ofDays(60)).build())
		.build()

	/** Registered with a secret, so it has a credential and must be made to present it. */
	private val confidentialClient = RegisteredClient.withId(UUID.randomUUID().toString())
		.clientId("something-with-a-secret")
		.clientName("Confidential")
		.clientSecret("{noop}shhh")
		.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
		.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
		.redirectUri("https://example.test/cb")
		.scope(OAuthScopes.READ)
		.build()

	private val clients = InMemoryRegisteredClientRepository(publicClient, confidentialClient)
	private val converter = PublicClientRefreshConverter()
	private val provider = PublicClientRefreshProvider(clients)

	private fun refreshRequest(
		clientId: String? = "claude-code",
		extra: Map<String, String> = emptyMap(),
	) = MockHttpServletRequest("POST", "/oauth2/token").apply {
		addParameter("grant_type", "refresh_token")
		addParameter("refresh_token", "a-refresh-token")
		if (clientId != null) addParameter("client_id", clientId)
		extra.forEach { (name, value) -> addParameter(name, value) }
	}

	// ---- the generator -----------------------------------------------------------------

	/**
	 * The library's own returns null here, which is the whole first half of the finding: the
	 * code exchange answered with an access token and no `refresh_token` at all, and
	 * `oauth2_authorization.refresh_token_value` was null on a running instance.
	 */
	@Test
	fun `a refresh token is generated for a public client on the code grant`() {
		val token = PublicClientRefreshTokenGenerator().generate(
			DefaultOAuth2TokenContext.builder()
				.registeredClient(publicClient)
				.tokenType(OAuth2TokenType.REFRESH_TOKEN)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.build(),
		)

		val issued = assertNotNull(token, "with nothing to rotate, the rotation this branch configured is decoration")
		assertTrue(issued.tokenValue.length >= 64, "the library's own key length, not a shorter one")
		assertEquals(
			publicClient.tokenSettings.refreshTokenTimeToLive,
			Duration.between(issued.issuedAt, issued.expiresAt),
			"the lifetime is the client's own, so `ClientRegistrationService` still decides it",
		)
	}

	@Test
	fun `it answers for refresh tokens and nothing else, so the delegate list still works`() {
		assertNull(
			PublicClientRefreshTokenGenerator().generate(
				DefaultOAuth2TokenContext.builder()
					.registeredClient(publicClient)
					.tokenType(OAuth2TokenType.ACCESS_TOKEN)
					.build(),
			),
			"returning something here would shadow the access-token generator beside it",
		)
	}

	// ---- the converter -----------------------------------------------------------------

	@Test
	fun `a public client on a refresh is recognised`() {
		val converted = converter.convert(refreshRequest())

		val token = assertNotNull(converted, "unrecognised, the request arrives anonymous and is refused with a bare 401")
		token as OAuth2ClientAuthenticationToken
		assertEquals("claude-code", token.principal, "the client it names is the one it presented")
		assertEquals(
			ClientAuthenticationMethod.NONE,
			token.clientAuthenticationMethod,
			"no credential was presented, and saying otherwise would be a lie the library reads",
		)
	}

	/**
	 * Every one of these is a request one of the library's own four converters should
	 * answer. A credential arriving here and being ignored would be a confidential client
	 * authenticated on the strength of its `client_id`.
	 */
	@Test
	fun `anything carrying a credential is left to the library`() {
		assertNull(
			converter.convert(refreshRequest().apply { addHeader("Authorization", "Basic Y2xpZW50OnNlY3JldA==") }),
			"Basic is `ClientSecretBasicAuthenticationConverter`'s request, not this one's",
		)
		assertNull(
			converter.convert(refreshRequest(extra = mapOf("client_secret" to "shhh"))),
			"a secret in the form is `ClientSecretPostAuthenticationConverter`'s",
		)
		assertNull(
			converter.convert(refreshRequest(extra = mapOf("client_assertion" to "ey.j.wt"))),
			"and an assertion is the JWT converter's",
		)
	}

	/**
	 * The code exchange must keep reaching `PublicClientAuthenticationProvider`, which is
	 * where the PKCE verifier is checked. A converter that widened to cover it would be a
	 * way to redeem an intercepted code with no verifier at all.
	 */
	@Test
	fun `the code exchange is not this converter's business`() {
		val code = MockHttpServletRequest("POST", "/oauth2/token").apply {
			addParameter("grant_type", "authorization_code")
			addParameter("code", "a-code")
			addParameter("code_verifier", "a-verifier")
			addParameter("client_id", "claude-code")
		}

		assertNull(converter.convert(code), "PKCE is checked by the converter this one must not replace")
	}

	@Test
	fun `a request with no client_id, or with two, is not a client to guess at`() {
		assertNull(converter.convert(refreshRequest(clientId = null)), "nothing named, nothing to authenticate")
		assertFailsWith<OAuth2AuthenticationException>(
			"whichever of two client_ids you pick, the other one was ignored",
		) {
			converter.convert(refreshRequest().apply { addParameter("client_id", "someone-else") })
		}
	}

	@Test
	fun `a GET is not a token request`() {
		val get = MockHttpServletRequest("GET", "/oauth2/token").apply {
			addParameter("grant_type", "refresh_token")
			addParameter("client_id", "claude-code")
		}

		assertNull(converter.convert(get), "the token endpoint is a POST, and a credential-free GET is a link")
	}

	// ---- the provider ------------------------------------------------------------------

	@Test
	fun `the client it names is looked up and handed back authenticated`() {
		val result = provider.authenticate(converter.convert(refreshRequest())!!)

		val token = assertNotNull(result, "returning null here leaves the request anonymous, which is the bug")
		token as OAuth2ClientAuthenticationToken
		assertEquals(publicClient, token.registeredClient, "the library needs the row, not the string")
		assertTrue(token.isAuthenticated, "an unauthenticated result is `invalid_client` one frame later")
	}

	/**
	 * The one check in the provider that is not bookkeeping. Without it this is a way for a
	 * client that registered a secret to authenticate without presenting it, on a request
	 * shape it never has to use.
	 */
	@Test
	fun `a client that registered a credential cannot skip it here`() {
		val request = converter.convert(refreshRequest(clientId = "something-with-a-secret"))!!

		assertFailsWith<OAuth2AuthenticationException>("a secret nobody has to present is not a secret") {
			provider.authenticate(request)
		}
	}

	@Test
	fun `a client id nothing registered is invalid_client, not a null that falls through`() {
		val request = converter.convert(refreshRequest(clientId = "never-registered"))!!

		assertFailsWith<OAuth2AuthenticationException> { provider.authenticate(request) }
	}

	/**
	 * `ProviderManager` asks this one first, so it has to hand the library's own requests
	 * back untouched — a null is what makes it move on to the next provider.
	 */
	@Test
	fun `a token from one of the library's converters is passed over`() {
		val fromTheLibrary = OAuth2ClientAuthenticationToken(
			"claude-code",
			ClientAuthenticationMethod.NONE,
			null,
			mapOf("grant_type" to AuthorizationGrantType.AUTHORIZATION_CODE.value, "code_verifier" to "v"),
		)

		assertNull(
			provider.authenticate(fromTheLibrary),
			"the PKCE exchange belongs to `PublicClientAuthenticationProvider`, which runs after this one",
		)
	}
}

package dev.kanso.oauth

import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 7591 registration, open — and the shape that makes open acceptable.
 *
 * The library's own endpoint demands a single-use initial access token bearing scope
 * `client.create`, which no MCP client presents and nobody can hand it, so `claude mcp
 * add` cannot use it. This is ours instead, and the trade is explicit: anybody may
 * create a client, so a client must be worth almost nothing on its own. It is a name
 * that may *ask*. Until a member says yes on the consent screen it can read nothing.
 *
 * What the request does **not** get to choose is most of the record, and that is the
 * point of nearly every assertion here.
 */
class ClientRegistrationServiceTest {

	private val saved = mutableListOf<RegisteredClient>()

	private val repository = object : RegisteredClientRepository {
		override fun save(registeredClient: RegisteredClient) {
			saved += registeredClient
		}

		override fun findById(id: String): RegisteredClient? = saved.firstOrNull { it.id == id }

		override fun findByClientId(clientId: String): RegisteredClient? =
			saved.firstOrNull { it.clientId == clientId }
	}

	private fun service(count: Int = 0, cap: Int = 200) = ClientRegistrationService(
		clients = repository,
		policy = RedirectUriPolicy(setOf("claude.ai")),
		clientCount = { count },
		maxClients = cap,
	)

	private fun request(
		redirectUris: List<String>? = listOf("http://127.0.0.1:9999/callback"),
		clientName: String? = "Claude Code",
		scope: String? = null,
	) = ClientRegistrationRequest(redirectUris, clientName, scope)

	@Test
	fun `a registration comes back with a client id and no secret`() {
		val response = service().register(request())

		assertTrue(response.clientId.isNotBlank())
		assertEquals(1, saved.size)
		assertNull(
			saved.single().clientSecret,
			"a leaked registration response must be worth nothing on its own",
		)
	}

	@Test
	fun `every registration is a public client that must use PKCE and ask for consent`() {
		service().register(request())
		val client = saved.single()

		assertEquals(setOf(ClientAuthenticationMethod.NONE), client.clientAuthenticationMethods)
		assertTrue(client.clientSettings.isRequireProofKey, "no secret survives on a laptop; PKCE is what is left")
		assertTrue(
			client.clientSettings.isRequireAuthorizationConsent,
			"the consent screen is the only thing between a self-made client and somebody's backlog",
		)
	}

	@Test
	fun `the grants are fixed, and include refresh`() {
		// The spike issued no refresh token because its client declared only
		// AUTHORIZATION_CODE, which would have left the refresh-rotation MUST with
		// nothing to rotate.
		service().register(request())

		assertEquals(
			setOf(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN),
			saved.single().authorizationGrantTypes,
		)
	}

	@Test
	fun `tokens are opaque, and rotate`() {
		service().register(request())
		val tokens = saved.single().tokenSettings

		assertEquals(
			OAuth2TokenFormat.REFERENCE,
			tokens.accessTokenFormat,
			"Kanso is both servers over one Postgres, so a JWT buys stateless validation it does not need",
		)
		assertEquals(Duration.ofHours(1), tokens.accessTokenTimeToLive)
		assertEquals(Duration.ofDays(60), tokens.refreshTokenTimeToLive)
		assertFalse(tokens.isReuseRefreshTokens, "a refresh token is consumed on use, or there is no rotation")
	}

	@Test
	fun `the scopes are this instance's two, whatever was asked for`() {
		service().register(request())
		assertEquals(OAuthScopes.ALL.toSet(), saved.single().scopes)
	}

	@Test
	fun `a scope outside the two is refused rather than quietly narrowed`() {
		// Narrowing would leave the client believing it has something it does not, and
		// the symptom would arrive as a 403 at the first write.
		val refusal = assertFailsWith<ClientRegistrationRefused> {
			service().register(request(scope = "kanso:read kanso:admin"))
		}
		assertEquals("invalid_client_metadata", refusal.code)
		assertTrue(refusal.reason.contains("kanso:admin"))
		assertTrue(saved.isEmpty(), "nothing is written by a refused registration")
	}

	@Test
	fun `at least one redirect uri is required`() {
		assertEquals(
			"invalid_redirect_uri",
			assertFailsWith<ClientRegistrationRefused> { service().register(request(redirectUris = null)) }.code,
		)
		assertEquals(
			"invalid_redirect_uri",
			assertFailsWith<ClientRegistrationRefused> { service().register(request(redirectUris = emptyList())) }.code,
		)
	}

	@Test
	fun `one bad redirect uri refuses the whole registration`() {
		// A partial registration is a client that works until it does not.
		val refusal = assertFailsWith<ClientRegistrationRefused> {
			service().register(
				request(redirectUris = listOf("http://127.0.0.1:9999/cb", "https://evil.com/cb")),
			)
		}
		assertEquals("invalid_redirect_uri", refusal.code)
		assertTrue(refusal.reason.contains("https://evil.com/cb"), "the refusal names which one")
		assertTrue(saved.isEmpty())
	}

	@Test
	fun `a full table refuses rather than making room`() {
		// Deleting a stranger's client to fit another stranger's is worse than saying no:
		// the first stranger is somebody's colleague whose agent stops working.
		val refusal = assertFailsWith<ClientRegistrationRefused> {
			service(count = 200, cap = 200).register(request())
		}
		assertEquals("invalid_client_metadata", refusal.code)
		assertTrue(saved.isEmpty())
	}

	/**
	 * `client_name` is `varchar(200)` and `redirect_uris` is one `varchar(1000)` holding
	 * the comma-joined list — the library's own schema, copied verbatim and not ours to
	 * widen. Nothing checked either, so an over-long name reached Postgres and came back
	 * as a `DataIntegrityViolationException`: a **500 from an unauthenticated endpoint**,
	 * where RFC 7591 asks for a 400 carrying a code the client can read.
	 */
	@Test
	fun `a name longer than the column is a 400 with a reason, not a 500 from Postgres`() {
		val refusal = assertFailsWith<ClientRegistrationRefused> {
			service().register(request(clientName = "n".repeat(201)))
		}
		assertEquals("invalid_client_metadata", refusal.code)
		assertTrue(refusal.reason.contains("200"), "a refusal a client cannot act on costs it a retry loop")
		assertTrue(saved.isEmpty())
	}

	@Test
	fun `a name that exactly fills the column is still a registration`() {
		// The bound is the column, not a taste in names: refusing 200 as well would be a
		// second rule nobody wrote down.
		service().register(request(clientName = "n".repeat(200)))
		assertEquals(200, saved.single().clientName.length)
	}

	@Test
	fun `more redirect uris than the row can hold is refused rather than truncated`() {
		val many = (1..40).map { "http://127.0.0.1:$it/callback" }
		val refusal = assertFailsWith<ClientRegistrationRefused> { service().register(request(redirectUris = many)) }

		assertEquals("invalid_client_metadata", refusal.code)
		assertTrue(saved.isEmpty(), "the alternative is a client whose last callback silently is not registered")
	}

	@Test
	fun `one enormous but otherwise allowed redirect uri is refused too`() {
		// Loopback on any port is allowed by policy, and policy says nothing about length —
		// so a single legal URI can still be longer than the column that has to hold it.
		val refusal = assertFailsWith<ClientRegistrationRefused> {
			service().register(request(redirectUris = listOf("http://127.0.0.1:9999/" + "c".repeat(1200))))
		}
		assertEquals("invalid_client_metadata", refusal.code)
		assertTrue(saved.isEmpty())
	}

	@Test
	fun `a missing client name gets one rather than an empty string`() {
		// `client_name` is optional in RFC 7591 and it is what the consent screen shows a
		// member. A blank there would ask them to authorise nothing in particular.
		service().register(request(clientName = null))
		assertTrue(saved.single().clientName.isNotBlank())
	}
}

package dev.kanso.oauth

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.mcp.McpChallenge
import dev.kanso.mcp.McpResource
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter
import org.springframework.security.oauth2.server.authorization.web.OAuth2ClientAuthenticationFilter
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.DelegatingAuthenticationConverter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The authorisation server as this application actually assembles it.
 *
 * `application-test.yml` sets `kanso.auth.mode: dev`, so `authorizationServerChain` is
 * absent from every other class in this suite — and with it `/oauth2/authorize`,
 * `AgentPrincipalFilter` as installed, `ResourceValidator` as wired, and both
 * `IssuerAppending*` handlers. That is a whole half of this branch, and the half where
 * most of its findings were: delete the two lines that install the RFC 9207 handlers, or
 * the one that installs the audience validator, and the rest of this suite stays green
 * while two MUSTs quietly stop holding.
 *
 * **This class buys a second context-cache entry, and that is a real cost** —
 * `SecurityBootstrapTest` records what a second context configuration does to this suite,
 * and the reason there is exactly one here rather than one per question is that the price
 * is paid per *configuration*, not per assertion. One more is affordable. Testing this
 * feature at all is what it buys, so everything that needs the oidc chain belongs in this
 * file rather than in a third context.
 *
 * `webEnvironment = NONE`, like [PostgresTest]: what is under test is a bean graph, not a
 * response. Where a request is needed it is driven through the installed filters by hand,
 * which is closer to the deployment than MockMvc would be anyway — these are the filter
 * objects the container holds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["kanso.auth.mode=oidc"])
@ActiveProfiles("test")
class OidcChainWiringTest {

	companion object {
		/** [PostgresTest]'s started singleton, so this costs a context and not a database. */
		@JvmStatic
		@ServiceConnection
		val postgres: PostgreSQLContainer = PostgresTest.postgres
	}

	@Autowired lateinit var chains: List<SecurityFilterChain>
	@Autowired lateinit var tokenGenerator: OAuth2TokenGenerator<*>

	private val authorizationServer: DefaultSecurityFilterChain
		get() = chains.first() as DefaultSecurityFilterChain

	private val endpoint: OAuth2AuthorizationEndpointFilter
		get() = authorizationServer.filters.filterIsInstance<OAuth2AuthorizationEndpointFilter>().single()

	private fun request(method: String, uri: String) = MockHttpServletRequest(method, uri)

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	@Test
	fun `both chains are built, and the authorisation server's comes first`() {
		assertEquals(
			2,
			chains.size,
			"in oidc mode there are two: the library's, and Kanso's own answering everything it did not claim",
		)
		assertTrue(
			authorizationServer.filters.any { it is OAuth2AuthorizationEndpointFilter },
			"the first chain has to be the one with the endpoints; ordering is what keeps them apart",
		)
	}

	/**
	 * The claim `AuthorizationServerConfig` calls its load-bearing line: adjacent prefixes,
	 * opposite directions. Kanso serves `/oauth2/authorization/{provider}` for signing
	 * *in* with Google and the library serves `/oauth2/authorize` for signing *out* to an
	 * agent, and until now nothing checked which chain each of them lands on — a chain
	 * matching every path under `/oauth2/` would have swallowed the Google button, and the
	 * symptom is a sign-in screen that stops working. Kotlin nests block comments, so the
	 * wildcard is spelled out in prose rather than written.
	 */
	@Test
	fun `the first chain claims the library's endpoints and nothing adjacent to them`() {
		val claimed = listOf(
			"GET" to "/oauth2/authorize",
			"POST" to "/oauth2/authorize",
			"POST" to "/oauth2/token",
			"POST" to "/oauth2/revoke",
		)
		for ((method, uri) in claimed) {
			assertTrue(
				authorizationServer.matches(request(method, uri)),
				"$method $uri is the library's; a rule in SecurityConfig must never be reached for it",
			)
		}

		val notClaimed = listOf(
			// Signing *in* with a provider. One character of prefix apart from the above.
			"GET" to "/oauth2/authorization/google",
			// Kanso's own controller, rate-limited by Kanso's own filter.
			"POST" to "/connect/register",
			// Kanso's own document. The *authorization-server* one next to it is the
			// library's and is claimed; this one is not.
			"GET" to McpChallenge.RESOURCE_METADATA_PATH,
			"POST" to McpResource.PATH,
			"GET" to "/api/me",
		)
		for ((method, uri) in notClaimed) {
			assertTrue(
				!authorizationServer.matches(request(method, uri)),
				"$method $uri belongs to Kanso's chain, and this one has no session handling at all",
			)
		}
	}

	/**
	 * `AgentPrincipalFilter` narrows the principal to a bare user id so the library can
	 * persist the authorisation it grants, and it only works if it runs *before* the
	 * endpoint that records it. Asserted on the installed list, because the position is
	 * the whole of what `addFilterBefore` was for.
	 */
	@Test
	fun `the principal is narrowed before the endpoint that persists it`() {
		val filters = authorizationServer.filters
		val narrowing = filters.indexOfFirst { it is AgentPrincipalFilter }
		val recording = filters.indexOfFirst { it is OAuth2AuthorizationEndpointFilter }

		assertTrue(narrowing >= 0, "a filter nobody registered narrows nothing")
		assertTrue(
			narrowing < recording,
			"the library records whatever principal it finds, and a Kanso one cannot make its JSON round trip",
		)
	}

	/**
	 * Three configuration lines, none of which any other test can see.
	 *
	 * The consent page is where the member is asked at all. The two handlers are RFC
	 * 9207's `iss` on the granted response and on the refused one — a MUST — and deleting
	 * both leaves every other test in this suite green.
	 */
	@Test
	fun `the endpoint asks on Kanso's page and names the issuer in both answers`() {
		assertEquals(
			CONSENT_PAGE,
			ReflectionTestUtils.getField(endpoint, "consentPage"),
			"the library's own page would ask the question in the library's words, on the library's screen",
		)
		assertTrue(
			ReflectionTestUtils.getField(endpoint, "authenticationSuccessHandler") is IssuerAppendingSuccessHandler,
			"without iss on the granted response a client cannot tell which server answered it",
		)
		assertTrue(
			ReflectionTestUtils.getField(endpoint, "authenticationFailureHandler") is IssuerAppendingFailureHandler,
			"and the refusal is the half a mix-up attack would use, so it carries iss too",
		)
	}

	/**
	 * RFC 8707's first enforcement point, as installed. `setAuthenticationValidator`
	 * *replaces*, so this is also the assertion that the library's own default is still
	 * wrapped rather than dropped — [ResourceValidatorTest] owns that behaviour; this owns
	 * the fact that it is reached at all.
	 */
	@Test
	fun `the code request provider validates the resource a token would be bound to`() {
		// Wrapped: observability decorates the manager, so the `ProviderManager` is one
		// `delegate` in. Unwrapped by type rather than by depth, so a second wrapper does
		// not turn this into a cast error somebody has to debug.
		var manager = ReflectionTestUtils.getField(endpoint, "authenticationManager")
		while (manager !is ProviderManager) {
			manager = ReflectionTestUtils.getField(manager!!, "delegate")
		}
		val provider = manager.providers
			.filterIsInstance<OAuth2AuthorizationCodeRequestAuthenticationProvider>()
			.single()

		assertTrue(
			ReflectionTestUtils.getField(provider, "authenticationValidator") is ResourceValidator,
			"the library's default validator accepts any resource, and a token good everywhere is the finding",
		)
	}

	/**
	 * The two halves of [PublicClientRefresh], as installed rather than as unit-tested.
	 *
	 * Both were confirmed missing against a running server: no refresh token was issued at
	 * all, and a refresh request was answered 401 with an empty body. Either line removed
	 * puts the connector back to one hour.
	 */
	@Test
	fun `a public client can be authenticated on a refresh, and given something to refresh with`() {
		val delegates = ReflectionTestUtils.getField(
			tokenGenerator as DelegatingOAuth2TokenGenerator,
			"tokenGenerators",
		) as List<*>
		assertTrue(
			delegates.any { it is PublicClientRefreshTokenGenerator },
			"the library's own refresh generator returns null for a public client, so there is nothing to rotate",
		)

		val clientAuth = authorizationServer.filters
			.filterIsInstance<OAuth2ClientAuthenticationFilter>()
			.single()
		val converter = ReflectionTestUtils.getField(clientAuth, "authenticationConverter")
		val converters = ReflectionTestUtils.getField(
			converter as DelegatingAuthenticationConverter,
			"delegates",
		) as List<*>
		assertTrue(
			converters.any { it is PublicClientRefreshConverter },
			"none of the library's five recognises a public client on a refresh, so the request arrives anonymous",
		)
	}

	/**
	 * Commit `bd46b49`, asserted on the chain that carries the defect.
	 *
	 * `HttpSessionSecurityContextRepository` hands back the *stored* `SecurityContext`
	 * rather than a copy, so a filter here that assigns through
	 * `SecurityContextHolder.getContext()` rewrites the member's session — and the member's
	 * next request to any Kanso endpoint arrives holding a bare `String`: authenticated
	 * enough for `anyRequest().authenticated()`, unreadable to `CurrentUser`, so every
	 * screen refuses including the one this grant is revoked from. A link to any path on
	 * this chain is a top-level navigation, so `Lax` sends the cookie.
	 *
	 * Until now that fix was asserted only against a hand-constructed filter, and
	 * `McpBearerFilter` had already drifted from the rule. This drives the installed chain
	 * with a session in it.
	 */
	@Test
	fun `a chain-1 request leaves the browser's own session context alone`() {
		val member = KansoLocalUser(UUID.randomUUID(), "elie@example.test", "Elie")
		val stored: SecurityContext = SecurityContextHolder.createEmptyContext().apply {
			authentication = UsernamePasswordAuthenticationToken(member, null, member.authorities)
		}
		val session = MockHttpSession().apply {
			setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, stored)
		}
		val request = request("GET", "/oauth2/authorize").apply {
			setSession(session)
			// A client id nothing registered, so the endpoint refuses. Whatever it answers
			// is not this test's business; what the session still holds is.
			addParameter("client_id", "not-registered-${UUID.randomUUID()}")
		}

		runCatching {
			MockFilterChain(NoopServlet(), *authorizationServer.filters.toTypedArray<Filter>())
				.doFilter(request, MockHttpServletResponse())
		}

		assertSame(
			member,
			stored.authentication?.principal,
			"this object is the member's session; rewriting it signs them out of everything but the cookie",
		)
	}

	/** Ends the chain without a `DispatcherServlet`, which this context does not have. */
	private class NoopServlet : HttpServlet()
}

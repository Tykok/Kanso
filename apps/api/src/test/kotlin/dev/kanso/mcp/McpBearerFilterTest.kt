package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.auth.KansoAgentUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.oauth.DEV_MODE_REFUSAL
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.server.RequestPath
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.pattern.PathPatternParser
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter, driven directly with mock servlet objects rather than through MockMvc.
 *
 * `PostgresTest` is `WebEnvironment.NONE`, and `SecurityBootstrapTest` explains why a
 * second Spring context per class is avoided here. A filter is a function of a request
 * and a response, so the mock pair tests it exactly and costs nothing.
 *
 * The filter under test is constructed rather than autowired, and the mode it is
 * constructed with is the reason: this suite runs with `kanso.auth.mode: dev`, where the
 * only correct answer at `/api/mcp` is that there is no door — so the container's own
 * bean cannot be used to test the door. It is used for exactly one test, the last one,
 * which asserts that refusal on the real wiring.
 */
@Transactional
class McpBearerFilterTest : PostgresTest() {

	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService

	/** The application's own chain, built in the dev mode this suite runs in. */
	@Autowired lateinit var chain: SecurityFilterChain

	private val grants by lazy { TestGrants(clients, authorizations, consents) }
	private val filter by lazy { McpBearerFilter(authorizations, clients, users, authMode = "oidc") }
	private val inDevMode by lazy { McpBearerFilter(authorizations, clients, users, authMode = "DEV") }

	/** What this instance calls itself on the origin `MockHttpServletRequest` defaults to. */
	private val ours = McpResource.canonical("http://localhost")
	private val read = setOf(OAuthScopes.READ)
	private val client = "claude-code"

	private fun member(): User = users.createLocalUser(
		email = "agent-${UUID.randomUUID()}@kanso.test",
		displayName = "Agent owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	private fun call(header: String?, through: McpBearerFilter = filter): MockHttpServletResponse {
		val request = MockHttpServletRequest("POST", McpResource.PATH)
		if (header != null) request.addHeader("Authorization", header)
		val response = MockHttpServletResponse()
		through.doFilter(request, response, MockFilterChain())
		return response
	}

	@Test
	fun `a valid token becomes the member who authorised it`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")

		call("Bearer $token")

		val principal = SecurityContextHolder.getContext().authentication?.principal
		assertTrue(principal is KansoAgentUser)
		assertEquals(owner.id, principal.kansoUserId, "the token acts as its owner, with their rights")
		assertEquals(owner.email, principal.kansoEmail, "everything downstream reads the member off the principal")
		assertEquals("claude-code", principal.clientId, "provenance travels with the principal")
	}

	@Test
	fun `no header leaves the context empty and challenges`() {
		val response = call(null)

		assertEquals(401, response.status)
		assertTrue(
			response.getHeader("WWW-Authenticate")!!.contains("resource_metadata="),
			"a 401 without the challenge is a dead end the client cannot recover from",
		)
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `an unknown token is refused, and says nothing about why`() {
		val response = call("Bearer kat_not-a-real-token")

		assertEquals(401, response.status)
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `a revoked grant stops working on the very next request`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")
		call("Bearer $token")
		assertTrue(SecurityContextHolder.getContext().authentication != null, "valid before revocation")
		SecurityContextHolder.clearContext()

		grants.revoke(owner, "claude-code")

		assertEquals(401, call("Bearer $token").status, "no window: the filter resolves against the same table")
	}

	@Test
	fun `a token issued for another resource is refused`() {
		val owner = member()
		val token = grants.issue(
			owner,
			setOf(OAuthScopes.READ),
			clientId = "claude-code",
			resource = "https://someone-elses-server.example.com/api/mcp",
		)

		assertEquals(401, call("Bearer $token").status, "a token minted for another audience is not a free pass")
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `a token bound to no resource at all is refused`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code", resource = null)

		assertEquals(
			401,
			call("Bearer $token").status,
			"absence is not consent — an unbound token would otherwise be good everywhere",
		)
	}

	/**
	 * The row a repeated `resource` produces cannot be read back at all: Jackson's
	 * allowlist refuses to reconstitute the `String[]` it was stored as, so the library
	 * raises where it should have returned nothing. This test is named for that, because
	 * that is the only thing it can prove — the refusal happens before `Audience` is ever
	 * consulted, and the rule about repeated values is asserted directly below instead.
	 */
	@Test
	fun `a grant this server cannot read back is refused, not raised`() {
		val owner = member()
		val token = grants.issue(
			owner,
			setOf(OAuthScopes.READ),
			clientId = "claude-code",
			resource = arrayOf(ours, "https://elsewhere.example.com/api/mcp"),
		)

		assertEquals(
			401,
			call("Bearer $token").status,
			"a grant this server cannot parse is not a grant, and not a 500 either",
		)
	}

	/**
	 * The audience rule itself, on an authorisation held in the hand.
	 *
	 * In memory because it has to be: the store cannot return a repeated `resource`, so
	 * driving this through the filter would only ever prove the catch above — and would
	 * pass with [Audience] deleted. Both shapes RFC 8707's parameter arrives in are
	 * asserted, because a stored grant hands back a `Collection` where a live request hands
	 * back an `Array`, and the rule has to be the same one for both.
	 */
	@Test
	fun `two resources bind a token to neither, and one of them being ours does not help`() {
		val owner = member()
		val elsewhere = "https://elsewhere.example.com/api/mcp"

		assertFalse(
			Audience.matches(grants.authorize(owner, read, client, resource = arrayOf(ours, elsewhere)), ours),
			"naming us alongside somebody else is not naming us",
		)
		assertFalse(
			Audience.matches(grants.authorize(owner, read, client, resource = listOf(ours, elsewhere)), ours),
			"the same rule, for the shape a stored grant reads back as",
		)
		assertFalse(
			Audience.matches(grants.authorize(owner, read, client, resource = elsewhere), ours),
			"and a single resource that is not ours is still not ours",
		)
		assertTrue(
			Audience.matches(grants.authorize(owner, read, client, resource = ours), ours),
			"one value, and it is ours: the only combination that opens the door",
		)
	}

	@Test
	fun `a grant whose principal is not a Kanso user id is refused, not looked up`() {
		val owner = member()
		val token = grants.issue(
			owner,
			setOf(OAuthScopes.READ),
			clientId = "claude-code",
			principalName = owner.email,
		)

		assertEquals(
			401,
			call("Bearer $token").status,
			"a name that is not a uuid means the grant was written by something other than our chain",
		)
	}

	@Test
	fun `the scopes granted travel with the principal, so a writing tool can refuse a read-only grant`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")

		call("Bearer $token")

		val principal = SecurityContextHolder.getContext().authentication?.principal as KansoAgentUser
		assertEquals(setOf(OAuthScopes.READ), principal.scopes)
		assertTrue(OAuthScopes.WRITE !in principal.scopes)
	}

	private fun at(uri: String): MockHttpServletResponse {
		val response = MockHttpServletResponse()
		filter.doFilter(MockHttpServletRequest("POST", uri), response, MockFilterChain())
		return response
	}

	/**
	 * Path parameters are stripped before a request is routed and **kept** in
	 * `getRequestURI()`, so a filter reasoning about the raw URI and a `DispatcherServlet`
	 * reasoning about the parsed path disagree over one character: both are handed
	 * `/api;x=y/mcp`, `PathPatternParser` matches it against `/api/mcp`, and a string
	 * prefix does not. The filter stood aside and the request was routed anyway — in dev
	 * mode that is the 503 gone, and with it the only thing between an agent and
	 * `DevAuthenticationFilter`'s header identity.
	 */
	@Test
	fun `a path parameter is not a way past the filter`() {
		assertEquals(401, at("/api;x=y/mcp").status, "the path Spring routes on is the path this filter guards")
		assertEquals(401, at("/api;/mcp").status, "an empty path parameter is the same trick with less to type")
		assertEquals(401, at("/api/%6Dcp").status, "and so is an escaped letter, which the parsed path decodes")
	}

	/**
	 * The rule behind the test above, over the shapes a URL can take. One direction only,
	 * because only one direction is a hole: anything Spring would route to `/api/mcp` has
	 * to have passed the filter first. The reverse costs a 401 where a 404 would have
	 * done, which is the safe way round for a guard to be wrong.
	 *
	 * `/api/../api/mcp` reaches neither, and is here as a pin: Tomcat normalises the URI
	 * before the filter or the dispatcher sees it, so what this holds is that the two
	 * still agree on the form that never arrives.
	 */
	@Test
	fun `whatever Spring routes to the endpoint, this filter has already seen`() {
		val routed = PathPatternParser.defaultInstance.parse(McpResource.PATH)
		val shapes = mapOf(
			"/api/mcp" to true,
			"/api;x=y/mcp" to true,
			"/api;/mcp" to true,
			"/api/mcp;session=1" to true,
			"/api/%6Dcp" to true,
			"/api/mcp/" to true,
			"//api/mcp" to false,
			"/api//mcp" to false,
			"/api/../api/mcp" to false,
			"/API/MCP" to false,
			"/api/tickets" to false,
		)

		for ((uri, guarded) in shapes) {
			assertEquals(
				if (guarded) 401 else 200,
				at(uri).status,
				"$uri — 401 is this filter answering, 200 is it standing aside for something else to refuse",
			)
			if (routed.matches(RequestPath.parse(uri, ""))) {
				assertTrue(guarded, "$uri is routed to the endpoint, so it cannot be a request this filter skips")
			}
		}
	}

	@Test
	fun `requests to anything but the MCP endpoint are none of this filter's business`() {
		val request = MockHttpServletRequest("GET", "/api/tickets")
		val response = MockHttpServletResponse()
		val chain = MockFilterChain()

		filter.doFilter(request, response, chain)

		assertEquals(200, response.status, "a session cookie answers everywhere else")
		assertTrue(chain.request === request, "the request continued down the chain untouched")
	}

	/**
	 * In dev mode a 401 would be a lie: it invites the client into a flow that does not
	 * exist, and it will keep trying. 503 with the sentence is the only answer that ends
	 * anywhere. Mixed case on purpose — `KANSO_AUTH_MODE=DEV` is dev mode, and reading it
	 * literally is the bug `AuthorizationServerConfig` avoids with an expression.
	 */
	@Test
	fun `in dev mode there is no door, and the refusal says so`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")

		val response = call("Bearer $token", through = inDevMode)

		assertEquals(503, response.status, "no authorisation server exists to send anybody to")
		assertEquals(DEV_MODE_REFUSAL, response.contentAsString.trim(), "the sentence names the variable to change")
		assertNull(SecurityContextHolder.getContext().authentication, "a valid token is still not honoured here")
	}

	/**
	 * Deployed under a servlet context path, `/api/mcp` arrives as `/kanso/api/mcp`. A
	 * filter matching the raw URI would skip it and leave the session chain to answer —
	 * an endpoint quietly falling back to cookie authentication, which is the wrong
	 * direction for a failure to take.
	 */
	@Test
	fun `the endpoint is found under a context path, and the challenge names it`() {
		val request = MockHttpServletRequest("POST", "/kanso${McpResource.PATH}").apply { contextPath = "/kanso" }
		val response = MockHttpServletResponse()

		filter.doFilter(request, response, MockFilterChain())

		assertEquals(401, response.status, "the filter has to recognise its own endpoint wherever it is mounted")
		assertEquals(
			McpChallenge.header(OAuthScopes.ALL, "http://localhost/kanso"),
			response.getHeader("WWW-Authenticate"),
			"and send the client to a document that exists on this deployment",
		)
	}

	/**
	 * The wiring, not the behaviour: everything above drives a filter this test built, so
	 * something has to assert that the deployed chain has one at all — and that it stands
	 * ahead of dev mode's filter, which would otherwise hand an agent a header identity
	 * on the one instance that must not have a door.
	 */
	@Test
	fun `the application's chain carries the filter, ahead of dev mode's`() {
		val positions = chain.filters.withIndex()
		val bearer = positions.firstOrNull { it.value is McpBearerFilter }
		val dev = positions.firstOrNull { it.value is DevAuthenticationFilter }

		assertTrue(bearer != null, "a filter nobody registered protects nothing")
		assertTrue(dev != null, "this suite runs in dev mode; the assertion below depends on it")
		assertTrue(bearer.index < dev.index, "the refusal comes before the header identity")
	}
}

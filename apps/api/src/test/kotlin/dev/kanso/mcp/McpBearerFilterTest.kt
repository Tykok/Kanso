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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
	 * Two audiences bind a token to neither — and this row cannot even be read back:
	 * Jackson's allowlist refuses to reconstitute the `String[]` it was stored as, so the
	 * library raises rather than returning nothing. Both roads end at the same 401, which
	 * is the assertion worth making: an unreadable grant is refused, not raised.
	 */
	@Test
	fun `a token naming this resource and another is refused`() {
		val owner = member()
		val token = grants.issue(
			owner,
			setOf(OAuthScopes.READ),
			clientId = "claude-code",
			resource = arrayOf(McpResource.canonical("http://localhost"), "https://elsewhere.example.com/api/mcp"),
		)

		assertEquals(
			401,
			call("Bearer $token").status,
			"two audiences bind a token to neither, and a grant we cannot parse is not a grant",
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

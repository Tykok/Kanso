package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoAgentUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The endpoint an authorised agent actually reaches, driven through the filter that
 * decides whether it may.
 *
 * A standalone MockMvc rather than the application's own, and the reason is the profile:
 * this suite runs with `kanso.auth.mode: dev`, where the only correct answer at
 * `/api/mcp` is that there is no door at all (`McpBearerFilterTest` owns that). So the
 * container's chain cannot be used to test the door — and a controller test that skipped
 * the filter instead would assert that a request nobody authenticated gets an answer,
 * which is the one thing about this endpoint that must not be true.
 *
 * So the filter is constructed the way [AgentRightsTest] constructs it, in the mode that
 * opens the door, and placed in front of the controller. What is asserted below is
 * therefore the real sequence: an opaque token issued by [TestGrants] the way the token
 * endpoint would, resolved into a [KansoAgentUser], and a JSON-RPC exchange answered
 * behind it.
 */
@Transactional
class McpStubTest : PostgresTest() {

	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService
	@Autowired lateinit var build: BuildProperties
	@Autowired lateinit var transactionManager: PlatformTransactionManager

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	private val mvc: MockMvc by lazy {
		// The explicit type argument is the builder's own generic self-type, which Kotlin
		// cannot infer through `addFilters`. Nothing about it is a decision.
		MockMvcBuilders.standaloneSetup(McpStubController(build))
			.addFilters<StandaloneMockMvcBuilder>(McpBearerFilter(authorizations, clients, users, transactionManager, authMode = "oidc"))
			.build()
	}

	private fun member(): User = users.createLocalUser(
		email = "stub-${UUID.randomUUID()}@kanso.test",
		displayName = "Agent owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	/** The token a member's agent would be holding after the consent screen. */
	private fun bearer(): String = "Bearer " + grants.issue(member(), setOf(OAuthScopes.READ), clientId = CLIENT)

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	private fun call(body: String, authorization: String?) = mvc.post(McpResource.PATH) {
		contentType = MediaType.APPLICATION_JSON
		content = body
		if (authorization != null) header("Authorization", authorization)
	}

	/**
	 * Re-asserted here rather than left to `McpBearerFilterTest`, because that suite drives
	 * the filter with no controller behind it: a mapping added at this path with the guard
	 * accidentally in front of *something else* would leave every test there green while
	 * this endpoint answered strangers.
	 */
	@Test
	fun `an unauthenticated call is refused with the challenge, not answered`() {
		call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", authorization = null).andExpect {
			status { isUnauthorized() }
			header {
				string(
					"WWW-Authenticate",
					McpChallenge.header(OAuthScopes.ALL, "http://localhost"),
				)
			}
		}
	}

	@Test
	fun `initialize names this server and the revision it speaks`() {
		call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", bearer()).andExpect {
			status { isOk() }
			jsonPath("$.jsonrpc") { value("2.0") }
			jsonPath("$.id") { value(1) }
			jsonPath("$.result.protocolVersion") { exists() }
			jsonPath("$.result.serverInfo.name") { value("kanso") }
			jsonPath("$.result.serverInfo.version") { value(build.version) }
		}
	}

	/**
	 * The deliverable of the whole plan, in one assertion: `tools/list` answered over a
	 * session an agent got by asking a member. Empty, and honestly so — see
	 * [McpStubController]'s own note on why a stub that claimed a tool would be worse than
	 * one that claims none.
	 */
	@Test
	fun `tools list answers an authorised agent with an empty list`() {
		call("""{"jsonrpc":"2.0","id":"t1","method":"tools/list"}""", bearer()).andExpect {
			status { isOk() }
			jsonPath("$.id") { value("t1") }
			jsonPath("$.result.tools") { isArray() }
			jsonPath("$.result.tools.length()") { value(0) }
		}
	}

	/** The principal the exchange above ran as, named rather than assumed. */
	@Test
	fun `the exchange runs as the member who authorised it`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = CLIENT)

		call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", "Bearer $token").andExpect { status { isOk() } }

		// Read after the call rather than during it: nothing in this chain clears the
		// holder — `SecurityContextHolderFilter` is the application's, not this setup's —
		// so what stands here is what the filter left, on the same thread that served the
		// request.
		val principal = SecurityContextHolder.getContext().authentication?.principal
		assertTrue(principal is KansoAgentUser, "the filter resolved the token into an agent principal")
		assertEquals(owner.id, principal.kansoUserId, "and the agent is its owner, not a new kind of user")
		assertEquals(CLIENT, principal.clientId, "with the application it came from travelling alongside")
	}

	@Test
	fun `a method this stub does not answer is a JSON-RPC error, not an HTTP one`() {
		call("""{"jsonrpc":"2.0","id":9,"method":"tools/call"}""", bearer()).andExpect {
			// 200 on purpose: the transport worked. A 404 here would read to a client as a
			// server that has gone away, and it would reconnect rather than report.
			status { isOk() }
			jsonPath("$.error.code") { value(-32601) }
			jsonPath("$.result") { doesNotExist() }
		}
	}

	/**
	 * A real client sends `notifications/initialized` the moment `initialize` returns, and
	 * JSON-RPC 2.0 §4.1 forbids answering a request with no `id`. Without this the stub's
	 * second exchange with every client it ever talks to is a protocol violation — which
	 * is the kind of thing that shows up as a client that "sometimes" fails to connect.
	 */
	@Test
	fun `a notification is accepted in silence`() {
		call("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", bearer()).andExpect {
			status { isAccepted() }
			content { string("") }
		}
	}

	private companion object {
		const val CLIENT = "claude-code"
	}
}

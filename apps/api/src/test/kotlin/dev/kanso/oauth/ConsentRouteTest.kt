package dev.kanso.oauth

import dev.kanso.MockMvcTest
import dev.kanso.config.KansoProperties
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One rule in `SecurityConfig`, and the only test in this suite that can see it.
 *
 * [ConsentControllerTest] calls the controller method, which means it has already walked
 * past the filter chain: delete the `permitAll` for this path and all six of its tests
 * stay green while every anonymous visitor is answered `401` by
 * `HttpStatusEntryPoint` — the entire first-run path, where an agent sends a member here
 * before they have a session, breaks with nothing red to say so. So the request is made
 * for real, through the chain the application builds.
 *
 * `anonymous()` is not decoration. This suite runs with `kanso.auth.mode: dev`, where
 * `DevAuthenticationFilter` authenticates *every* request that arrives with no
 * authentication already in the context — so without it there is no such thing as an
 * anonymous request here, and the rule under test would be invisible again.
 */
class ConsentRouteTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var props: KansoProperties
	@Autowired lateinit var clients: RegisteredClientRepository

	/**
	 * The controller refuses an unregistered client before it offers anybody a login
	 * screen — that ordering is what stops a stranger planting a return address in a fresh
	 * session — so this test has to register one or it would be asserting the refusal
	 * rather than the rule it exists for. A fresh id each run because this class is not
	 * `@Transactional` and the row outlives the test.
	 */
	private val clientId = "route-test-${UUID.randomUUID()}"

	@BeforeEach
	fun register() {
		clients.save(
			RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId(clientId)
				.clientName("Claude Code")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://127.0.0.1:8765/callback")
				.apply { OAuthScopes.ALL.forEach { scope(it) } }
				.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
				.build(),
		)
	}

	@Test
	fun `an anonymous visitor is sent to log in rather than refused`() {
		val response = mvc.get(CONSENT_PAGE) {
			param("client_id", clientId)
			param("scope", OAuthScopes.READ)
			param("state", "s")
			with(anonymous())
		}.andReturn().response

		assertEquals(
			302,
			response.status,
			"a 401 here is a dead end: the browser is mid-flow and has nowhere to go from it",
		)
		assertTrue(
			response.getHeader("Location").orEmpty().startsWith("${props.webOrigin}/login"),
			"and the way out is the app's login screen, carrying the way back",
		)
	}

	/**
	 * The one screen in Kanso where being framed is an attack.
	 *
	 * Everything else here answers a `fetch` with JSON; this answers a browser with two
	 * buttons, and a page that can be loaded in an invisible iframe over somebody else's
	 * site is a page whose Authorise button can be clicked by a mis-aimed cursor. The
	 * header comes from the framework default and was pinned by nothing — a `.headers { }`
	 * added to this chain for any other reason replaces the whole set and would drop it
	 * with no test to notice. Asserted through the chain, because a default is exactly the
	 * kind of thing a controller test cannot see.
	 */
	@Test
	fun `the consent page refuses to be framed`() {
		val response = mvc.get(CONSENT_PAGE) {
			param("client_id", clientId)
			param("scope", OAuthScopes.READ)
			param("state", "s")
			with(anonymous())
		}.andReturn().response

		assertEquals(
			"DENY",
			response.getHeader("X-Frame-Options"),
			"a consent button that can be framed is a consent button somebody else can aim at",
		)
	}
}

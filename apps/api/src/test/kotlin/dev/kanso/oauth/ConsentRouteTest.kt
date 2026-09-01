package dev.kanso.oauth

import dev.kanso.MockMvcTest
import dev.kanso.config.KansoProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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

	@Test
	fun `an anonymous visitor is sent to log in rather than refused`() {
		val response = mvc.get(CONSENT_PAGE) {
			param("client_id", "claude-code")
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
}

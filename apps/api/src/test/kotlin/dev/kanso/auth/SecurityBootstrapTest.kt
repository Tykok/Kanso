package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.oauth.ReturnUrlSuccessHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.util.ReflectionTestUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fresh instance has no identity provider at all, and that used to be
 * inexpressible: the registration bean only existed once one was configured. Now
 * it always exists and may hold nothing, so the thing worth checking is that the
 * filter chain still builds around an empty one — `InMemoryClientRegistrationRepository`
 * would have refused, and the whole context would have failed to start.
 *
 * Deliberately no `@SpringBootTest` of its own: a second context configuration
 * makes the test framework pause and restart the shared one between classes, and
 * not every bean in this application survives that.
 */
class SecurityBootstrapTest : PostgresTest() {

	@Autowired lateinit var registrations: DynamicClientRegistrationRepository
	@Autowired lateinit var chain: SecurityFilterChain

	@Test
	fun `the context starts with no provider configured`() {
		assertTrue(registrations.current().isEmpty(), "nothing is configured in the test profile")
		assertNull(registrations.findByRegistrationId("google"))
		assertTrue(
			chain.filters.any { it is OAuth2AuthorizationRequestRedirectFilter },
			"oauth2Login is wired unconditionally, so the wizard can fill it in later",
		)
	}

	/**
	 * The wiring, not the behaviour — [dev.kanso.oauth.ReturnUrlSuccessHandlerTest] owns
	 * that. Asserted through reflection because `AbstractAuthenticationProcessingFilter`
	 * exposes no getter, and asserted at all because the alternative is a silent
	 * regression: put `SimpleUrlAuthenticationSuccessHandler` back and every test in this
	 * suite stays green while every member who signs in with Google mid-authorisation
	 * lands on the home screen instead of the consent page.
	 */
	@Test
	fun `a provider sign-in is handled by the one that remembers where the flow started`() {
		val filter = chain.filters.filterIsInstance<OAuth2LoginAuthenticationFilter>().single()
		assertTrue(
			ReflectionTestUtils.getField(filter, "successHandler") is ReturnUrlSuccessHandler,
			"a fixed target here abandons the agent authorisation the member was in the middle of",
		)
	}

	@Test
	fun `a provider can be added to a running instance`() {
		try {
			registrations.reload(
				listOf(
					CommonOAuth2Provider.GOOGLE.getBuilder("google")
						.clientId("client-id")
						.clientSecret("client-secret")
						.build()
				)
			)
			assertEquals(listOf("google"), registrations.current().map { it.registrationId })
			assertEquals("client-id", registrations.findByRegistrationId("google")?.clientId)
		} finally {
			registrations.reload(emptyList())
		}
	}
}

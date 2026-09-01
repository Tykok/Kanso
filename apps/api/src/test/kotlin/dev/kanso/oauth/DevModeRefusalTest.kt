package dev.kanso.oauth

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `application-test.yml` sets `kanso.auth.mode: dev`, so this suite runs in exactly the
 * configuration that must not be able to mint a credential — which makes the test free.
 *
 * Dev mode takes identity from an unverified `X-Kanso-User` header. An authorisation
 * server behind that is a credential factory: anybody who can reach it issues a real,
 * durable token for any member. A session obtained that way ends when the process does;
 * a refresh token lasts sixty days.
 */
class DevModeRefusalTest : PostgresTest() {

	@Autowired lateinit var context: ApplicationContext

	@Test
	fun `the authorization server's chain is absent in dev mode`() {
		assertFalse(
			context.containsBean("authorizationServerChain"),
			"no chain means no authorize and no token endpoint — nothing to reach at all",
		)
	}

	@Test
	fun `the services behind it are still present, so the bearer filter can refuse`() {
		// Gating the whole configuration instead of the chain would remove these, and
		// `McpBearerFilter` needs the authorization service to exist in order to look a
		// token up and reject it. A refusal path that cannot be constructed is not a
		// refusal path.
		assertTrue(context.containsBean("authorizationService"))
	}

	@Test
	fun `the refusal says why, in a sentence somebody can act on`() {
		assertTrue(DEV_MODE_REFUSAL.contains("dev"))
		assertTrue(
			DEV_MODE_REFUSAL.contains("KANSO_AUTH_MODE"),
			"naming the variable is the difference between a refusal and a dead end",
		)
	}
}

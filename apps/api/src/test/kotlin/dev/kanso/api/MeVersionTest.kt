package dev.kanso.api

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version has to come from the build, not from a constant somebody edits, and it
 * has to travel — the client reads it off `/api/me` and nothing else exposes it.
 *
 * Two assertions, because the previous shape only made the first one: a test that
 * autowires [BuildProperties] and never calls the endpoint stays green while
 * `AuthController.me()` returns a hard-coded string. That is exactly the failure this
 * branch shipped three times, so the endpoint is called here for real.
 *
 * Its own web environment: [PostgresTest] runs with `WebEnvironment.NONE`, which has no
 * MockMvc to drive. The container is the same started singleton, so this costs a second
 * Spring context and not a second Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeVersionTest {

	companion object {
		@JvmStatic
		@ServiceConnection
		val postgres: PostgreSQLContainer = PostgresTest.postgres
	}

	@Autowired
	lateinit var build: BuildProperties

	@Autowired
	lateinit var mvc: MockMvc

	@Test
	fun `the build stamps a version the API can report`() {
		// BuildProperties.getVersion() is @Nullable (JSpecify), so a null-safe read
		// is required even though the property is only ever absent if buildInfo()
		// never ran — which is exactly the case this test exists to catch.
		assertTrue(!build.version.isNullOrBlank(), "the build must generate a version")
		assertFalse(build.version == "unknown", "a placeholder is not a version")
	}

	/**
	 * `kanso.auth.mode: dev` in the test profile means `DevAuthenticationFilter`
	 * authenticates the request as its default identity, so no credentials are set up
	 * here — the point of the call is the body, not who made it.
	 */
	@Test
	fun `GET api me reports the version the build stamped`() {
		mvc.get("/api/me").andExpect {
			status { isOk() }
			// Not "some non-empty string": the string the build produced. A constant
			// typed into the controller fails here however plausible it looks.
			jsonPath("$.version") { value(build.version) }
		}
	}
}

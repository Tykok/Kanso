package dev.kanso

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Base for the handful of tests that have to arrive through the filter chain.
 *
 * [PostgresTest] runs with `WebEnvironment.NONE` and so has no MockMvc to drive, which
 * makes a whole class of question unaskable there: *who may reach this path without a
 * session* is decided by `SecurityConfig`, and a test that calls a controller method
 * directly has already walked past that decision. `MeVersionTest` needed the same thing
 * for the same reason and carried these three annotations itself.
 *
 * A base class rather than a repeated annotation block, and that is the load-bearing
 * part: the test context cache is keyed on the configuration, so every class that
 * declares its own `@SpringBootTest` buys another context — and `SecurityBootstrapTest`
 * records what that costs, because the framework pauses and restarts the shared one
 * between them. Inherited, the key is one key however many subclasses there are.
 *
 * The container is [PostgresTest]'s started singleton, so this costs a Spring context
 * and not a second Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class MockMvcTest {

	companion object {
		@JvmStatic
		@ServiceConnection
		val postgres: PostgreSQLContainer = PostgresTest.postgres
	}
}

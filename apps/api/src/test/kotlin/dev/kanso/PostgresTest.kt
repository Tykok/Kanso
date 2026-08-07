package dev.kanso

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Base for tests that need the real schema.
 *
 * A real Postgres, not an in-memory substitute: the parts most worth testing here —
 * `WITH RECURSIVE`, `FOR UPDATE SKIP LOCKED`, `ON CONFLICT` on a partial index —
 * exist only in Postgres, and Flyway runs the same migrations the application ships.
 *
 * The container is a started singleton rather than a `@Container` managed by the
 * JUnit extension: the extension stops a static container when its class finishes,
 * and the next class would then inherit a cached Spring context pointing at a
 * container that no longer answers. Ryuk reaps this one when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class PostgresTest {

	companion object {
		@JvmStatic
		@ServiceConnection
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine").apply { start() }
	}
}

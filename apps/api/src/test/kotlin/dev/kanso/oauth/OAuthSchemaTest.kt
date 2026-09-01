package dev.kanso.oauth

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library's own three tables, plus the one column this branch adds to a table
 * Kanso owns. Asserted rather than assumed because the three are *copied* SQL: a
 * paste that lost a column fails here rather than at the first consent screen.
 */
class OAuthSchemaTest : PostgresTest() {

	@Autowired lateinit var jdbc: JdbcClient

	private fun columns(table: String): Set<String> = jdbc
		.sql("SELECT column_name FROM information_schema.columns WHERE table_name = :t")
		.param("t", table)
		.query(String::class.java)
		.list()
		// `list()` is typed from Java, so Kotlin sees String? — an information_schema
		// column name never is, and a Set<String?> would leak that fiction downstream.
		.filterNotNull()
		.toSet()

	@Test
	fun `the library's three tables exist`() {
		assertTrue(columns("oauth2_registered_client").contains("client_id"))
		assertTrue(columns("oauth2_authorization").contains("access_token_value"))
		assertTrue(columns("oauth2_authorization_consent").contains("authorities"))
	}

	@Test
	fun `activity carries the client that typed the change`() {
		assertTrue(
			columns("activity").contains("via_client_id"),
			"provenance is a column on activity, not a second log",
		)
	}

	@Test
	fun `via_client_id is TEXT, because the library chooses its own client id type`() {
		val type = jdbc.sql(
			"""
			SELECT data_type FROM information_schema.columns
			 WHERE table_name = 'activity' AND column_name = 'via_client_id'
			""".trimIndent()
		).query(String::class.java).single()
		assertEquals("text", type, "a foreign key that has to convert is one that will not")
	}
}

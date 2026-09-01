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

	/**
	 * The column and the only value that can reach it have to name the same thing.
	 *
	 * `V18` pointed this key at `oauth2_registered_client(id)` — the library's surrogate —
	 * while `McpBearerFilter` puts the public `client_id` on the principal, so the one
	 * value a service holds would have failed the constraint on insert. Neither
	 * `AgentRightsTest`'s tripwire nor anything else could catch that, because nothing
	 * writes the column yet: this is the assertion that makes plan two's first provenance
	 * write possible rather than a discovery.
	 */
	@Test
	fun `via_client_id points at the client id a service actually holds`() {
		val target = jdbc.sql(
			"""
			SELECT a.attname
			  FROM pg_constraint c
			  JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = c.confkey[1]
			 WHERE c.conname = 'activity_via_client_id_fkey'
			""".trimIndent()
		).query(String::class.java).single()
		assertEquals(
			"client_id",
			target,
			"the surrogate id never leaves the library's own table, so a key on it cannot be written",
		)
	}

	/**
	 * The index that makes the key above legal. Not a widening of the library's copied
	 * schema but its own precondition: `findByClientId` takes a single result, so a
	 * duplicate is already a runtime failure one layer down.
	 */
	@Test
	fun `client_id is unique, which is what the library assumed and the key requires`() {
		val unique = jdbc.sql(
			"""
			SELECT count(*) FROM pg_index i
			  JOIN pg_class t ON t.oid = i.indrelid
			  JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)
			 WHERE t.relname = 'oauth2_registered_client'
			   AND a.attname = 'client_id'
			   AND i.indisunique
			""".trimIndent()
		).query(Int::class.java).single()
		assertEquals(1, unique, "two clients sharing a client_id break the library before they break us")
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

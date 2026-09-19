package dev.kanso.oauth

import dev.kanso.PostgresTest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `oauth2_authorization` holds, asserted against the table rather than against the
 * wrapper — because the wrapper working is not the claim. The claim is that the *column* is
 * no longer a credential.
 *
 * `V27__api_tokens.sql` states the rule for personal access tokens — *a stolen database dump
 * is not a set of live credentials* — and this table was the exception: `McpBearerFilter`
 * resolved a bearer by SQL equality against the value it was handed, so anyone who could
 * read Postgres could replay it. Worse here than the library's usual default, because
 * `PublicClientRefresh` deliberately issues these clients a sixty-day refresh token that a
 * bare `client_id` redeems, and the `client_id` is in the same dump.
 *
 * Not `@Transactional`: `JdbcOAuth2AuthorizationService` is the thing under test and it does
 * its own writing, so the rows are cleaned up by hand at the end of each test rather than by
 * a rollback that would also hide a write that never happened.
 */
class HashedOAuthTokensTest : PostgresTest() {

	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var jdbc: JdbcClient

	private val clientId = "hashed-${UUID.randomUUID()}"
	private lateinit var client: RegisteredClient

	/** `oauth2_authorization.registered_client_id` is a foreign key, so the client comes first. */
	@BeforeEach
	fun register() {
		client = RegisteredClient.withId(UUID.randomUUID().toString())
			.clientId(clientId)
			.clientName("Claude Code")
			.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
			.redirectUri("http://127.0.0.1:8765/callback")
			.apply { OAuthScopes.ALL.forEach { scope(it) } }
			.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
			.build()
		clients.save(client)
	}

	private fun grant(accessToken: String, refreshToken: String): OAuth2Authorization {
		val now = Instant.now()
		val authorization = OAuth2Authorization.withRegisteredClient(client)
			.id(UUID.randomUUID().toString())
			.principalName(UUID.randomUUID().toString())
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizedScopes(setOf(OAuthScopes.READ))
			.accessToken(
				OAuth2AccessToken(
					OAuth2AccessToken.TokenType.BEARER,
					accessToken,
					now,
					now.plus(1, ChronoUnit.HOURS),
					setOf(OAuthScopes.READ),
				),
			)
			.refreshToken(OAuth2RefreshToken(refreshToken, now, now.plus(60, ChronoUnit.DAYS)))
			.build()
		authorizations.save(authorization)
		return authorization
	}

	private fun storedValue(column: String, id: String): String? = jdbc
		.sql("SELECT $column FROM oauth2_authorization WHERE id = :id")
		.param("id", id)
		.query(String::class.java)
		.optional()
		.orElse(null)

	@Test
	fun `the token a client holds is not the string the column holds`() {
		val access = "access-${UUID.randomUUID()}"
		val refresh = "refresh-${UUID.randomUUID()}"
		val saved = grant(access, refresh)

		val storedAccess = assertNotNull(storedValue("access_token_value", saved.id))
		val storedRefresh = assertNotNull(storedValue("refresh_token_value", saved.id))

		assertFalse(storedAccess.contains(access), "a dump of this column was a live bearer token")
		assertFalse(storedRefresh.contains(refresh), "and a sixty-day one a bare client_id redeems")
		assertTrue(storedAccess.startsWith("sha256\$"), "marked, so the next reader can see it is keyed")
		assertTrue(storedRefresh.startsWith("sha256\$"))
	}

	@Test
	fun `a bearer still resolves to its grant`() {
		val access = "access-${UUID.randomUUID()}"
		val saved = grant(access, "refresh-${UUID.randomUUID()}")

		val found = authorizations.findByToken(access, OAuth2TokenType.ACCESS_TOKEN)

		assertEquals(saved.id, found?.id, "`McpBearerFilter` has to keep finding the row")
	}

	/**
	 * The asymmetry that makes the rest of it worth anything.
	 *
	 * `save` has to tolerate a value it has already keyed, because the library hands back
	 * authorizations it read and then re-saves them — so `stored` is idempotent. If lookups
	 * shared that shortcut, whoever read the column could present its contents as a bearer
	 * and the row would match, which is precisely the dump this change exists to devalue.
	 */
	@Test
	fun `presenting the stored digest is not presenting the token`() {
		val access = "access-${UUID.randomUUID()}"
		val saved = grant(access, "refresh-${UUID.randomUUID()}")
		val digest = assertNotNull(storedValue("access_token_value", saved.id))

		assertNull(
			authorizations.findByToken(digest, OAuth2TokenType.ACCESS_TOKEN),
			"the column's own contents must not be a usable credential",
		)
	}

	/**
	 * Saving a grant twice is what the code exchange and every revocation do — they read an
	 * authorization back and save it again. A `save` that keyed unconditionally would store a
	 * digest of a digest, and the bearer the client holds would stop resolving.
	 */
	@Test
	fun `re-saving a grant read back from the table does not key it twice`() {
		val access = "access-${UUID.randomUUID()}"
		val saved = grant(access, "refresh-${UUID.randomUUID()}")

		val readBack = assertNotNull(authorizations.findById(saved.id))
		authorizations.save(readBack)

		assertEquals(
			saved.id,
			authorizations.findByToken(access, OAuth2TokenType.ACCESS_TOKEN)?.id,
			"a second save must leave the row findable by the token the client was given",
		)
	}
}

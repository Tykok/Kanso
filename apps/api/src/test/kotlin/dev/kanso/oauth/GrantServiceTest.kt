package dev.kanso.oauth

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A member sees and revokes their own grants, and nobody else's.
 *
 * The listing is per-member by construction rather than by filter: the query starts from
 * `currentUser.requireId()`, so there is no parameter an agent could change to see
 * somebody else's connected applications.
 *
 * Everything a grant is made of is written here through the library's own services —
 * `RegisteredClientRepository`, `OAuth2AuthorizationConsentService`,
 * `OAuth2AuthorizationService` — and read back through [GrantService]'s hand-written SQL.
 * That asymmetry is the point. `GrantService` reads three tables Kanso copied but does
 * not own, including a column whose contents are the library's format and not ours
 * (`authorities`, which is `SCOPE_`-prefixed), so a test that also wrote them by hand
 * would assert nothing about the format that actually arrives at runtime.
 */
@Transactional
class GrantServiceTest : PostgresTest() {

	@Autowired lateinit var grants: GrantService
	@Autowired lateinit var registrations: ClientRegistrationService
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun member(name: String): User = users.createLocalUser(
		email = "grants-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	/** Stands in for the auth filter, which has no servlet request to run inside here. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	/**
	 * One whole grant, made the way the flow makes one: a client registers itself, the
	 * member consents on the screen, and a token comes out the other end.
	 *
	 * @param consentScopes what the consent row records, which is what the listing reads.
	 *   Separate from [scopes] so a row can carry a permission this version of Kanso has no
	 *   sentence for — an old grant, or a newer instance's — which is not a thing the
	 *   consent screen can produce today and is exactly why it has to be written by hand.
	 * @return the client id the member would see, and the access token the agent holds.
	 */
	private fun connect(
		member: User,
		name: String,
		scopes: List<String> = OAuthScopes.ALL,
		consentScopes: List<String> = scopes,
	): Grant {
		val registered = registrations.register(
			ClientRegistrationRequest(
				redirectUris = listOf("http://127.0.0.1:9999/callback"),
				clientName = name,
				scope = null,
			),
		)
		val client = assertNotNull(clients.findByClientId(registered.clientId))

		consents.save(
			OAuth2AuthorizationConsent.withId(client.id, member.id.toString())
				.apply { consentScopes.forEach { scope(it) } }
				.build(),
		)

		val token = "token-${UUID.randomUUID()}"
		val issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		authorizations.save(
			OAuth2Authorization.withRegisteredClient(client)
				.id(UUID.randomUUID().toString())
				// The user id, not the display name — `AgentPrincipalFilter` is what makes
				// that true in production, and it is the key every query here joins on.
				.principalName(member.id.toString())
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.authorizedScopes(scopes.toSet())
				.token(
					OAuth2AccessToken(
						OAuth2AccessToken.TokenType.BEARER,
						token,
						issuedAt,
						issuedAt.plus(1, ChronoUnit.HOURS),
						scopes.toSet(),
					),
				)
				.build(),
		)
		return Grant(registered.clientId, token)
	}

	private data class Grant(val clientId: String, val token: String)

	@Test
	fun `a member sees only their own grants`() {
		val mine = member("Elie")
		val theirs = member("Sam")
		val ours = connect(mine, "Claude Code")
		connect(theirs, "Somebody else's editor")

		actAs(mine)
		val listed = grants.list()

		assertEquals(1, listed.size, "another member's grant is not a fact about this member's account")
		assertEquals(
			ours.clientId,
			listed.single().clientId,
			"the public id is what a member matches against what `claude mcp add` told them",
		)
		assertEquals(
			"Claude Code",
			listed.single().clientName,
			"and the name is the registration's own, because there is nowhere else it could come from",
		)
		assertEquals(
			OAuthScopes.ALL.toSet(),
			listed.single().scopes.toSet(),
			"the consent row stores authorities in the library's own format; this is what says we read it",
		)
		assertTrue(
			listed.single().scopeProse.any { it.contains("Read the tickets") },
			"a member is shown what they agreed to, in the words they agreed to it in",
		)
	}

	@Test
	fun `revoking removes the consent and every token under it`() {
		val mine = member("Elie")
		val ours = connect(mine, "Claude Code")

		assertNotNull(
			authorizations.findByToken(ours.token, OAuth2TokenType.ACCESS_TOKEN),
			"the token has to work before revoking it can mean anything",
		)

		actAs(mine)
		grants.revoke(ours.clientId)

		assertTrue(grants.list().isEmpty(), "a revoked application is gone from the screen that revoked it")
		assertNull(
			authorizations.findByToken(ours.token, OAuth2TokenType.ACCESS_TOKEN),
			"the bearer filter resolves a token against oauth2_authorization, so deleting the row " +
				"is what makes the agent stop working on its very next request",
		)
	}

	@Test
	fun `revoking a grant that is not yours is a 404, not a 403`() {
		// Saying "forbidden" would confirm that somebody else has a grant with that
		// client — which is a fact about another member's setup.
		val mine = member("Elie")
		val theirs = member("Sam")
		val ours = connect(theirs, "Claude Code")

		actAs(mine)
		val refusal = assertFailsWith<NotFoundException> { grants.revoke(ours.clientId) }
		val unknown = assertFailsWith<NotFoundException> { grants.revoke(UUID.randomUUID().toString()) }

		assertEquals(
			unknown.message,
			refusal.message,
			"a client somebody else connected and a client nobody connected must be told apart by nobody",
		)
		assertNotNull(
			authorizations.findByToken(ours.token, OAuth2TokenType.ACCESS_TOKEN),
			"the refusal has to actually refuse — the other member's agent still works",
		)
	}

	@Test
	fun `a scope this version cannot say aloud is counted, not dropped and not rendered`() {
		val mine = member("Elie")
		// A consent that outlived a vocabulary change, which is the only way this row is
		// written: a client may only be granted what it is registered for, and registration
		// forces `OAuthScopes.ALL`. So it is written by hand, because production writes it
		// by upgrade — and the failure it would cause is a settings screen that answers 500,
		// at the exact moment somebody is trying to revoke something.
		connect(mine, "Claude Code", consentScopes = listOf(OAuthScopes.READ, "kanso:admin"))

		actAs(mine)
		val grant = grants.list().single()

		assertEquals(
			listOf(OAuthScopes.READ),
			grant.scopes,
			"the permissions this version does have words for are still shown",
		)
		assertEquals(
			1,
			grant.unrecognisedScopes,
			"a permission the row grants and this version cannot name must not vanish from a " +
				"screen whose whole job is saying what an application may do",
		)
		assertTrue(
			grant.scopeProse.none { it.contains("kanso:admin") },
			"the unknown string itself never leaves the server — `prose` refuses to put a " +
				"stranger's words in Kanso's voice, and a count cannot carry them either",
		)
	}
}

package dev.kanso.github

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a member's consent leaves behind, and what it buys.
 *
 * The table this exercises is the one `V36` created and deliberately left empty, saying so
 * in its own header: *"a `github_accounts` table with no rows is not a bug: it is the
 * reason the feed says KAN-142 moved to Done via #418 instead of naming a person."* So the
 * assertions come in pairs — what happens once somebody consents, and what still happens
 * for everybody who has not.
 */
@Transactional
class GithubAccountTest : PostgresTest() {

	@Autowired lateinit var accounts: GithubAccountRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var jdbc: JdbcClient

	private fun member(): User = users.createLocalUser(
		email = "gh-${UUID.randomUUID()}@kanso.test",
		displayName = "Consenting member",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	/** A distinct GitHub id per call, so two members in one test cannot collide by accident. */
	private fun githubId(): Long = System.nanoTime() and 0x7FFF_FFFFL

	// --- both of GitHub's token shapes ---------------------------------------

	/**
	 * The non-expiring shape, which is what an App that never opted into expiring tokens
	 * issues. `V36` made both columns nullable on the argument that this shape is real and
	 * "no refresh token" has to be storable rather than assumed away.
	 *
	 * The assertion that matters is the last one: nothing invented an expiry. A row that
	 * came back with a fabricated `expires_at` would read as [GithubTokenState.ACTIVE] and
	 * then, hours later, as expired — for a token that never expires.
	 */
	@Test
	fun `a token with no refresh token and no expiry is stored as exactly that`() {
		val who = member()
		val stored = accounts.link(
			who.id,
			githubId(),
			"tykok",
			GithubToken(accessToken = "ghu_permanent", refreshToken = null, expiresAt = null),
		)

		assertNull(stored.expiresAt, "an expiry nobody sent must not be invented")
		assertFalse(stored.refreshable)
		assertEquals(
			GithubTokenState.PERMANENT,
			stored.tokenState(OffsetDateTime.now()),
			"no expiry is a permanent token, not an expired one",
		)

		val columns = jdbc.sql(
			"""
			SELECT expires_at, refresh_token_enc, (access_token_enc IS NOT NULL) AS has_access
			FROM github_accounts WHERE user_id = :id
			""".trimIndent()
		)
			.param("id", who.id)
			.query { rs, _ ->
				Triple(rs.getObject("expires_at"), rs.getBytes("refresh_token_enc"), rs.getBoolean("has_access"))
			}
			.single()

		assertNull(columns.first, "expires_at is NULL in the column, not a default")
		assertNull(columns.second, "refresh_token_enc is NULL in the column")
		assertTrue(columns.third, "access_token_enc is NOT NULL — a row without one means nothing")
	}

	/**
	 * The expiring shape, and the three words that describe its life.
	 *
	 * `tokenState` takes `now` as an argument precisely so this is a table rather than three
	 * tests that have to wait eight hours.
	 */
	@Test
	fun `a token with an expiry is active, then refreshable, and never permanent`() {
		val who = member()
		val expiry = OffsetDateTime.now().plusHours(8)
		val stored = accounts.link(
			who.id,
			githubId(),
			"tykok",
			GithubToken(accessToken = "ghu_expiring", refreshToken = "ghr_renew", expiresAt = expiry),
		)

		assertTrue(stored.refreshable)
		assertNotEquals(null, stored.expiresAt)
		assertEquals(GithubTokenState.ACTIVE, stored.tokenState(OffsetDateTime.now()))
		assertEquals(
			GithubTokenState.REFRESHABLE,
			stored.tokenState(expiry.plusMinutes(1)),
			"a passed expiry with a refresh token is renewable without asking the member again",
		)
	}

	/**
	 * The shape GitHub does not document and the schema still admits, asserted rather than
	 * assumed away: an expiry with nothing to renew it.
	 *
	 * It is [GithubTokenState.EXPIRED] and **not** an error, because the row is still the
	 * consent — which is the next test.
	 */
	@Test
	fun `an expiry with no refresh token is expired, not a refusal`() {
		val who = member()
		val expiry = OffsetDateTime.now().minusMinutes(5)
		val stored = accounts.link(
			who.id,
			githubId(),
			"tykok",
			GithubToken(accessToken = "ghu_orphan", refreshToken = null, expiresAt = expiry),
		)

		assertFalse(stored.refreshable)
		assertEquals(GithubTokenState.EXPIRED, stored.tokenState(OffsetDateTime.now()))
	}

	/**
	 * **The name does not depend on the token.**
	 *
	 * This is the guard on the mistake the whole design warns against: gating attribution
	 * on a clock would make a member's own history flicker back to *via #418* eight hours
	 * after they linked, for a reason that has nothing to do with them. A row exists because
	 * somebody completed a consent flow; consent is not undone by an expiry.
	 */
	@Test
	fun `an expired token still names its member`() {
		val who = member()
		accounts.link(
			who.id,
			githubId(),
			"tykok",
			GithubToken("ghu_dead", refreshToken = null, expiresAt = OffsetDateTime.now().minusDays(30)),
		)

		assertEquals(GithubTokenState.EXPIRED, accounts.find(who.id)!!.tokenState(OffsetDateTime.now()))
		assertEquals(
			who.id,
			accounts.memberFor(githubUserId = null, login = "tykok"),
			"a stale token is not a withdrawn consent",
		)
	}

	// --- the token is a secret at rest --------------------------------------

	/**
	 * `SecretBox` on both tokens, asserted against the column rather than against the API —
	 * the shape `WebhookServiceTest` uses for `secret_cipher`, and the only version of this
	 * assertion that could fail if the encrypting were dropped.
	 */
	@Test
	fun `neither token is readable in the column`() {
		val who = member()
		accounts.link(
			who.id,
			githubId(),
			"tykok",
			GithubToken("ghu_plaintext_marker", "ghr_plaintext_marker", OffsetDateTime.now().plusHours(8)),
		)

		val bytes = jdbc.sql(
			"SELECT access_token_enc, refresh_token_enc FROM github_accounts WHERE user_id = :id"
		)
			.param("id", who.id)
			.query { rs, _ -> rs.getBytes("access_token_enc") to rs.getBytes("refresh_token_enc") }
			.single()

		assertFalse(
			String(bytes.first, Charsets.ISO_8859_1).contains("ghu_plaintext_marker"),
			"the access token is in the clear in access_token_enc",
		)
		assertFalse(
			String(bytes.second!!, Charsets.ISO_8859_1).contains("ghr_plaintext_marker"),
			"the refresh token is in the clear in refresh_token_enc",
		)

		val round = accounts.tokenFor(who.id)!!
		assertEquals("ghu_plaintext_marker", round.accessToken, "and it still round-trips")
		assertEquals("ghr_plaintext_marker", round.refreshToken)
	}

	// --- the resolution, which is the point ---------------------------------

	/**
	 * **The assertion this ticket exists for, in both directions.**
	 *
	 * A member who consented resolves to their own id, whatever a payload does to the
	 * capitalisation of their login — GitHub logins are case-insensitive and
	 * `github_accounts_login_idx` is lowercased for exactly this. A login nobody linked
	 * resolves to `null`, which becomes `activity.actor_id = NULL` and the feed's existing
	 * sentence with no person in it.
	 *
	 * `null` and not a placeholder: there is no synthetic member, and the raw login is never
	 * returned as a stand-in for a name. "Unknown" in a feed is a claim about a person.
	 */
	@Test
	fun `a consented login resolves in any capitalisation and an unlinked one resolves to nothing`() {
		val who = member()
		accounts.link(who.id, githubId(), "Tykok", GithubToken("ghu_x", null, null))

		for (spelling in listOf("Tykok", "tykok", "TYKOK", "tYkOk")) {
			assertEquals(
				who.id,
				accounts.memberFor(githubUserId = null, login = spelling),
				"'$spelling' is the same GitHub account as 'Tykok'",
			)
		}

		assertNull(
			accounts.memberFor(githubUserId = null, login = "somebody-else"),
			"a login nobody linked is nobody, which is what leaves the feed saying 'via #418'",
		)
		assertNull(accounts.memberFor(githubUserId = null, login = ""))
		assertNull(accounts.memberFor(githubUserId = null, login = null))
		assertNull(accounts.memberFor(githubUserId = 999_999_999L, login = null))
	}

	/**
	 * The id wins outright, which is what makes a rename survivable.
	 *
	 * Somebody links as `Tykok` and later renames themselves on GitHub. A payload carrying
	 * their numeric id still reaches them. And the subtle half: a payload carrying their id
	 * **and** a login that now belongs to somebody else must follow the id — a login lookup
	 * as a "fallback" there is not a fallback, it is a wrong answer waiting for a recycled
	 * name.
	 */
	@Test
	fun `the numeric id wins over a login that has moved to somebody else`() {
		val renamed = member()
		val squatter = member()
		val renamedId = githubId()
		accounts.link(renamed.id, renamedId, "old-name", GithubToken("ghu_a", null, null))
		accounts.link(squatter.id, githubId(), "old-name-taken-later", GithubToken("ghu_b", null, null))

		assertEquals(
			renamed.id,
			accounts.memberFor(githubUserId = renamedId, login = "old-name-taken-later"),
			"the id is the identity that survives a rename; the login on the payload is not",
		)
		assertEquals(
			squatter.id,
			accounts.memberFor(githubUserId = null, login = "old-name-taken-later"),
			"and with no id on the payload, the login is all there is",
		)

		// The trap, and the reason the id lookup must not "fall back" to the login when it
		// finds nothing: a payload from an account **nobody linked**, whose login collides
		// with a member's. Falling through would name that member for somebody else's
		// pull request — the one failure mode worse than naming nobody.
		assertNull(
			accounts.memberFor(githubUserId = 424_242_424L, login = "old-name-taken-later"),
			"an id nobody linked is nobody, even when the login beside it belongs to a member",
		)
	}

	// --- one GitHub identity, one member ------------------------------------

	/**
	 * Re-consenting lands on the row the member already has.
	 *
	 * Walking the consent screen twice is ordinary — it is what a member does when their
	 * token expired with nothing to refresh it — so it has to be an update and not a second
	 * row, and `user_id` being the primary key is what makes that true.
	 */
	@Test
	fun `re-linking replaces the grant rather than adding one`() {
		val who = member()
		val id = githubId()
		accounts.link(who.id, id, "tykok", GithubToken("ghu_first", null, null))
		accounts.link(who.id, id, "tykok-renamed", GithubToken("ghu_second", "ghr_second", OffsetDateTime.now().plusHours(8)))

		val rows = jdbc.sql("SELECT count(*) FROM github_accounts WHERE user_id = :id")
			.param("id", who.id)
			.query(Long::class.java)
			.single()
		assertEquals(1L, rows, "one member, one grant")

		val stored = accounts.find(who.id)!!
		assertEquals("tykok-renamed", stored.githubLogin, "GitHub's current spelling wins")
		assertTrue(stored.refreshable, "and the newer token's shape replaces the older one's")
		assertEquals("ghu_second", accounts.tokenFor(who.id)!!.accessToken)
	}

	/**
	 * Two members cannot stand behind one GitHub identity, and the database is the guard.
	 *
	 * `github_user_id` is `UNIQUE` in `V36` precisely so an inbound payload resolves to
	 * exactly one member — two would make the feed name the wrong person, which is worse
	 * than naming nobody. [GithubAccountRepository.ownerOf] is what lets the controller say
	 * so in a sentence; this asserts that the constraint underneath is real, so the sentence
	 * is a courtesy rather than the protection.
	 */
	@Test
	fun `a second member cannot claim a GitHub identity that is already linked`() {
		val first = member()
		val second = member()
		val shared = githubId()
		accounts.link(first.id, shared, "tykok", GithubToken("ghu_first", null, null))

		assertEquals(first.id, accounts.ownerOf(shared), "the sentence the controller refuses with")

		assertFailsWith<DataIntegrityViolationException>("the UNIQUE has to be the actual guard") {
			accounts.link(second.id, shared, "tykok", GithubToken("ghu_second", null, null))
			// Forces the statement out before the assertion is judged: without this the
			// violation could surface at commit, outside the block.
			jdbc.sql("SELECT count(*) FROM github_accounts").query(Long::class.java).single()
		}
	}

	// --- withdrawing it ------------------------------------------------------

	/**
	 * Unlinking removes the row, and with it every future attribution.
	 *
	 * A `DELETE` and not a flag, because the row *is* the consent: a `revoked_at` column
	 * would leave an encrypted token in the database after the person who owned it asked for
	 * it to be gone. Twice is not an error.
	 */
	@Test
	fun `unlinking forgets the grant and the name goes back to nobody`() {
		val who = member()
		accounts.link(who.id, githubId(), "tykok", GithubToken("ghu_x", null, null))

		assertTrue(accounts.unlink(who.id))
		assertNull(accounts.find(who.id))
		assertNull(accounts.tokenFor(who.id))
		assertNull(
			accounts.memberFor(githubUserId = null, login = "tykok"),
			"withdrawn consent puts the feed's fallback back",
		)
		assertFalse(accounts.unlink(who.id), "unlinking twice is not an error")
	}
}

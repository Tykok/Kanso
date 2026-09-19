package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.settings.PreferencesService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Not `@Transactional`: single use is enforced by a conditional UPDATE, and a test
 * that never commits could not tell it apart from a check that happens to run
 * twice in the same snapshot.
 */
class InvitationTest : PostgresTest() {

	@Autowired lateinit var invitations: InvitationService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var preferences: PreferencesService

	@BeforeTest
	fun clearLocalAccounts() {
		jdbc.sql("DELETE FROM login_attempts").update()
		jdbc.sql("DELETE FROM invitations").update()
		jdbc.sql("DELETE FROM users WHERE password_hash IS NOT NULL OR instance_role <> 'member'").update()
	}

	@Test
	fun `an invitation is accepted once and refused ever after`() {
		val (token, expiresAt) = invitations.create(owner().id, null, InstanceRole.MEMBER)
		assertTrue(expiresAt.isAfter(OffsetDateTime.now()), "a link that is already dead is not a link")

		val invited = invitations.accept(token, "Invited@Kanso.test", "Invited", "a-long-enough-password")
		assertEquals("invited@kanso.test", invited.email, "addresses are compared lowercased everywhere else too")
		assertEquals(InstanceRole.MEMBER, invited.instanceRole)
		assertTrue(invited.hasPassword)

		assertFailsWith<BadRequestException> {
			invitations.accept(token, "second@kanso.test", "Second", "a-long-enough-password")
		}
		assertNull(
			tx.execute { users.findByEmail("second@kanso.test") },
			"the refused account must not survive the rejected acceptance",
		)
	}

	@Test
	fun `accepting an invitation stamps the onboarding`() {
		val (token, _) = invitations.create(owner().id, null, InstanceRole.MEMBER)

		val invited = invitations.accept(token, "joined@kanso.test", "Joined", "a-long-enough-password")

		assertNotNull(preferences.get(invited.id).onboardedAt)
	}

	@Test
	fun `an expired invitation is refused`() {
		val (token, _) = invitations.create(owner().id, null, InstanceRole.MEMBER)
		// The only pending invitation in this test's database; backdating it is the
		// only way to reach an expiry seven days out.
		jdbc.sql("UPDATE invitations SET expires_at = now() - interval '1 day'").update()

		assertFailsWith<BadRequestException> {
			invitations.accept(token, "late@kanso.test", "Late", "a-long-enough-password")
		}
	}

	@Test
	fun `an invitation bound to an address refuses any other`() {
		val (token, _) = invitations.create(owner().id, "expected@kanso.test", InstanceRole.ADMIN)

		assertFailsWith<BadRequestException> {
			invitations.accept(token, "someone.else@kanso.test", "Else", "a-long-enough-password")
		}

		val invited = invitations.accept(token, "expected@kanso.test", "Expected", "a-long-enough-password")
		assertEquals(InstanceRole.ADMIN, invited.instanceRole, "the role travels with the invitation, not the form")
	}

	@Test
	fun `a short password is refused before an account exists`() {
		val (token, _) = invitations.create(owner().id, null, InstanceRole.MEMBER)

		assertFailsWith<BadRequestException> {
			invitations.accept(token, "shorty@kanso.test", "Shorty", "too-short")
		}
		assertNull(tx.execute { users.findByEmail("shorty@kanso.test") })

		// And the link is still good, because nothing consumed it.
		invitations.accept(token, "shorty@kanso.test", "Shorty", "a-long-enough-password")
	}

	private fun owner(): User = requireNotNull(
		tx.execute {
			users.createLocalUser(
				email = "inviter@kanso.test",
				displayName = "Inviter",
				passwordHash = encoder.hash("a-long-enough-password"),
				role = InstanceRole.OWNER,
			)
		}
	)
}

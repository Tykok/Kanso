package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class AccountTest : PostgresTest() {

	@Autowired lateinit var account: AccountService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun localUser(role: InstanceRole = InstanceRole.MEMBER, password: String = "correct-horse-battery") =
		users.createLocalUser(
			email = "user-${UUID.randomUUID()}@kanso.test",
			displayName = "Test",
			passwordHash = encoder.hash(password),
			role = role,
		)

	@Test
	fun `changing a password requires the current one`() {
		val user = localUser()

		val failure = assertFailsWith<dev.kanso.service.BadRequestException> {
			account.changePassword(user.id, "not-the-password", "a-brand-new-password")
		}
		assertTrue(failure.message!!.contains("current password"), failure.message!!)

		account.changePassword(user.id, "correct-horse-battery", "a-brand-new-password")
		assertTrue(
			encoder.matches("a-brand-new-password", users.passwordHashOf(user.id)!!),
			"the new password should be the one stored",
		)
	}

	@Test
	fun `a new password still has to satisfy the policy`() {
		val user = localUser()
		assertFailsWith<dev.kanso.service.BadRequestException> {
			account.changePassword(user.id, "correct-horse-battery", "short")
		}
	}

	@Test
	fun `reusing the current password as the new one is refused`() {
		val user = localUser()
		val failure = assertFailsWith<dev.kanso.service.BadRequestException> {
			account.changePassword(user.id, "correct-horse-battery", "correct-horse-battery")
		}
		assertTrue(failure.message!!.contains("same as the current"), failure.message!!)
	}

	@Test
	fun `unlinking the only way to sign in is refused`() {
		// A provider account with no password: unlinking would strand it.
		val user = users.provisionFromOidc(
			provider = "google",
			subject = UUID.randomUUID().toString(),
			email = "oidc-${UUID.randomUUID()}@kanso.test",
			displayName = "Provider only",
			avatarUrl = null,
		)

		val failure = assertFailsWith<dev.kanso.service.ConflictException> {
			account.unlinkProvider(user.id, "google")
		}
		assertTrue(failure.message!!.contains("no way to sign in"), failure.message!!)
	}

	@Test
	fun `unlinking is allowed once a password exists`() {
		val user = users.provisionFromOidc(
			provider = "google",
			subject = UUID.randomUUID().toString(),
			email = "both-${UUID.randomUUID()}@kanso.test",
			displayName = "Both",
			avatarUrl = null,
		)
		users.setPassword(user.id, encoder.hash("correct-horse-battery"))

		val unlinked = account.unlinkProvider(user.id, "google")
		assertNull(unlinked.oidcProvider, "the provider should be detached")
		assertTrue(unlinked.hasPassword, "the password is what is left")
	}

	@Test
	fun `a member cannot change roles`() {
		val member = localUser(InstanceRole.MEMBER)
		val target = localUser(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> {
			account.setInstanceRole(member, target.id, InstanceRole.ADMIN)
		}
	}

	@Test
	fun `an admin promotes and demotes, but never touches the owner`() {
		val admin = localUser(InstanceRole.ADMIN)
		val target = localUser(InstanceRole.MEMBER)

		assertEquals(InstanceRole.ADMIN, account.setInstanceRole(admin, target.id, InstanceRole.ADMIN).instanceRole)
		assertEquals(InstanceRole.MEMBER, account.setInstanceRole(admin, target.id, InstanceRole.MEMBER).instanceRole)

		val owner = users.findAll().firstOrNull { it.instanceRole == InstanceRole.OWNER }
			?: localUser(InstanceRole.OWNER)
		assertFailsWith<dev.kanso.service.ConflictException> {
			account.setInstanceRole(admin, owner.id, InstanceRole.MEMBER)
		}
	}

	@Test
	fun `ownership is not assignable from the role list`() {
		val admin = localUser(InstanceRole.ADMIN)
		val target = localUser(InstanceRole.MEMBER)

		assertFailsWith<dev.kanso.service.BadRequestException> {
			account.setInstanceRole(admin, target.id, InstanceRole.OWNER)
		}
	}

	@Test
	fun `renaming trims and refuses an empty name`() {
		val user = localUser()
		assertEquals("Renamed", account.rename(user.id, "  Renamed  ").displayName)
		assertFailsWith<dev.kanso.service.BadRequestException> { account.rename(user.id, "   ") }
	}
}

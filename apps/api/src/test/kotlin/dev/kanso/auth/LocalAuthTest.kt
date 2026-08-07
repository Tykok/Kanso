package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deliberately not `@Transactional`, unlike most tests here: the owner claim is
 * decided by a unique index across two committed transactions, and the rate
 * limiter counts committed rows on purpose. A test transaction that rolled
 * everything back at the end would exercise neither mechanism.
 */
class LocalAuthTest : PostgresTest() {

	@Autowired lateinit var localAuth: LocalAuthService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var jdbc: JdbcClient

	/** Only the rows local auth owns, so a shared container stays usable for the rest. */
	@BeforeTest
	fun clearLocalAccounts() {
		jdbc.sql("DELETE FROM login_attempts").update()
		jdbc.sql("DELETE FROM invitations").update()
		jdbc.sql("DELETE FROM users WHERE password_hash IS NOT NULL OR instance_role <> 'member'").update()
	}

	@Test
	fun `two simultaneous claims leave exactly one owner`() {
		val pool = Executors.newFixedThreadPool(2)
		val ready = CountDownLatch(1)
		try {
			val racing = (1..2).map { n ->
				pool.submit(
					Callable {
						ready.await()
						runCatching {
							localAuth.claimOwner("owner-$n@kanso.test", "Owner $n", "a-long-enough-password")
						}
					}
				)
			}
			ready.countDown()
			val outcomes = racing.map { it.get(30, TimeUnit.SECONDS) }

			assertEquals(1, outcomes.count { it.isSuccess }, "the instance owner can only be claimed once")
			assertIs<ConflictException>(
				outcomes.single { it.isFailure }.exceptionOrNull(),
				"the loser of the race should be told the owner is taken, not shown a constraint violation",
			)
			assertEquals(
				1,
				tx.execute { users.findAll().count { it.instanceRole == InstanceRole.OWNER } },
				"the partial unique index is what makes this true, not the pre-check",
			)
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `the sixth wrong password in a row is refused before it is checked`() {
		val email = "rate-limited@kanso.test"
		createLocalUser(email, "the-real-password")

		repeat(LocalAuthService.MAX_FAILURES) { attempt ->
			assertFailsWith<BadRequestException>("attempt ${attempt + 1} should still be answered") {
				localAuth.authenticate(email, "not-the-real-password", "10.0.0.1")
			}
		}

		val refused = assertFailsWith<TooManyLoginAttemptsException> {
			localAuth.authenticate(email, "not-the-real-password", "10.0.0.1")
		}
		assertTrue(refused.retryAfter.toMinutes() >= 0, "the caller needs to know how long to wait")

		assertFailsWith<TooManyLoginAttemptsException>("even the right password waits out the window") {
			localAuth.authenticate(email, "the-real-password", "10.0.0.1")
		}
	}

	@Test
	fun `the limit counts the address, not only the machine it came from`() {
		val email = "guessed-everywhere@kanso.test"
		createLocalUser(email, "the-real-password")

		repeat(LocalAuthService.MAX_FAILURES) { n ->
			assertFailsWith<BadRequestException> {
				localAuth.authenticate(email, "wrong-password-$n", "10.0.0.$n")
			}
		}

		assertFailsWith<TooManyLoginAttemptsException> {
			localAuth.authenticate(email, "wrong-again", "10.0.0.200")
		}
	}

	@Test
	fun `a correct password signs in and is recorded`() {
		val email = "signs-in@kanso.test"
		createLocalUser(email, "the-real-password")

		val user = localAuth.authenticate(email, "the-real-password", "10.0.0.1")
		assertEquals(email, user.email)
		assertTrue(user.hasPassword)

		val reloaded = tx.execute { users.findByEmail(email) }
		assertTrue(reloaded?.lastLoginAt != null, "last_login_at is how an idle account becomes visible")
		assertTrue(tx.execute { localAuth.passwordLoginEnabled() } == true)
	}

	@Test
	fun `a password shorter than twelve characters is refused by name`() {
		val error = assertFailsWith<BadRequestException> {
			localAuth.claimOwner("shorty@kanso.test", "Shorty", "short-pass")
		}
		assertTrue(
			error.message?.contains(PasswordPolicy.MIN_LENGTH.toString()) == true,
			"the rule has to be named so it can be met: ${error.message}",
		)
		assertNull(tx.execute { users.findByEmail("shorty@kanso.test") }, "nothing should have been created")
	}

	private fun createLocalUser(email: String, password: String): User =
		requireNotNull(
			tx.execute {
				users.createLocalUser(email, email.substringBefore('@'), encoder.hash(password), InstanceRole.MEMBER)
			}
		)
}

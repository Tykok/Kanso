package dev.kanso.auth

import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Too many failed sign-ins for this address or from this machine. [retryAfter] is
 * when the oldest counted failure leaves the window, which is the earliest moment
 * the next attempt can succeed — the caller turns it into a `Retry-After`.
 */
class TooManyLoginAttemptsException(val retryAfter: Duration) : RuntimeException(
	"Too many failed sign-in attempts. Try again in ${retryAfter.toMinutes() + 1} minute(s)."
)

/**
 * Email and password, for an instance that has no identity provider to lean on.
 *
 * BCrypt rather than a raw digest: it is deliberately slow and salts each hash, so
 * a stolen `users` table cannot be turned into a rainbow-table lookup.
 */
@Service
@Transactional
class LocalAuthService(
	private val users: UserRepository,
	private val attempts: LoginAttemptLog,
	private val encoder: PasswordEncoder,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Verifying a hash takes about a tenth of a second, so skipping it when the
	 * address is unknown would answer "no such account" measurably faster than
	 * "wrong password". This hash exists to be compared against and thrown away.
	 */
	private val decoyHash: String by lazy { encoder.hash("kanso-constant-time-decoy") }

	/**
	 * Claims the instance for its first account.
	 *
	 * The check below is a courtesy, not the guard: two setup requests arriving
	 * together would both pass it. The partial unique index on `instance_role` is
	 * what actually decides, and the loser is translated here rather than escaping
	 * as a constraint violation.
	 */
	fun claimOwner(email: String, displayName: String, password: String): User {
		val address = normalise(email)
		PasswordPolicy.validate(password)

		if (users.ownerExists()) throw ConflictException(OWNER_TAKEN)
		if (users.findByEmail(address) != null) throw ConflictException("An account already exists for $address")

		return try {
			users.createLocalUser(
				email = address,
				displayName = displayName.ifBlank { address.substringBefore('@') },
				passwordHash = encoder.hash(password),
				role = InstanceRole.OWNER,
			).also { log.info("Instance owner claimed by {} (kanso id {})", it.email, it.id) }
		} catch (e: Exception) {
			// Exposed wraps the driver's SQLException, which Kotlin does not treat as
			// unchecked but is not a RuntimeException either — hence the wide catch.
			if (!mentions(e, OWNER_INDEX)) throw e
			throw ConflictException(OWNER_TAKEN)
		}
	}

	/**
	 * The attempt is recorded whether it worked or not — the successes are what
	 * make an unusual run of failures readable afterwards — and always before the
	 * refusal is thrown, since [LoginAttemptLog] commits on its own.
	 */
	fun authenticate(email: String, password: String, ip: String?): User {
		val address = normalise(email)
		refuseIfRateLimited(address, ip)

		val stored = users.findPasswordHash(address)
		val matched = encoder.matches(password, stored ?: decoyHash)
		val accepted = matched && stored != null
		attempts.record(address, ip, accepted)

		// One message for a wrong password and for an address that has no account:
		// the difference is only useful to someone finding out who has one.
		if (!accepted) throw BadRequestException(REJECTED)

		val user = users.findByEmail(address) ?: throw BadRequestException(REJECTED)
		if (!user.active) throw BadRequestException("This account has been deactivated")

		users.markLoggedIn(user.id)
		return user
	}

	/**
	 * Whether the sign-in screen should offer a password form. Public because the
	 * setup endpoints answer the same question from outside this package.
	 */
	@Transactional(readOnly = true)
	fun passwordLoginEnabled(): Boolean = users.anyPasswordSet()

	private fun refuseIfRateLimited(address: String, ip: String?) {
		val window = attempts.failuresSince(address, ip, OffsetDateTime.now().minus(WINDOW), MAX_FAILURES)
		if (window.failures < MAX_FAILURES) return

		val reopensAt = window.oldestFailure?.plus(WINDOW) ?: OffsetDateTime.now().plus(WINDOW)
		val wait = Duration.between(OffsetDateTime.now(), reopensAt)
		log.warn("Rate-limited sign-in for {} from {} after {} failures", address, ip ?: "unknown", window.failures)
		throw TooManyLoginAttemptsException(if (wait.isNegative) Duration.ZERO else wait)
	}

	private fun normalise(email: String): String = email.trim().lowercase()
		.ifBlank { throw BadRequestException("An email address is required") }

	/**
	 * Exposed reports a constraint violation as its own exception and Spring's
	 * `JdbcClient` as a `DataIntegrityViolationException`; both carry the Postgres
	 * message somewhere in the chain, and the index named in it is the only way to
	 * tell "someone else just became owner" apart from any other unique key.
	 */
	private fun mentions(error: Throwable, constraint: String): Boolean =
		generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)
			.any { it.message?.contains(constraint) == true }

	companion object {
		val WINDOW: Duration = Duration.ofMinutes(15)
		const val MAX_FAILURES = 5

		private const val OWNER_INDEX = "users_single_owner"
		private const val OWNER_TAKEN = "This instance already has an owner"
		private const val REJECTED = "Incorrect email address or password"
		private const val MAX_CAUSE_DEPTH = 10
	}
}

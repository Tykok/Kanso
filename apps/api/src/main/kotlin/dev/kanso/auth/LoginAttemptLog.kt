package dev.kanso.auth

import dev.kanso.db.LoginAttempts
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** What one subject — an email address or an IP — has spent of the current window. */
data class AttemptWindow(val failures: Int, val oldestFailure: OffsetDateTime?)

/**
 * The rate limiter's memory.
 *
 * Two decisions are load-bearing here. Attempts live in Postgres, so the limit
 * survives a restart and holds across instances — an attacker should not be able
 * to reset it by waiting for a deploy. And each write commits in its own
 * transaction: a refused sign-in ends in an exception, and a row recorded in the
 * caller's transaction would roll back with it, leaving the counter at zero
 * forever.
 */
@Component
class LoginAttemptLog {

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun record(email: String, ip: String?, succeeded: Boolean) {
		LoginAttempts.insert {
			it[LoginAttempts.email] = email.lowercase()
			it[LoginAttempts.ip] = ip
			it[at] = OffsetDateTime.now()
			it[LoginAttempts.succeeded] = succeeded
		}
	}

	/**
	 * The worse of the two counts: an address being guessed from many machines and
	 * a machine guessing many addresses are both worth stopping, and neither
	 * subsumes the other.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun failuresSince(email: String, ip: String?, since: OffsetDateTime, cap: Int): AttemptWindow {
		val byEmail = window(LoginAttempts.email.lowerCase() eq email.lowercase(), since, cap)
		val byIp = ip?.let { window(LoginAttempts.ip eq it, since, cap) }
		return if (byIp != null && byIp.failures > byEmail.failures) byIp else byEmail
	}

	/**
	 * Reads one row past [cap] and stops. How far beyond the threshold someone is
	 * changes nothing, and an unbounded scan would let a flood make its own
	 * rejection expensive. Ascending order keeps the oldest failure — the one whose
	 * departure from the window reopens the door — at the head.
	 */
	private fun window(subject: Op<Boolean>, since: OffsetDateTime, cap: Int): AttemptWindow {
		val times = LoginAttempts
			.select(LoginAttempts.at)
			.where { subject and (LoginAttempts.succeeded eq false) and (LoginAttempts.at greaterEq since) }
			.orderBy(LoginAttempts.at to SortOrder.ASC)
			.limit(cap + 1)
			.map { it[LoginAttempts.at] }
		return AttemptWindow(times.size, times.firstOrNull())
	}
}

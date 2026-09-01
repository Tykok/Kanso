package dev.kanso.oauth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The second guard on an unauthenticated endpoint that writes rows.
 *
 * [RedirectUriPolicy] decides that a stranger's client is harmless; this decides that a
 * loop cannot fill the table before the cap notices. In memory rather than in Postgres,
 * unlike sign-in throttling: a failed sign-in protects an account and is worth persisting
 * across a restart, whereas the worst a lost registration counter costs is a few extra
 * rows under a cap that refuses anyway.
 */
class RegistrationRateLimitTest {

	private var now = Instant.parse("2026-09-01T10:00:00Z")

	private fun limit(perHour: Int = 3) = RegistrationRateLimit(perHour) { now }

	@Test
	fun `an address may register up to its allowance`() {
		val limit = limit()
		repeat(3) { assertTrue(limit.allow("203.0.113.7"), "attempt ${it + 1} is within the allowance") }
	}

	@Test
	fun `the next one is refused`() {
		val limit = limit()
		repeat(3) { limit.allow("203.0.113.7") }
		assertFalse(limit.allow("203.0.113.7"))
	}

	@Test
	fun `one address does not spend another's allowance`() {
		val limit = limit()
		repeat(3) { limit.allow("203.0.113.7") }
		assertTrue(limit.allow("203.0.113.8"), "a shared limit would let one client deny every other")
	}

	@Test
	fun `the window slides`() {
		val limit = limit()
		repeat(3) { limit.allow("203.0.113.7") }
		now = now.plus(Duration.ofMinutes(61))
		assertTrue(limit.allow("203.0.113.7"), "an hour later is a new hour")
	}

	@Test
	fun `an unknown address is still limited, and all of them share one bucket`() {
		// Behind a proxy that strips the address, everything arrives as null. Treating
		// that as unlimited would make the limit optional for anyone who can arrange it.
		val limit = limit()
		repeat(3) { limit.allow(null) }
		assertFalse(limit.allow(null))
	}
}

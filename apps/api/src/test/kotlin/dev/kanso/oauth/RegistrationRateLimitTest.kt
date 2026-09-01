package dev.kanso.oauth

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

	/**
	 * The key is `remoteAddr`, which the caller chooses: one IPv6 /64 holds more addresses
	 * than this process holds memory, and nothing about reaching `/connect/register`
	 * requires a credential. A limiter that leaks a key per caller is a slow leak on the
	 * one endpoint an attacker can call at will.
	 */
	@Test
	fun `the addresses it remembers have a ceiling, so a flood cannot grow the map without end`() {
		val limit = RegistrationRateLimit(perHour = 3, addresses = 4) { now }

		repeat(50) { limit.allow("2001:db8::$it") }

		assertTrue(
			limit.trackedAddresses <= 4,
			"pruning timestamps without ever dropping a key prunes nothing an attacker cares about",
		)
	}

	@Test
	fun `at the ceiling a new address is refused, and welcome again once its window has passed`() {
		val limit = RegistrationRateLimit(perHour = 3, addresses = 2) { now }
		assertTrue(limit.allow("203.0.113.1"))
		assertTrue(limit.allow("203.0.113.2"))

		assertFalse(
			limit.allow("203.0.113.3"),
			"a registration endpoint that is unavailable is a bounded failure; a heap that only grows is not",
		)

		now = now.plus(Duration.ofMinutes(61))
		assertTrue(limit.allow("203.0.113.3"), "and the sweep frees what the window has already made worthless")
	}

	/**
	 * Counting outside the lock and appending inside it is not one decision, and two
	 * registrations arriving together from one address both read the same room and both
	 * took it. The same gap mutated an `ArrayList` while a concurrent `removeAll` held the
	 * bin lock, which is a list that can end up structurally broken rather than merely
	 * over-long.
	 *
	 * Many small rounds rather than one big one: the window is a few instructions wide, so
	 * what makes it visible is how often threads meet at the barrier, not how many there
	 * are. It cannot fail against a limiter that decides once, under the lock.
	 */
	@Test
	fun `two callers from one address cannot both take the last registration`() {
		val callers = 8
		val pool = Executors.newFixedThreadPool(callers)
		try {
			repeat(200) {
				val limit = RegistrationRateLimit(perHour = 1) { now }
				val together = CyclicBarrier(callers)
				val allowed = AtomicInteger()
				(1..callers)
					.map { pool.submit { together.await(); if (limit.allow("203.0.113.7")) allowed.incrementAndGet() } }
					.forEach { it.get() }
				assertEquals(1, allowed.get(), "an allowance read outside the lock is one two callers can both spend")
			}
		} finally {
			pool.shutdownNow()
		}
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

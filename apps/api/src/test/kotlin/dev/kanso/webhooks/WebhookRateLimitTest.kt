package dev.kanso.webhooks

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The limiter the ticket insists on: *"sans rate limit par abonnement, un webhook en boucle
 * noie la base."*
 *
 * The clock is injected, which is `RegistrationRateLimitTest`'s idiom and the only way to
 * test a sliding window without waiting a minute. `ApiTokenFilterTest` drives its limit by
 * lowering `perMinute` instead, because a filter test has no clock seam — this one does.
 */
class WebhookRateLimitTest {

	private var now: Instant = Instant.parse("2026-09-03T10:00:00Z")

	private fun limit(perMinute: Int = 3) = WebhookRateLimit(perMinute) { now }

	@Test
	fun `the delivery past the limit is refused`() {
		val limiter = limit(perMinute = 3)
		val subscription = UUID.randomUUID()

		for (i in 1..3) {
			assertTrue(limiter.allow(subscription), "delivery $i is within the minute's allowance")
		}
		assertFalse(limiter.allow(subscription), "the fourth in one minute is what a loop looks like")
	}

	@Test
	fun `one runaway subscription does not throttle another`() {
		val limiter = limit(perMinute = 1)
		val looping = UUID.randomUUID()
		val innocent = UUID.randomUUID()

		assertTrue(limiter.allow(looping), "first for the loop")
		assertFalse(limiter.allow(looping), "second for the loop is refused")
		assertTrue(
			limiter.allow(innocent),
			"per subscription is the axis that makes this useful: the row to fix is the bucket that filled",
		)
	}

	@Test
	fun `the window slides, so a subscription that waited gets its allowance back`() {
		val limiter = limit(perMinute = 2)
		val subscription = UUID.randomUUID()

		assertTrue(limiter.allow(subscription))
		assertTrue(limiter.allow(subscription))
		assertFalse(limiter.allow(subscription), "full")

		now = now.plusSeconds(61)
		assertTrue(
			limiter.allow(subscription),
			"a minute later the earlier deliveries have aged out — this is a window, not a quota per hour",
		)
	}

	@Test
	fun `a refused delivery does not consume the allowance it was refused for`() {
		val limiter = limit(perMinute = 1)
		val subscription = UUID.randomUUID()

		assertTrue(limiter.allow(subscription))
		// Hammer it while full. Were a refusal to append, the bucket's oldest entry would
		// keep moving forward and the subscription would never recover — a throttle that
		// becomes a permanent block under load, which is the opposite of pacing.
		repeat(20) { assertFalse(limiter.allow(subscription), "still full") }

		now = now.plusSeconds(61)
		assertTrue(
			limiter.allow(subscription),
			"twenty refusals must not have pushed the window forward, or being throttled would be terminal",
		)
	}

	@Test
	fun `the retry-after is shorter than the window, because the window slides`() {
		val limiter = limit()
		assertTrue(
			limiter.retryAfter.seconds in 1..59,
			"waiting the whole minute would idle a subscription that had room again after seconds; " +
				"got ${limiter.retryAfter}",
		)
	}
}

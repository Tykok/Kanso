package dev.kanso.sync.notion

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterTest {

	/**
	 * A fake clock and a recording sleeper: the point is the spacing the limiter
	 * asks for, not that a test waits for it.
	 */
	private class FakeTime {
		var nanos: Long = 0L
		val sleeps = mutableListOf<Long>()

		fun advance(millis: Long) {
			nanos += millis * 1_000_000
		}
	}

	@Test
	fun `spaces requests at the configured rate`() = runTest {
		val time = FakeTime()
		val limiter = RateLimiter(
			permitsPerSecond = 2.5,
			nanoTime = { time.nanos },
			sleep = { millis -> time.sleeps += millis; time.advance(millis) },
		)

		// First permit is free; the rest are spaced by 1/2.5s = 400ms.
		repeat(4) { limiter.acquire() }

		assertEquals(3, time.sleeps.size, "expected three waits, got ${time.sleeps}")
		assertTrue(time.sleeps.all { it in 400..401 }, "expected ~400ms waits, got ${time.sleeps}")
	}

	@Test
	fun `does not delay a caller that arrives after the interval has passed`() = runTest {
		val time = FakeTime()
		val limiter = RateLimiter(
			permitsPerSecond = 2.5,
			nanoTime = { time.nanos },
			sleep = { millis -> time.sleeps += millis; time.advance(millis) },
		)

		limiter.acquire()
		time.advance(1_000)
		limiter.acquire()

		assertTrue(time.sleeps.isEmpty(), "an idle limiter should not make anyone wait: ${time.sleeps}")
	}

	@Test
	fun `a 429 penalty pushes back every queued slot, not just the rejected call`() = runTest {
		val time = FakeTime()
		val limiter = RateLimiter(
			permitsPerSecond = 2.5,
			nanoTime = { time.nanos },
			sleep = { millis -> time.sleeps += millis; time.advance(millis) },
		)

		limiter.acquire()
		limiter.penalise(5_000)
		limiter.acquire()

		assertEquals(1, time.sleeps.size)
		assertTrue(
			time.sleeps.single() >= 5_000,
			"the next caller should wait out the whole penalty, waited ${time.sleeps.single()}ms",
		)
	}
}

package dev.kanso.sync.notion

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Paces requests to a steady rate.
 *
 * Notion allows roughly three requests per second averaged over time and answers
 * 429 past that. Rather than react to rejections, this hands out evenly spaced
 * slots: it reserves the next free instant under the lock, then sleeps outside it,
 * so concurrent callers queue in order without serialising their actual work.
 *
 * The clock is injectable so the spacing can be asserted in a test without
 * waiting in real time.
 */
class RateLimiter(
	permitsPerSecond: Double,
	private val nanoTime: () -> Long = System::nanoTime,
	private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
	init {
		require(permitsPerSecond > 0) { "permitsPerSecond must be positive, was $permitsPerSecond" }
	}

	private val intervalNanos: Long = (1_000_000_000.0 / permitsPerSecond).toLong()
	private val mutex = Mutex()
	private var nextFreeNanos: Long = Long.MIN_VALUE

	suspend fun acquire() {
		val waitNanos = mutex.withLock {
			val now = nanoTime()
			val slot = if (nextFreeNanos == Long.MIN_VALUE) now else maxOf(now, nextFreeNanos)
			nextFreeNanos = slot + intervalNanos
			slot - now
		}
		if (waitNanos > 0) sleep(waitNanos / 1_000_000 + 1)
	}

	/**
	 * Pushes every queued slot back, used after a 429 so the whole client backs
	 * off rather than only the request that was rejected.
	 */
	suspend fun penalise(delayMillis: Long) {
		mutex.withLock {
			val now = nanoTime()
			val base = if (nextFreeNanos == Long.MIN_VALUE) now else maxOf(now, nextFreeNanos)
			nextFreeNanos = base + delayMillis * 1_000_000
		}
	}
}

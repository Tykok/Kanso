package dev.kanso.tokens

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How many requests one token may make in a minute.
 *
 * From day one, because the ticket says so and because the reason is structural rather
 * than precautionary: a cookie is held by a person who gets bored, and a token is held by
 * a loop. Every consumer this door was opened for — a webhook retry, a CI job, an MCP
 * client, a script somebody wrote at midnight — fails by repeating, and the first one
 * written against an unlimited API is the one that takes the instance down. Adding the
 * limit later means adding it to callers already built on its absence.
 *
 * Per *token* and not per user, which is the axis that makes it useful: a runaway CI job
 * is throttled while the same person's editor and their other integrations keep working,
 * and the row to revoke is named by the bucket that filled.
 *
 * ## Per-instance, and what that means
 *
 * This counter lives in this JVM's heap. **One instance is one limiter; two instances are
 * two limiters, and a token spread across both gets twice the quota.** Behind a load
 * balancer with three replicas the effective limit is three times this number, and it
 * drifts with how evenly the balancer spreads a single client's keep-alive connections —
 * which is to say the real ceiling is unknowable rather than merely higher. A restart
 * resets every bucket, so a rolling deploy briefly grants everybody a fresh minute.
 *
 * That is acceptable *today* and the reason is Kanso's deployment: one container, one
 * `docker compose` service, no replicas. It is written down here rather than assumed
 * because the day a second instance appears the fix is not to tune this number — it is to
 * move the counter to Postgres or Redis, and somebody will need to know that this file is
 * where the assumption was made. The shape is deliberately the same as
 * `RegistrationRateLimit`'s so that move can be made to both at once.
 *
 * ## What it does not protect
 *
 * A caller presenting a *different* invalid secret every time is not throttled by this at
 * all: there is no token to key on until the lookup succeeds, so guessing is bounded by
 * one indexed probe per attempt and by the 256 bits of `ApiTokenSecret.BYTES`, not by
 * anything here. That is the right division — a limiter that bucketed unauthenticated
 * attempts would have to key on an address, and `RegistrationRateLimit` records at length
 * why an address is not a key this application can trust.
 *
 * @param perMinute how many requests one token may make.
 * @param clock injected so the window can be tested without waiting a minute.
 */
class ApiTokenRateLimit(
	private val perMinute: Int,
	private val clock: () -> Instant = Instant::now,
) {

	private val window = Duration.ofMinutes(1)

	private val seen = ConcurrentHashMap<UUID, MutableList<Instant>>()

	/**
	 * No ceiling on the map, unlike `RegistrationRateLimit`, and the difference is the key.
	 *
	 * That one is keyed on an address, which the caller picks and can have 2^64 of. This is
	 * keyed on a token id, which exists only because a row exists in `api_tokens` — so the
	 * key space is a table this instance's own members wrote, bounded by the total number
	 * of tokens ever created, and not by anything an attacker can inflate. An entry for a
	 * revoked token lingers until the process restarts; it is one `UUID` and a short list,
	 * and a sweeper for it would be more moving parts than the leak it prevents.
	 */
	fun allow(tokenId: UUID): Boolean {
		val now = clock()
		val cutoff = now.minus(window)

		// The count and the append inside one `compute`, for the reason
		// `RegistrationRateLimit` gives: read outside it, two requests arriving together
		// both see room and both take it, and `recent += now` mutates the list without the
		// bin lock a concurrent prune holds — which is a broken list rather than merely a
		// generous one. A token is *expected* to arrive concurrently, so this is the
		// ordinary case here and not the racy edge.
		var allowed = false
		seen.compute(tokenId) { _, before ->
			val recent = (before ?: mutableListOf()).apply { removeAll { it.isBefore(cutoff) } }
			allowed = recent.size < perMinute
			if (allowed) recent += now
			recent.ifEmpty { null }
		}
		return allowed
	}

	/** Seconds a refused caller is told to wait. The window, which is the honest answer. */
	val retryAfterSeconds: Long get() = window.seconds
}

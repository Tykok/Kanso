package dev.kanso.webhooks

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How many deliveries one subscription may receive in a minute.
 *
 * From day one, because the ticket is explicit that it is not optional: *"sans rate limit
 * par abonnement, un webhook en boucle noie la base."* The loop it names is real and it is
 * short — a subscriber whose handler writes back to Kanso through `api_tokens` provokes the
 * change that provokes the next delivery, and nothing in the outbox slows that down. Two
 * integrations pointed at each other close the loop without either author noticing.
 *
 * The shape is [dev.kanso.tokens.ApiTokenRateLimit]'s, on purpose and down to the
 * `compute` block, because these two limiters guard the two halves of the same loop: that
 * one bounds the callbacks coming in, this one bounds the deliveries going out. They should
 * be moved to a shared store together, and looking like each other is what will make that
 * one change rather than two.
 *
 * ## Refusing here means waiting, not dropping
 *
 * The difference from `ApiTokenRateLimit`, and the reason this file is not simply that one
 * reused. That limiter answers an HTTP request, so a refusal is a `429` the caller sees and
 * the request is over. This one answers a *queue*, where there is no caller to tell: a
 * refusal turns into [dev.kanso.outbox.Failure.Defer], which puts the job back with its
 * attempt **refunded** and no `last_error` worth reading. So nothing is ever dropped for
 * being throttled, and a subscriber past their minute is late rather than skipped —
 * `max-attempts` is for endpoints that are broken, and being popular is not being broken.
 *
 * That is also what makes this a real answer to the ticket rather than a smaller version of
 * the same problem. The database load a loop creates is bounded by the outbox's own
 * coalescing — one pending job per entity, so a thousand edits of one ticket is one row —
 * and this bounds the *outbound* half the same way, by pacing rather than by refusing.
 *
 * ## Per-instance, and what that means
 *
 * This counter lives in this JVM's heap. One instance is one limiter; two instances are two
 * limiters, and a subscription served by both gets twice the quota. `ApiTokenRateLimit`
 * carries the full argument for why that is acceptable in Kanso's one-container deployment
 * and what to do the day it is not, and it applies here unchanged with one addition that
 * makes it *milder*: a restart resets every bucket, and where that briefly grants an API
 * caller a fresh minute, here it briefly grants a subscriber a faster catch-up on a queue
 * that was already going to be delivered.
 *
 * @param perMinute deliveries one subscription may receive.
 * @param clock injected so the window can be tested without waiting a minute.
 */
class WebhookRateLimit(
	private val perMinute: Int,
	private val clock: () -> Instant = Instant::now,
) {

	private val window = Duration.ofMinutes(1)

	private val seen = ConcurrentHashMap<UUID, MutableList<Instant>>()

	/**
	 * No ceiling on the map, for `ApiTokenRateLimit`'s reason: the key is a subscription id,
	 * which exists only because a configurator wrote a row in `webhook_subscriptions`. The
	 * key space is a table this instance's own admins wrote and not anything a caller can
	 * inflate — and it is a much smaller table than `api_tokens`, since a subscription is
	 * instance configuration rather than something every member makes for themselves.
	 */
	fun allow(subscriptionId: UUID): Boolean {
		val now = clock()
		val cutoff = now.minus(window)

		// The count and the append inside one `compute`, for `ApiTokenRateLimit`'s reason:
		// read outside it and two callers both see room and both take it. Concurrency is
		// less certain here — there is one drain per destination, so today's deliveries are
		// sequential — but `beat` already proves the outbox does not promise that, and a
		// limiter that is only correct while nothing runs in parallel is one that breaks
		// silently the day a destination drains in two threads.
		var allowed = false
		seen.compute(subscriptionId) { _, before ->
			val recent = (before ?: mutableListOf()).apply { removeAll { it.isBefore(cutoff) } }
			allowed = recent.size < perMinute
			if (allowed) recent += now
			recent.ifEmpty { null }
		}
		return allowed
	}

	/**
	 * How long to put a throttled job back for.
	 *
	 * A fraction of the window rather than the whole of it, and it is a *sliding* window —
	 * so the oldest delivery in a full bucket ages out within a fraction of a minute and
	 * waiting the whole minute would idle a subscription that had room again after five
	 * seconds. Deferring costs one claim and one `UPDATE`, which is cheap enough to prefer
	 * over sleeping through a permit.
	 */
	val retryAfter: Duration get() = window.dividedBy(SLICES)

	private companion object {
		const val SLICES = 6L
	}
}

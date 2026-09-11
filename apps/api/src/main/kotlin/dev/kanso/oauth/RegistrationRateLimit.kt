package dev.kanso.oauth

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * How often one caller may create a client.
 *
 * `/connect/register` is unauthenticated and writes rows. [RedirectUriPolicy] makes each
 * row harmless; this keeps a loop from making a million of them before the total cap
 * notices.
 *
 * In memory, unlike `LocalAuthService`'s sign-in throttle, and the difference is what is
 * being protected. A failed sign-in is evidence about an *account* and is worth surviving
 * a restart. A lost registration counter costs a handful of extra rows under a cap that
 * refuses anyway — not a table, not a lock, not a migration.
 *
 * **Which address, and therefore how much this is worth.** The bucket is one address, and
 * *which* address changed when `application.yml` set `server.forward-headers-strategy:
 * framework` for the distribution image: Boot's `ForwardedHeaderFilter` overrides
 * `getRemoteAddr` from the **leftmost** `X-Forwarded-For` entry, so behind that image this
 * counts a caller again instead of pooling every request behind the proxy into one
 * instance-wide bucket.
 *
 * Leftmost is the hop furthest from us and therefore the one the *caller* writes, so on its
 * own that would trade a weak-but-hard limit for no limit at all — a client rotating the
 * header would get a fresh bucket per request. What decides it is not this file and not the
 * strategy, but **who the edge is**, and `KANSO_TLS` is the variable that already says:
 *
 * - `auto`, the image's default: Caddy holds the socket, and
 *   `/etc/s6-overlay/scripts/kanso-init` writes it a `request_header -X-Forwarded-For` that
 *   drops whatever arrived before Caddy appends the peer it actually saw. The leftmost entry
 *   is then the real one and this is a genuine per-caller limit.
 * - `off`: the operator terminates TLS in their own proxy, that proxy is the edge, and Caddy
 *   preserves the header because replacing it would collapse every visitor into one bucket.
 *   Every word of the paragraph above is true again, sanitising is the operator's job, and
 *   `docs/self-hosting.md` says so.
 *
 * Neither half is closable from in here: it needs a count of trusted hops, which is a fact
 * about someone's deployment that a limiter cannot learn from inside the process. So under
 * `off` the cap that still holds is the `addresses` ceiling on the map itself and the
 * refusal at the end of it, on an endpoint that is open by design.
 *
 * @param perHour how many one address may create.
 * @param addresses how many addresses are remembered at once. A ceiling and not a
 *   guess at a working set: see [allow].
 * @param clock injected so the window can be tested without waiting an hour.
 */
class RegistrationRateLimit(
	private val perHour: Int,
	private val addresses: Int = 10_000,
	private val clock: () -> Instant = Instant::now,
) {

	private val window = Duration.ofHours(1)

	private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

	/** Only so the test that asserts the ceiling can see it; nothing else may care. */
	internal val trackedAddresses: Int get() = attempts.size

	/**
	 * A null address shares one bucket rather than escaping the limit. Behind a proxy
	 * that strips it, everything arrives unknown — and a limit that is optional for
	 * anyone who can arrange that is not a limit.
	 */
	fun allow(address: String?): Boolean {
		val key = address ?: "unknown"
		val now = clock()
		val cutoff = now.minus(window)

		// A key per address, and the address is the caller's to pick: an attacker holding
		// an IPv6 /64 has more of them than this process has memory, on an endpoint that
		// asks for no credential. So the map has a ceiling. Reaching it first sweeps what
		// the window has already made worthless, and only if that frees nothing is a *new*
		// address refused. Refusing is the failure to choose here: self-registration
		// unavailable for at most an hour is bounded, and the total client cap would be
		// refusing under that much traffic anyway, whereas a map that only grows ends the
		// process — taking every member's Kanso with it.
		if (attempts.size >= addresses && !attempts.containsKey(key)) {
			sweep(cutoff)
			if (attempts.size >= addresses && !attempts.containsKey(key)) return false
		}

		// The count and the append inside one `compute`, because they are one decision.
		// Read outside it, two registrations arriving together from one address both saw
		// room and both took it — and `recent += now` mutated an `ArrayList` without the
		// bin lock a concurrent `removeAll` holds, which is a list that ends up broken
		// rather than merely over-long.
		var allowed = false
		attempts.compute(key) { _, seen ->
			val recent = (seen ?: mutableListOf()).apply { removeAll { it.isBefore(cutoff) } }
			allowed = recent.size < perHour
			if (allowed) recent += now
			recent.ifEmpty { null }
		}
		return allowed
	}

	/**
	 * Addresses whose every attempt has aged out, dropped — which is the only thing that
	 * takes a key back out of the map.
	 *
	 * `computeIfPresent` over a snapshot of the keys, not an iterator over the entries:
	 * every read and every write of a list has to happen under its bin lock, and touching
	 * a value while another thread is inside [allow]'s `compute` would be the same race
	 * in a second place.
	 */
	private fun sweep(cutoff: Instant) {
		for (key in attempts.keys.toList()) {
			attempts.computeIfPresent(key) { _, seen ->
				seen.apply { removeAll { it.isBefore(cutoff) } }.ifEmpty { null }
			}
		}
	}
}

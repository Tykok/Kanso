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
 * @param clock injected so the window can be tested without waiting an hour.
 */
class RegistrationRateLimit(
	private val perHour: Int,
	private val clock: () -> Instant = Instant::now,
) {

	private val window = Duration.ofHours(1)

	private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

	/**
	 * A null address shares one bucket rather than escaping the limit. Behind a proxy
	 * that strips it, everything arrives unknown — and a limit that is optional for
	 * anyone who can arrange that is not a limit.
	 */
	fun allow(address: String?): Boolean {
		val key = address ?: "unknown"
		val now = clock()
		val recent = attempts.compute(key) { _, existing ->
			(existing ?: mutableListOf()).apply { removeAll { it.isBefore(now.minus(window)) } }
		}!!
		if (recent.size >= perHour) return false
		recent += now
		return true
	}
}

package dev.kanso.publik

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Who voted, without recording who voted.
 *
 * The drawing shows open voting with no sign-in, so there is no account to key a vote
 * to and the only thing the request carries is an address. Storing that address would
 * make double voting impossible — and would also mean the `votes` table of every
 * self-hosted instance is a log of who read the roadmap, kept forever, for a feature
 * whose entire purpose is a rough sense of what people want. That is the worse trade,
 * so it is not the one taken: the stored value is `HMAC(secret, address | day)`, and a
 * determined visitor with a second address or a second day gets a second vote. Nobody
 * is counting on this being exact; it exists to stop the reload and the second tab.
 *
 * **Keyed, not merely hashed.** A plain SHA-256 of an IPv4 address is undone by trying
 * all four billion of them, which would leave this a column of addresses written in a
 * slower notation. The HMAC key is the instance secret — the one `SecretBox` already
 * resolves for the Notion and Google credentials — so an attacker holding the database
 * and not the key gets nothing back out.
 *
 * The key is read lazily rather than in the constructor, and that is deliberate: on a
 * fresh instance `SecretBox` *writes* the key file during startup, and two components
 * racing to create the same file is a failure mode with no upside. By the time anybody
 * has voted, startup is long finished and the file is there to be read.
 */
@Component
class VoterKeys(
	@Value("\${kanso.security.secret-key:}") private val configuredKey: String,
	@Value("\${kanso.security.key-file:./data/secret.key}") private val keyFilePath: String,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val key: ByteArray by lazy { resolveKey() }

	/** The value stored in `votes.voter_key` for this address, today. */
	fun keyFor(address: String): String {
		val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
		val day = LocalDate.now(ZoneOffset.UTC)
		// The separator matters: without it, address `1.2.3.4` on day `5-06-07` and
		// address `1.2.3.45` on day `-06-07` would hash to the same voter.
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(mac.doFinal("$address|$day".toByteArray(Charsets.UTF_8)))
	}

	private fun resolveKey(): ByteArray {
		if (configuredKey.isNotBlank()) return Base64.getDecoder().decode(configuredKey.trim())

		val file = Path.of(keyFilePath)
		if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim())

		// Only reachable when the key file could not be created at startup. A salt that
		// lives as long as the process means a restart forgets today's voters, which
		// costs one extra vote per restart — the same mild failure the day-scoped key
		// already accepts, so it is not worth refusing to serve the page over.
		log.warn(
			"No instance secret at {} — vote de-duplication resets when this process does. " +
				"Set KANSO_SECRET_KEY to make it survive a restart.",
			keyFilePath,
		)
		return ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
	}

	private companion object {
		const val ALGORITHM = "HmacSHA256"
		const val KEY_BYTES = 32
	}
}

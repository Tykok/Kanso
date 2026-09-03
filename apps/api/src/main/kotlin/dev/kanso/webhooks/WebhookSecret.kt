package dev.kanso.webhooks

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The instance has no `kanso.webhooks.signing-key`, so there is nowhere safe to put a secret. */
class WebhooksNotConfigured :
	RuntimeException("Webhooks need kanso.webhooks.signing-key set to 32 base64 bytes")

/**
 * The signing secret: how one is made, and the only form the database holds.
 *
 * The shape is [dev.kanso.tokens.ApiTokenSecret]'s deliberately — generate, label, reduce
 * — and the one method that differs is the important one. That object *hashes*, because a
 * PAT is presented to Kanso and only has to be recognised. This one **encrypts**, because
 * a webhook secret is never presented: Kanso computes an HMAC with it over every body it
 * sends, so the bytes have to come back. `V31` argues the storage decision at length and
 * `V27` argues the one it departs from; this is the same decisions as code.
 *
 * ## What encryption here buys, and what it does not
 *
 * It buys exactly what `V27` claims for its digest: a stolen database dump is not a set of
 * live credentials. `pg_dump` output, an old backup, a read-only replica or a SQL injection
 * that can read `webhook_subscriptions` all yield ciphertext.
 *
 * It buys **nothing against a compromised host.** In the shipped `docker-compose.yml` the
 * key and the database password are environment on the same service, so anybody who has
 * one has both. Said here rather than left to be discovered, because the difference between
 * "encrypted at rest" and "safe" is where people put too much faith, and because moving the
 * key to a real secret store is the change that would make this claim stronger — this file
 * is where somebody making it should start.
 *
 * ## Why GCM
 *
 * It authenticates. A tampered row fails to decrypt loudly instead of yielding
 * attacker-chosen key material that Kanso would then sign with — a subscriber verifying
 * against their own copy of the secret would see every delivery fail, and the cause would
 * be invisible from either end. CBC would decrypt whatever it was given.
 *
 * @param base64Key 32 raw bytes, base64. Blank means the feature is off; see
 *   `KansoProperties.Webhooks.signingKey` for why that is a refusal and not a default.
 */
class WebhookSecret(base64Key: String) {

	private val key: SecretKeySpec? = base64Key.takeIf { it.isNotBlank() }?.let {
		val raw = runCatching { Base64.getDecoder().decode(it.trim()) }
			.getOrElse { throw IllegalArgumentException("kanso.webhooks.signing-key is not valid base64") }
		require(raw.size == KEY_BYTES) {
			"kanso.webhooks.signing-key must be $KEY_BYTES bytes, got ${raw.size}"
		}
		SecretKeySpec(raw, "AES")
	}

	val configured: Boolean get() = key != null

	/**
	 * A fresh secret, in the form the subscriber pastes into their own configuration.
	 *
	 * Generated here and never accepted from a caller, for the reason `ApiTokenService`
	 * gives: an endpoint that could be handed one is an endpoint a fixture or a future
	 * caller could hand a weak one to, and there is no reason for that door to exist.
	 */
	fun generate(): String = MARKER + ENCODER.encodeToString(ByteArray(SECRET_BYTES).also(RANDOM::nextBytes))

	/** Base64 of `iv || ciphertext || tag` — one column, because the parts are useless apart. */
	fun encrypt(plaintext: String): String {
		val cipher = cipher() ?: throw WebhooksNotConfigured()
		// A fresh IV per encryption, never a stored or derived one. Reusing an IV under one
		// GCM key is the failure that breaks GCM outright rather than degrading it, and
		// twelve random bytes is the size the mode is specified for.
		val iv = ByteArray(IV_BYTES).also(RANDOM::nextBytes)
		cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
		return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
	}

	/**
	 * The secret back, to sign with.
	 *
	 * Throws on a row this key cannot open — a rotated key, a restored dump from another
	 * instance, a tampered column. Throwing is right: the alternative is signing with
	 * something wrong, which every subscriber would reject while the instance reported
	 * success. `WebhookOutboundHandler` turns this into a [dev.kanso.outbox.Failure.Fatal],
	 * because no number of retries will decrypt it.
	 */
	fun decrypt(stored: String): String {
		val cipher = cipher() ?: throw WebhooksNotConfigured()
		val raw = Base64.getDecoder().decode(stored)
		require(raw.size > IV_BYTES) { "stored webhook secret is too short to contain an IV" }
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
		return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
	}

	private fun cipher(): Cipher? = key?.let { Cipher.getInstance("AES/GCM/NoPadding") }

	companion object {
		/**
		 * `kanso_whsec_`, and the distinction from `kanso_pat_` was reserved in advance.
		 *
		 * `ApiTokenSecret.MARKER` says why: "a webhook signing key (KAN-17) is also a
		 * `kanso_…` string and must not be confusable with a credential that acts as a
		 * person". A PAT in an `Authorization` header authenticates as somebody and is
		 * worth an instance's data; this authenticates nothing and only proves a body came
		 * from here. Two things with different blast radii should not look alike in a
		 * `.env` file, and `whsec` is the word the ecosystem already uses for this one.
		 */
		const val MARKER = "kanso_whsec_"

		/** Six characters past the marker, for `V31`'s and `V27`'s shared reason. */
		const val PREFIX_LENGTH = MARKER.length + 6

		/** AES-256. */
		private const val KEY_BYTES = 32

		/** As `ApiTokenSecret.BYTES`, and load-bearing for the same reason: 256 bits of HMAC key. */
		private const val SECRET_BYTES = 32

		private const val IV_BYTES = 12
		private const val TAG_BITS = 128

		private val RANDOM = SecureRandom()

		/** Base64url unpadded, so the whole secret is one shell-safe word — `ApiTokenSecret` argues it. */
		private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

		fun prefixOf(secret: String): String = secret.take(PREFIX_LENGTH)
	}
}

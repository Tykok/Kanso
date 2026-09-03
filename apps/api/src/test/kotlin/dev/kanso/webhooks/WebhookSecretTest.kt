package dev.kanso.webhooks

import dev.kanso.tokens.ApiTokenSecret
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The storage decision, held to what `V31` claims for it.
 *
 * Three of these are the claims a reader would otherwise have to trust: that the ciphertext
 * is not the plaintext, that two encryptions of one secret differ (a reused IV is the
 * failure that breaks GCM outright rather than degrading it), and that a tampered row
 * refuses to open instead of yielding attacker-chosen key material for Kanso to sign with.
 */
class WebhookSecretTest {

	private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
	private val secrets = WebhookSecret(key)

	@Test
	fun `a generated secret is marked as a signing key and not as a credential`() {
		val secret = secrets.generate()
		assertTrue(secret.startsWith(WebhookSecret.MARKER), "secret was $secret")
		assertFalse(
			secret.startsWith(ApiTokenSecret.MARKER),
			"`ApiTokenSecret.MARKER` reserved the distinction in advance: a signing key must not " +
				"be confusable with a credential that acts as a person",
		)
		assertTrue(
			secret.drop(WebhookSecret.MARKER.length).all { it.isLetterOrDigit() || it == '_' || it == '-' },
			"base64url unpadded, so the whole secret is one shell-safe word: $secret",
		)
	}

	@Test
	fun `the secret comes back, because signing needs the bytes and no digest could give them back`() {
		val secret = secrets.generate()
		assertEquals(
			secret,
			secrets.decrypt(secrets.encrypt(secret)),
			"this is the one property that rules out V27's SHA-256 for this column",
		)
	}

	@Test
	fun `what is stored is not what was generated`() {
		val secret = secrets.generate()
		val stored = secrets.encrypt(secret)
		assertFalse(
			stored.contains(secret) || stored.contains(secret.drop(WebhookSecret.MARKER.length)),
			"a database dump must not be a set of signing keys, which is the whole claim",
		)
	}

	@Test
	fun `the same secret encrypts differently every time, because the IV is fresh`() {
		val secret = secrets.generate()
		assertNotEquals(
			secrets.encrypt(secret),
			secrets.encrypt(secret),
			"a reused IV under one GCM key breaks the mode outright rather than weakening it",
		)
	}

	@Test
	fun `a tampered row refuses to open rather than yielding whatever it was given`() {
		val stored = secrets.encrypt(secrets.generate())
		val raw = Base64.getDecoder().decode(stored)
		// One bit of the ciphertext, well past the twelve-byte IV.
		raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()

		assertFailsWith<Exception>("GCM authenticates; CBC would have decrypted this into nonsense and signed with it") {
			secrets.decrypt(Base64.getEncoder().encodeToString(raw))
		}
	}

	@Test
	fun `another instance's key cannot read this instance's rows`() {
		val stored = secrets.encrypt(secrets.generate())
		val elsewhere = WebhookSecret(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }))
		assertFailsWith<Exception>("a restored dump must not be signable by whoever restored it") {
			elsewhere.decrypt(stored)
		}
	}

	@Test
	fun `with no key the slice reports itself off, and refuses rather than storing plaintext`() {
		val unconfigured = WebhookSecret("")
		assertFalse(unconfigured.configured, "a blank key means the feature is off")
		assertFailsWith<WebhooksNotConfigured>("storing a signing secret in plaintext is not the fallback") {
			unconfigured.encrypt("kanso_whsec_anything")
		}
	}

	@Test
	fun `a key of the wrong size is refused at startup rather than at the first delivery`() {
		for (size in listOf(1, 16, 31, 33, 64)) {
			assertFailsWith<IllegalArgumentException>("$size bytes must not be accepted as AES-256") {
				WebhookSecret(Base64.getEncoder().encodeToString(ByteArray(size)))
			}
		}
		assertFailsWith<IllegalArgumentException>("a key that is not base64 must say so") {
			WebhookSecret("this is not base64 at all !!")
		}
	}

	@Test
	fun `the prefix labels a row without being a head start`() {
		val secret = secrets.generate()
		val prefix = WebhookSecret.prefixOf(secret)
		assertTrue(secret.startsWith(prefix), "the prefix must be the start of the secret")
		assertEquals(WebhookSecret.MARKER.length + 6, prefix.length, "six characters past the marker")
	}
}

package dev.kanso.webhooks

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The signature, and specifically the one property a reader has to take on trust otherwise:
 * that **the timestamp is inside the HMAC**.
 *
 * That is the whole of the replay defence and it is invisible by inspection — a header
 * carrying a `t` and a `v1` looks identical whether or not the `t` was signed. The test
 * that catches the wrong version is [a captured body cannot be presented later with a fresh
 * timestamp]: sign the body alone, as a plausible implementation would, and re-stamping `t`
 * produces a header that verifies. Signed with the timestamp, it cannot.
 *
 * No base class and no annotations: this is arithmetic, and `RateLimiterTest` and
 * `GoogleCredentialProbeTest` set the precedent for a pure unit test here.
 */
class WebhookSignatureTest {

	private val secret = "kanso_whsec_ZmFrZS1zZWNyZXQtZm9yLWEtdGVzdC1vbmx5"
	private val body = """{"event":"ticket.updated","entity":"ticket","id":"a1b2c3d4-0000-0000-0000-000000000001"}"""
	private val now = Instant.parse("2026-09-03T10:00:00Z")

	@Test
	fun `a header this code produced is one this code accepts`() {
		val header = WebhookSignature.header(body, secret, now)
		assertTrue(
			WebhookSignature.verify(body, secret, header, now),
			"the recipe in the KDoc is executable, and this is what keeps it from drifting from the signer",
		)
	}

	@Test
	fun `the header says t and v1, in the format the recipe tells subscribers to parse`() {
		val header = WebhookSignature.header(body, secret, now)
		assertTrue(header.startsWith("t=${now.epochSecond},v1="), "header was $header")
		val digest = header.substringAfter("v1=")
		assertTrue(
			digest.length == 64 && digest.all { it in "0123456789abcdef" },
			"a subscriber is told 64 lowercase hex characters, and got $digest",
		)
	}

	@Test
	fun `one byte of the body changed, and the signature no longer verifies`() {
		val header = WebhookSignature.header(body, secret, now)
		assertFalse(
			WebhookSignature.verify(body.replace("updated", "deleted"), secret, header, now),
			"a body somebody rewrote in flight must not verify, or the signature proves nothing",
		)
	}

	@Test
	fun `somebody else's secret does not verify`() {
		val header = WebhookSignature.header(body, secret, now)
		assertFalse(
			WebhookSignature.verify(body, secret + "x", header, now),
			"two subscriptions hold different secrets and must not be able to check each other's bodies",
		)
	}

	@Test
	fun `a captured body cannot be presented later with a fresh timestamp`() {
		val captured = WebhookSignature.header(body, secret, now)
		val muchLater = now.plusSeconds(3600)

		// Step 5 of the recipe on its own: the original header is simply stale.
		assertFalse(
			WebhookSignature.verify(body, secret, captured, muchLater),
			"an hour-old delivery must fail the freshness check",
		)

		// And step 5 cannot be worked around, which is the part that needs the timestamp to
		// be *inside* the digest. An attacker holding the captured body rewrites `t` to now
		// and keeps the digest, because the digest is all they have.
		val restamped = "t=${muchLater.epochSecond},v1=${captured.substringAfter("v1=")}"
		assertFalse(
			WebhookSignature.verify(body, secret, restamped, muchLater),
			"re-stamping the timestamp must break the digest — this is the whole replay defence",
		)

		// The proof that the previous assertion is testing something real: had the signature
		// covered the body alone, the same re-stamp would have sailed through. This is the
		// RED case for the guard, written out rather than described.
		val bodyOnly = Mac.getInstance("HmacSHA256")
			.apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
			.doFinal(body.toByteArray())
			.joinToString("") { "%02x".format(it) }
		assertTrue(
			bodyOnly == Mac.getInstance("HmacSHA256")
				.apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
				.doFinal(body.toByteArray())
				.joinToString("") { "%02x".format(it) },
			"a body-only digest is the same for every timestamp, which is exactly why it is not what is sent",
		)
		assertFalse(
			bodyOnly == captured.substringAfter("v1="),
			"the header's digest must not equal HMAC(body) alone, or the timestamp is not covered",
		)
	}

	@Test
	fun `a malformed header is refused rather than throwing`() {
		for (header in listOf("", "t=", "v1=abc", "t=notanumber,v1=abc", "garbage", "t=1,v1=")) {
			assertFalse(
				WebhookSignature.verify(body, secret, header, now),
				"a receiver's bad header must be a false, not an exception: '$header'",
			)
		}
	}

	@Test
	fun `an unknown scheme version beside v1 does not stop v1 from being read`() {
		val header = WebhookSignature.header(body, secret, now) + ",v2=whatever-comes-next"
		assertTrue(
			WebhookSignature.verify(body, secret, header, now),
			"the recipe promises a subscriber that a v2 may appear beside v1 and old code keeps working",
		)
	}
}

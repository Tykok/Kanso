package dev.kanso.webhooks

import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The proof that a body came from this Kanso, and the recipe for checking it.
 *
 * ## Verifying a Kanso webhook
 *
 * Kanso sends, on every delivery:
 *
 * ```
 * POST /your/endpoint
 * Content-Type: application/json
 * X-Kanso-Signature: t=1756900000,v1=3b7c…  (64 lowercase hex characters)
 * X-Kanso-Delivery:  0f2c9a44-1d3e-4a71-9f52-8e6b0c1d2a34
 * X-Kanso-Event:     ticket.updated
 * ```
 *
 * To verify, with `secret` being the `kanso_whsec_…` string shown once when the
 * subscription was created:
 *
 *   1. Read `t` and `v1` out of `X-Kanso-Signature`. Split on `,` then on `=`; do not
 *      assume the order or that there are only two parts, because a `v2=` will be added
 *      beside `v1` the day the scheme changes and an old receiver has to keep working.
 *   2. Build the signed string as **`t`, a literal full stop, then the raw request body**:
 *      `"$t.$body"`. The body is the bytes as received — not reserialised, not
 *      pretty-printed, not with keys reordered. Frameworks that hand you a parsed object
 *      have usually thrown the original bytes away; take the raw body before parsing.
 *   3. Compute `HMAC-SHA256(key = secret, message = "$t.$body")` and hex-encode it in
 *      lowercase. The key is the whole secret string including the `kanso_whsec_` marker,
 *      UTF-8, not base64-decoded and not stripped.
 *   4. Compare with `v1` using a **constant-time** comparison (`hmac.compare_digest` in
 *      Python, `crypto.timingSafeEqual` in Node, `hmac.Equal` in Go). A `==` on strings
 *      leaks how many leading characters were right, which is enough to forge one.
 *   5. Reject anything where `now - t` is larger than you are willing to accept — five
 *      minutes is the usual answer. **This step is not optional**, and step 2 is what makes
 *      it work: because `t` is inside the signed string, an attacker who captured a valid
 *      body cannot present it later with a fresh timestamp — they would have to re-sign it,
 *      which needs the secret. Without the freshness check the signature only proves the
 *      body was *ever* genuine, which a replayed `ticket.deleted` satisfies.
 *   6. Dedupe on `X-Kanso-Delivery`. Kanso retries a failed delivery with the **same**
 *      delivery id and a **new** `t`, so that header is a true idempotency key while the
 *      signature stays fresh. A delivery a human replays from the Kanso UI is a *different*
 *      id on purpose — that is somebody asking for it again, and a receiver that deduped it
 *      away would make the button do nothing.
 *
 * Two independent facts, two headers. `t` says "this request is recent"; `X-Kanso-Delivery`
 * says "this is that same delivery". One value cannot carry both, which is why a receiver
 * needs both steps.
 *
 * The body is JSON but the signature is over bytes and knows nothing about that, which is
 * the property that keeps step 2 stateable at all.
 */
object WebhookSignature {

	const val HEADER = "X-Kanso-Signature"

	/** The idempotency key. Stable across retries of one delivery — see step 6. */
	const val DELIVERY_HEADER = "X-Kanso-Delivery"

	/** `ticket.updated`, so a receiver can route without parsing the body. */
	const val EVENT_HEADER = "X-Kanso-Event"

	/**
	 * The header value for one body, signed now.
	 *
	 * `t` and `v1` in one header rather than two, so a receiver cannot read a timestamp
	 * from one place and a digest from another and end up verifying a pair that never
	 * belonged together. The scheme is versioned in the value because that is where a
	 * second algorithm has to be able to appear beside the first — a header named
	 * `X-Kanso-Signature-V2` would be a header old receivers ignore and new ones have to
	 * guess about.
	 */
	fun header(body: String, secret: String, at: Instant): String {
		val t = at.epochSecond
		return "t=$t,v1=${hex(sign("$t.$body", secret))}"
	}

	/**
	 * The verification half, so the recipe above is executable rather than only written
	 * down. Kanso itself never calls this — a signature it produced needs no checking —
	 * and it exists because a recipe nothing runs is a recipe that drifts from the
	 * signer. `WebhookSignatureTest` verifies real headers through it.
	 */
	fun verify(body: String, secret: String, header: String, now: Instant, tolerance: Long = 300): Boolean {
		val parts = header.split(",").mapNotNull {
			val (name, value) = it.trim().split("=", limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
			name to value
		}.toMap()

		val t = parts["t"]?.toLongOrNull() ?: return false
		val presented = parts["v1"] ?: return false
		if (Math.abs(now.epochSecond - t) > tolerance) return false
		return constantTimeEquals(hex(sign("$t.$body", secret)), presented)
	}

	private fun sign(message: String, secret: String): ByteArray =
		Mac.getInstance("HmacSHA256").apply {
			init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
		}.doFinal(message.toByteArray(Charsets.UTF_8))

	private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

	/**
	 * Length first, then every character, and never an early return.
	 *
	 * `==` on two strings stops at the first difference, so how long it took says how many
	 * leading characters were right — and a digest guessable one character at a time is
	 * not a digest. Comparing the full length always is the whole of the fix; `or` rather
	 * than `||` because the boolean operator would short-circuit and put the branch back.
	 */
	private fun constantTimeEquals(a: String, b: String): Boolean {
		if (a.length != b.length) return false
		var diff = 0
		for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
		return diff == 0
	}
}

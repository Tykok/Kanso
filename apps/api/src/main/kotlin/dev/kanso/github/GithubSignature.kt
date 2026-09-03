package dev.kanso.github

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The only guard on `/api/github/webhook`, which is unauthenticated and writes rows.
 *
 * An `object` rather than a bean, following `WebhookSignature`: it holds no state and takes
 * its key as an argument, so a test can exercise it without a context and the controller
 * cannot be constructed without one.
 *
 * **The trap, written here rather than discovered.** `X-Hub-Signature-256` is an
 * HMAC-SHA256 of the **raw request body**. A body Jackson has parsed and re-serialised has
 * different whitespace and a different key order, so the digest will not match — and the
 * symptom is *"GitHub is sending bad signatures"*, which sends the next person to look at
 * GitHub. This is why [verify] takes a `ByteArray` and why the controller's parameter is
 * `@RequestBody bytes: ByteArray`: there is no overload that accepts a parsed object,
 * because an overload that accepted one would eventually be called.
 */
object GithubSignature {

	const val HEADER = "X-Hub-Signature-256"
	const val DELIVERY_HEADER = "X-GitHub-Delivery"
	const val EVENT_HEADER = "X-GitHub-Event"

	/** GitHub's own prefix, and part of the compared string rather than stripped first. */
	private const val PREFIX = "sha256="

	/**
	 * Whether [header] is GitHub's signature of exactly these bytes.
	 *
	 * Every failure answers `false` and none of them explains itself. A missing header, a
	 * malformed one and a wrong digest are one answer to a caller who is either GitHub or an
	 * attacker, and telling the second which of the three it got is the only thing a
	 * distinction here could buy.
	 */
	fun verify(body: ByteArray, secret: String, header: String?): Boolean {
		if (header.isNullOrBlank() || secret.isBlank()) return false
		return constantTimeEquals(header, sign(body, secret))
	}

	/**
	 * What GitHub would send for these bytes. Public because the tests sign their fixtures
	 * with it — the alternative is a hex digest pasted into a test file, which asserts that
	 * somebody once ran the right command rather than that the code is correct.
	 */
	fun sign(body: ByteArray, secret: String): String {
		val mac = Mac.getInstance("HmacSHA256").apply {
			init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
		}
		return PREFIX + mac.doFinal(body).joinToString("") { "%02x".format(it) }
	}

	/**
	 * `MessageDigest.isEqual`, which is the JDK's constant-time comparison and is
	 * documented as such — rather than a hand-rolled XOR loop, and rather than `==`.
	 *
	 * `String.equals` returns on the first differing character, so the time it takes leaks
	 * how much of a guess was right. That is enough to recover a digest one byte at a time
	 * given enough attempts, and this endpoint has no rate limit in front of it.
	 *
	 * Compared as ASCII bytes of the whole `sha256=…` string. Lengths differing is not an
	 * early return here either: `isEqual` is specified not to leak on length beyond the fact
	 * that it differs, which for a fixed-width digest is public anyway.
	 */
	private fun constantTimeEquals(a: String, b: String): Boolean =
		MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

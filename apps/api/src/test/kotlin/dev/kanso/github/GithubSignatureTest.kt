package dev.kanso.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The signature, on its own, because it is the only guard on an unauthenticated endpoint
 * that writes rows.
 *
 * The design asks for this assertion end to end — a real payload posted with a wrong
 * signature, and no row moved — and that test belongs with the controller, which is not in
 * this change. What is here is the primitive underneath it, which is the half that can be
 * wrong silently: a wrong digest fails loudly the first time GitHub calls, and a comparison
 * that leaks timing or a `verify` that accepts an absent header fails never.
 */
class GithubSignatureTest {

	/**
	 * GitHub's own documented example, which is the whole reason this test is worth more
	 * than a round trip through [GithubSignature.sign].
	 *
	 * Secret `It's a Secret to Everybody`, body `Hello, World!`. Asserting our digest
	 * against a value GitHub published is what proves the algorithm, the encoding and the
	 * `sha256=` prefix are theirs and not merely self-consistent — a round trip would pass
	 * just as happily with the wrong hash function.
	 */
	@Test
	fun `the digest is GitHub's, not merely our own`() {
		assertEquals(
			"sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
			GithubSignature.sign("Hello, World!".toByteArray(), "It's a Secret to Everybody"),
		)
	}

	@Test
	fun `a body signed with the secret verifies`() {
		val body = """{"action":"opened","number":418}""".toByteArray()
		assertTrue(GithubSignature.verify(body, "shh", GithubSignature.sign(body, "shh")))
	}

	@Test
	fun `a different secret does not`() {
		val body = """{"action":"opened"}""".toByteArray()
		assertFalse(GithubSignature.verify(body, "shh", GithubSignature.sign(body, "other")))
	}

	/**
	 * **The trap, asserted.** Re-serialised JSON differs from the raw body only in
	 * whitespace and key order, and it must not verify — which is the whole reason
	 * `verify` takes bytes and the controller's parameter is a `ByteArray`. Presents as
	 * "GitHub is sending bad signatures", so it is worth a test rather than a comment.
	 */
	@Test
	fun `the same JSON with different whitespace is a different body`() {
		val raw = """{"action":"opened","number":418}"""
		val reserialised = """{"action": "opened", "number": 418}"""
		val header = GithubSignature.sign(raw.toByteArray(), "shh")

		assertTrue(GithubSignature.verify(raw.toByteArray(), "shh", header))
		assertFalse(
			GithubSignature.verify(reserialised.toByteArray(), "shh", header),
			"a body Jackson parsed and re-serialised must not verify against the raw one's HMAC",
		)
	}

	/** A byte changed anywhere in the body invalidates it. */
	@Test
	fun `a tampered body does not verify`() {
		val body = """{"action":"closed","merged":false}""".toByteArray()
		val header = GithubSignature.sign(body, "shh")
		val tampered = """{"action":"closed","merged":true}""".toByteArray()
		assertFalse(GithubSignature.verify(tampered, "shh", header))
	}

	/**
	 * Every shape of nothing is a refusal, and none of them throws. An unsigned request is
	 * the commonest thing a stranger sends, so `verify` has to answer it rather than 500 —
	 * a stack trace is an answer too, and a more informative one than we owe.
	 */
	@Test
	fun `an absent, blank or malformed header is refused and never throws`() {
		val body = "{}".toByteArray()
		for (header in listOf(null, "", "   ", "sha256=", "garbage", "sha1=abc")) {
			assertFalse(GithubSignature.verify(body, "shh", header), "header=$header")
		}
	}

	/**
	 * An instance with no webhook secret configured cannot verify anything, so it must
	 * accept **nothing** — including a header that is perfectly well formed, because an
	 * attacker's is. Without this, an unconfigured instance would be an open endpoint that
	 * writes rows, which is the worst state this feature can be in.
	 *
	 * `verify` short-circuits on the blank secret before it reaches the JCE, so this also
	 * asserts it does not throw its way out of the question.
	 */
	@Test
	fun `no configured secret verifies nothing, however well formed the header`() {
		val body = "{}".toByteArray()
		val plausible = GithubSignature.sign(body, "some-secret-the-attacker-picked")
		assertFalse(GithubSignature.verify(body, "", plausible))
		assertFalse(GithubSignature.verify(body, "   ", plausible))
	}

	/**
	 * Signing with no secret is a programming error, not a configuration one, and it says so
	 * in words — the JCE's own refusal is `IllegalArgumentException: Empty key`, which sends
	 * the reader into the crypto provider rather than to the missing setting.
	 */
	@Test
	fun `signing with no secret is refused in words`() {
		val thrown = assertFailsWith<IllegalArgumentException> {
			GithubSignature.sign("{}".toByteArray(), "")
		}
		assertTrue(
			thrown.message!!.contains("webhook secret"),
			"names the missing setting: ${thrown.message}",
		)
	}

	/** Lowercase hex, `sha256=` prefixed, and 64 digits — GitHub's format exactly. */
	@Test
	fun `the header is lowercase hex behind GitHub's prefix`() {
		val header = GithubSignature.sign("x".toByteArray(), "shh")
		assertTrue(header.startsWith("sha256="), header)
		val digest = header.removePrefix("sha256=")
		assertEquals(64, digest.length, header)
		assertTrue(digest.all { it in "0123456789abcdef" }, header)
	}
}

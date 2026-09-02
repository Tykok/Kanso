package dev.kanso.tokens

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The secret: how one is made, and how it is reduced to the only form Kanso keeps.
 *
 * Its own object, small and with no dependencies, because everything in it is a claim the
 * rest of the slice relies on and none of it should have to be re-derived by a reader
 * following the filter. `V27__api_tokens.sql` argues the *storage* decisions at length;
 * this is the same decisions as code.
 */
object ApiTokenSecret {

	/**
	 * What every token starts with, and it is not branding.
	 *
	 * Two things read it. A person: a 43-character blob in a `.env` file is
	 * indistinguishable from every other 43-character blob, and one that says what it is
	 * gets rotated instead of shrugged at. And a secret scanner: GitHub, `gitleaks` and
	 * friends match credentials by exactly this kind of fixed marker, so a token pasted
	 * into a public repository can be *recognised* — which is worth more than any amount
	 * of documentation telling people not to do that.
	 *
	 * `pat` rather than `token`, to leave room for the other kinds of secret this codebase
	 * will grow — a webhook signing key (KAN-17) is also a `kanso_…` string and must not
	 * be confusable with a credential that acts as a person.
	 */
	const val MARKER = "kanso_pat_"

	/**
	 * How much of the secret the settings screen may print.
	 *
	 * Six characters past the marker: enough to pick one row out of a list of four,
	 * and 36 of 256 bits given away, leaving 220. `V27` records why storing this at all
	 * is the one derived value in the schema that is not computed on read.
	 */
	const val PREFIX_LENGTH = MARKER.length + 6

	/**
	 * 32 bytes, which is not a round number chosen for looking like one: it is the point
	 * past which the digest in the table stops being the weakest link, so the whole
	 * argument in `V27` for a fast hash — "there is no search space being searched" —
	 * rests on this constant and not on the algorithm.
	 */
	private const val BYTES = 32

	/** Seeded by the platform, and never re-seeded by hand: doing so is how entropy is lost. */
	private val random = SecureRandom()

	private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

	/**
	 * Base64url and unpadded, so the whole token is one word.
	 *
	 * It goes into shell exports, `Authorization` headers, YAML, CI secret fields and
	 * `.env` files. Standard base64 would put `+` and `/` in it — one of which a URL
	 * encoder rewrites and the other a shell path-completes — and padding would put `=`
	 * in it, which `KEY=value` files and some header parsers handle badly. The character
	 * set here is `[A-Za-z0-9_-]`, which nothing on that list touches.
	 */
	fun generate(): String = MARKER + encoder.encodeToString(ByteArray(BYTES).also(random::nextBytes))

	fun prefixOf(secret: String): String = secret.take(PREFIX_LENGTH)

	/**
	 * Lowercase hex of SHA-256, which is the form the column holds.
	 *
	 * No salt and no per-row work factor — `V27__api_tokens.sql` carries the argument, and
	 * the short version is that this digest has to be *looked up*, not compared. Nothing
	 * in this slice ever compares two digests, so there is also no equality check here for
	 * anybody to worry about timing.
	 */
	fun hash(secret: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(secret.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
}

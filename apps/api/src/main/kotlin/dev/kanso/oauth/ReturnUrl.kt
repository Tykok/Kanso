package dev.kanso.oauth

import java.net.URI
import java.net.URISyntaxException

/**
 * Where the session remembers the way back, for the length of one provider round trip.
 *
 * A session attribute rather than Spring Security's `RequestCache`, and the difference is
 * not taste: `SavedRequestAwareAuthenticationSuccessHandler` replays what
 * `ExceptionTranslationFilter` cached when a request was **denied**, and the consent page
 * is `permitAll` — it is never denied, so it is never cached. What the cache would hold
 * instead is whichever earlier HTML `GET` on the API happened to get a 401, and sending
 * every member there after every provider sign-in would be a larger change than the one
 * being made. So: the smallest thing that works, written by exactly one caller.
 */
const val RETURN_URL_ATTRIBUTE: String = "dev.kanso.oauth.RETURN_URL"

/**
 * A URL this application is willing to put in a `Location` header and let a member
 * follow — and the reason it is a file of its own is that it is reflected.
 *
 * Two shapes pass, the two the consent flow actually produces: a path inside this
 * application, or an absolute URL whose **parsed origin** is the API's own. Parsed, never
 * a prefix. `https://api.example.com.evil.com/` starts with the API's origin as text and
 * `https://api.example.com@evil.com/` reads as it to a person, and a check written about
 * strings says yes to both. Scheme, host and port are what an origin is, so all three are
 * compared and none of them as text.
 *
 * This is `safeNext` in `apps/web` said a second time, in Kotlin, deliberately: the app
 * checks the `next` it reflects out of its URL bar, and the API checks the one it reflects
 * out of the session. Neither substitutes for the other, because they are reached by
 * different halves of the same round trip — a member who types a password never passes
 * through here, and a member who clicks Google never passes through there.
 */
object ReturnUrl {

	private val HTTP_SCHEMES = setOf("http", "https")

	/**
	 * The URL to follow, or `null` for every other string in the world.
	 *
	 * @param origin the API's own origin, as this request sees it. A caller that cannot
	 *   name one honestly should not be calling: an origin that fails to parse refuses
	 *   everything absolute rather than waving it through, because a misconfiguration
	 *   must not open the redirect this function exists to close.
	 */
	fun parse(raw: String?, origin: String): URI? {
		if (raw.isNullOrBlank()) return null

		// Browsers strip tab, CR and LF out of a URL before resolving it, so `/<tab>/evil.com`
		// navigates to `//evil.com`. Stripping them first means this judges the string the
		// browser will follow rather than the one that was written down. A backslash is
		// refused outright instead: browsers normalise it to `/`, so `/\evil.com` is
		// protocol-relative to them, and relying on `URI` to reject it is relying on a
		// parser staying strict.
		val value = raw.replace(STRIPPED, "")
		if (value.contains('\\')) return null

		val uri = try {
			// `URI(String)` throws the *checked* URISyntaxException; `URI.create` throws
			// IllegalArgumentException. `RedirectUriPolicy` records the same trap.
			URI(value)
		} catch (_: URISyntaxException) {
			return null
		}

		// Opaque means there is no authority to reason about — `javascript:`, `data:`,
		// `mailto:`. Refusing them here keeps the rest of this function about origins.
		if (uri.isOpaque || uri.rawUserInfo != null) return null

		if (!uri.isAbsolute) {
			// A relative reference is only inside this application if it is rooted and has
			// no authority of its own: `URI` parses `//evil.com/x` as host `evil.com` with
			// no scheme, which is exactly the protocol-relative escape, so a parsed host
			// here is disqualifying however the string was spelled.
			return if (uri.host == null && uri.authority == null && value.startsWith("/")) uri else null
		}

		if (uri.scheme?.lowercase() !in HTTP_SCHEMES) return null
		val here = try {
			URI(origin.trim())
		} catch (_: URISyntaxException) {
			return null
		}

		val sameOrigin = uri.scheme.equals(here.scheme, ignoreCase = true) &&
			uri.host != null && uri.host.equals(here.host, ignoreCase = true) &&
			effectivePort(uri) == effectivePort(here)
		return if (sameOrigin) uri else null
	}

	/** `https://host` and `https://host:443` are one origin written two ways. */
	private fun effectivePort(uri: URI): Int =
		if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

	private val STRIPPED = Regex("[\\t\\n\\r]")
}

package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one function in the API whose output is written into a `Location` header a member
 * follows without reading, immediately after typing their password or coming back from
 * Google. It is `safeNext` in `apps/web` said again in Kotlin, and it is said twice on
 * purpose: the app checks what it reflects, and so does the API.
 *
 * Every case here is a way of writing "somewhere else" that reads as "here" — a
 * protocol-relative path, a host that has the API's origin as a *prefix*, a `user@host`
 * that reads as one host and resolves to another. A check that compares text says yes to
 * all three, which is why this one compares parsed scheme, host and port.
 */
class ReturnUrlTest {

	private val origin = "https://api.example.com"

	private fun accepted(raw: String, expected: String = raw) =
		assertEquals(expected, ReturnUrl.parse(raw, origin)?.toString(), "$raw should be followed")

	private fun refused(raw: String?) =
		assertNull(ReturnUrl.parse(raw, origin), "$raw must not be reflected into a redirect")

	@Test
	fun `a path inside this application is followed`() {
		accepted("/oauth/consent?client_id=claude-code&scope=kanso%3Aread&state=s")
		accepted("/settings")
		accepted("/")
	}

	@Test
	fun `an absolute URL on the API's own origin is followed`() {
		accepted("https://api.example.com/oauth/consent?state=s")
		// The default port is the same origin written a second way; a comparison on text
		// would call these two different places.
		accepted("https://api.example.com:443/oauth/consent")
		assertEquals(
			"/oauth/consent",
			ReturnUrl.parse("https://API.EXAMPLE.COM/oauth/consent", origin)?.path,
			"a host differs only by case to a string, never to DNS",
		)
	}

	@Test
	fun `another origin is refused however much it reads like this one`() {
		refused("https://evil.example.com/")
		// Starts with the API's origin as text. This is the whole reason for parsing.
		refused("https://api.example.com.evil.example.com/oauth/consent")
		refused("https://api.example.com@evil.example.com/oauth/consent")
		// Right host, wrong scheme and wrong port: an origin is all three together.
		refused("http://api.example.com/oauth/consent")
		refused("https://api.example.com:8443/oauth/consent")
	}

	@Test
	fun `a path that is really an authority is refused`() {
		// Protocol-relative: the browser resolves `//evil.example.com` against the current
		// scheme and leaves the application entirely.
		refused("//evil.example.com/")
		refused("/\\evil.example.com/")
		refused("\\\\evil.example.com/")
		// Browsers strip tab and newline before resolving, so this is `//evil.example.com`
		// wearing a disguise.
		refused("/\t/evil.example.com/")
		refused("/\n/evil.example.com/")
	}

	@Test
	fun `a scheme that is not http is refused`() {
		refused("javascript:alert(1)")
		refused("data:text/html,<script>alert(1)</script>")
		refused("mailto:someone@example.com")
		refused("ftp://api.example.com/")
	}

	@Test
	fun `nothing at all is refused rather than defaulted`() {
		refused(null)
		refused("")
		refused("   ")
		// Neither shape: no leading slash, and no scheme to check an origin against.
		refused("evil.example.com/oauth/consent")
	}

	@Test
	fun `a misconfigured origin closes the door rather than opening it`() {
		assertNull(
			ReturnUrl.parse("https://api.example.com/oauth/consent", "not a url"),
			"an origin this function cannot parse must refuse everything absolute, not allow it",
		)
	}
}

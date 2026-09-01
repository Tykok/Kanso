package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security of an unauthenticated, row-creating endpoint, in one file.
 *
 * `/connect/register` accepts a `POST` from anybody and writes a client. **A
 * registration is only ever as dangerous as where it can send a code**, so this policy is
 * the whole of that endpoint's defence, and it is written first and reviewed hardest.
 *
 * Every comparison is on **parsed** `URI` components. A prefix comparison on strings is
 * how open redirects are built: `https://claude.ai.evil.com/cb` starts with
 * `https://claude.ai`, and a policy that reasons about text rather than about hosts says
 * yes to it.
 */
class RedirectUriPolicyTest {

	private val policy = RedirectUriPolicy(setOf("claude.ai", "claude.com"))

	private fun allowed(uri: String) = assertTrue(policy.isAllowed(uri), "$uri should be allowed")

	private fun refused(uri: String) = assertFalse(policy.isAllowed(uri), "$uri must be refused")

	@Test
	fun `loopback is allowed on any port, because a CLI picks one at runtime`() {
		allowed("http://127.0.0.1:9999/callback")
		allowed("http://localhost:1234/cb")
		allowed("http://127.0.0.1:1/cb")
		allowed("http://[::1]:8080/cb")
	}

	@Test
	fun `the configured hosts are allowed over https only`() {
		allowed("https://claude.ai/api/mcp/auth_callback")
		allowed("https://claude.com/api/mcp/auth_callback")
		refused("http://claude.ai/api/mcp/auth_callback")
	}

	@Test
	fun `a lookalike host is refused`() {
		// The three shapes a prefix comparison gets wrong, and the reason this policy
		// parses instead of comparing text.
		refused("http://127.0.0.1.evil.com/cb")
		refused("https://claude.ai.evil.com/cb")
		refused("https://claude.aievil.com/cb")
	}

	@Test
	fun `a userinfo trick is refused`() {
		// The authority here is evil.com; "claude.ai" is a username. A reader skims this
		// as the allowed host, which is exactly why it is refused twice over — on the
		// userinfo being present at all, and on the host not matching.
		refused("https://claude.ai@evil.com/cb")
		refused("http://127.0.0.1@evil.com/cb")
	}

	@Test
	fun `a scheme that is not a redirect at all is refused`() {
		refused("javascript:alert(1)")
		refused("data:text/html,<script>alert(1)</script>")
		refused("mailto:someone@example.com")
		refused("file:///etc/passwd")
	}

	@Test
	fun `a non-loopback http origin is refused`() {
		refused("http://example.com/cb")
		refused("http://192.168.1.10/cb")
	}

	@Test
	fun `a string that merely starts with an allowed origin is not thereby allowed`() {
		// The assertion the plan singles out. Everything here contains an allowed origin
		// as a substring and none of them is one.
		refused("https://evil.com/https://claude.ai/cb")
		refused("https://evil.com/?next=https://claude.ai/cb")
		refused("https://evil.com#https://claude.ai/cb")
	}

	@Test
	fun `a fragment is refused, because a redirect uri has none`() {
		// RFC 6749 is explicit, and a fragment never reaches a server anyway — so one
		// here means the client is confused about what it is registering.
		refused("https://claude.ai/cb#anything")
		refused("http://127.0.0.1:9999/cb#anything")
	}

	@Test
	fun `something that is not a URI at all is refused rather than throwing`() {
		refused("")
		refused("   ")
		refused("not a uri")
		refused("http://")
		refused("/relative/only")
	}
}

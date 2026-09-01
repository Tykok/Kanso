package dev.kanso.oauth

import java.net.URI
import java.net.URISyntaxException

/**
 * Where a self-registered client may be sent a code — and nowhere else.
 *
 * `/connect/register` is unauthenticated and creates rows, which makes it the most
 * abusable surface this branch adds. This file is its defence, and the reasoning is one
 * sentence: **a registration is only ever as dangerous as where it can send a code.** A
 * client that cannot name an attacker's callback cannot hand an attacker an
 * authorisation, however freely it was created.
 *
 * Everything is compared on **parsed** components. String prefixes are how open
 * redirects get built — `https://claude.ai.evil.com/cb` starts with `https://claude.ai`,
 * and a policy that reasons about text says yes to it. Host equality here is exact:
 * there is no suffix rule, not even a careful one, because a suffix rule is the thing
 * that has to be got right forever.
 *
 * @param allowedHosts the hosted clients' hosts, over https. Configuration rather than a
 *   constant: which origins Anthropic's clients call back on is not Kanso's fact to
 *   hard-code, and an instance serving a different client needs to add one without a
 *   rebuild.
 */
class RedirectUriPolicy(private val allowedHosts: Set<String>) {

	private val loopback = setOf("127.0.0.1", "localhost", "[::1]", "::1")

	fun isAllowed(raw: String): Boolean {
		val uri = try {
			// `URI(String)` throws the *checked* URISyntaxException; it is `URI.create`
			// that throws IllegalArgumentException. Catching the wrong one lets
			// `data:text/html,<script>` escape as an exception rather than a refusal.
			URI(raw.trim())
		} catch (_: URISyntaxException) {
			return false
		}

		// Opaque means there is no authority to check — `javascript:`, `data:`, `mailto:`.
		// Refusing these before anything else is what keeps the rest of this function
		// about hosts rather than about schemes.
		if (uri.isOpaque || !uri.isAbsolute) return false

		// Present at all is enough to refuse. `https://claude.ai@evil.com` reads as the
		// allowed host to a person skimming it, and the host is evil.com.
		if (uri.rawUserInfo != null) return false

		// RFC 6749 forbids one, and a fragment never reaches a server — so a client that
		// registers one is confused about what a redirect URI is.
		if (uri.rawFragment != null) return false

		val host = uri.host?.lowercase() ?: return false
		val scheme = uri.scheme?.lowercase() ?: return false

		return when {
			// Any port: a native client binds one at runtime and cannot know it in
			// advance. This is RFC 8252's prescription, and the port is the only thing
			// loopback gets to vary.
			host in loopback -> scheme == "http" || scheme == "https"
			// Exact host, https, and the default port. A hosted client on a stray port is
			// not a hosted client.
			host in allowedHosts -> scheme == "https" && (uri.port == -1 || uri.port == 443)
			else -> false
		}
	}
}

package dev.kanso.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Dev mode: the caller names themselves with `X-Kanso-User: someone@example.com`
 * and the user is created on the spot. Nothing is verified — this exists so tests
 * and local hacking don't need an OAuth round trip.
 *
 * Only ever wired in when `kanso.auth.mode` says `dev` in so many words. An
 * instance with nothing configured used to fall back here; it now goes to the
 * first-run wizard instead, because a default that silently trusts a header is a
 * default nobody chose.
 *
 * There is one request in the application a browser **cannot** put a header on, and
 * `KAN-25` is what found it: the WebSocket handshake. `new WebSocket(url)` takes a URL and
 * nothing else — no headers, by specification — so every socket in dev mode arrived here
 * bare and was authenticated as [defaultEmail]. Under a cookie login that is invisible,
 * because the cookie authenticates the handshake on its own; in dev mode it meant every
 * connected browser was the *same person*. Nothing depended on that until presence, which
 * is derived from the socket rather than from a request: two people on one document showed
 * as one viewer called "dev".
 *
 * So the query parameter below, and it is deliberately narrow. It is read only when the
 * header is absent, only by this class — which exists only in dev mode — and it asserts
 * exactly what the header asserts, unverified, which is this whole file's contract. It
 * grants nothing a header could not already grant to the same caller on the same instance.
 * `SecurityConfig` is untouched: under `oidc` or a password login this class is not in the
 * chain at all, the session cookie authenticates the handshake as it always did, and the
 * parameter means nothing to anybody.
 */
class DevAuthenticationFilter(
	private val provisioning: UserProvisioning,
	private val defaultEmail: String = "dev@kanso.local",
) : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		if (SecurityContextHolder.getContext().authentication == null) {
			// Header first, always. The parameter is the fallback for the one caller that has
			// no way to send one, not an alternative anybody else should reach for.
			val email = request.getHeader(HEADER)?.takeIf { it.isNotBlank() }
				?: request.getParameter(PARAM)?.takeIf { it.isNotBlank() }
				?: defaultEmail
			val user = provisioning.findOrCreateByEmail(email)
			val principal = KansoDevUser(user.id, user.email, user.displayName)
			SecurityContextHolder.getContext().authentication =
				UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
		}
		filterChain.doFilter(request, response)
	}

	companion object {
		const val HEADER = "X-Kanso-User"

		/** For the WebSocket handshake alone — see the class doc. `realtime.ts` sends it. */
		const val PARAM = "devUser"
	}
}

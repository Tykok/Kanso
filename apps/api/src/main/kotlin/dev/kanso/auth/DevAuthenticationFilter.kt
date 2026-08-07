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
			val email = request.getHeader(HEADER)?.takeIf { it.isNotBlank() } ?: defaultEmail
			val user = provisioning.findOrCreateByEmail(email)
			val principal = KansoDevUser(user.id, user.email, user.displayName)
			SecurityContextHolder.getContext().authentication =
				UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
		}
		filterChain.doFilter(request, response)
	}

	companion object {
		const val HEADER = "X-Kanso-User"
	}
}

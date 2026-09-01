package dev.kanso.oauth

import dev.kanso.auth.KansoAuthenticatedUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * On the authorisation server's chain only: leave the user id, drop the principal.
 *
 * The authorisation endpoint records whatever `Authentication` it finds, and the library
 * persists that record as JSON. Kanso's own principals cannot make that round trip:
 * Jackson 3's `PolymorphicTypeValidator` refuses `dev.kanso.auth.KansoDevUser` on the way
 * back, so the row saves and the *next* request — the code exchange — fails with an empty
 * body. Invisible with an in-memory store, which is why this is a filter and not a
 * comment.
 *
 * Leaving only the user id also fixes a second thing. Every Kanso principal implements
 * `getName()` as the display name (`Principals.kt`), so `principal_name` would hold
 * "Elie" — neither unique nor stable, and it is the key `McpBearerFilter` maps a token
 * back through.
 *
 * The alternative was a Jackson mixin per principal class, which would make a persisted
 * format out of three internal classes and require keeping them in step forever.
 *
 * Registered on this chain and no other. Kanso's own chain must keep its rich principal,
 * because `CurrentUser` reads `kansoEmail` and the display name off it.
 */
class AgentPrincipalFilter : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		chain: FilterChain,
	) {
		val authentication = SecurityContextHolder.getContext().authentication
		val principal = authentication?.principal
		if (principal is KansoAuthenticatedUser) {
			// A fresh context, set on the holder — never an assignment into the one already
			// there. That object *is* the member's session:
			// `HttpSessionSecurityContextRepository` hands back the stored instance rather
			// than a copy, and `SecurityContextHolderFilter` neither copies it nor saves
			// it. Writing through it therefore rewrites the session, and the member's next
			// request to any Kanso endpoint arrives holding a bare `String` — authenticated
			// enough for `anyRequest().authenticated()`, unreadable to `CurrentUser`, so
			// every endpoint refuses including the Settings screen this grant is revoked
			// from. A link to any path on this chain is a top-level navigation, so `Lax`
			// sends the cookie: whoever clicked it would have been locked out looking
			// signed in.
			SecurityContextHolder.setContext(
				SecurityContextHolder.createEmptyContext().apply {
					this.authentication = UsernamePasswordAuthenticationToken(
						principal.kansoUserId.toString(),
						null,
						authentication.authorities,
					)
				},
			)
		}
		chain.doFilter(request, response)
	}
}

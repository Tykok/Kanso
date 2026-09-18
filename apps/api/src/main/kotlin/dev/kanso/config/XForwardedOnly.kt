package dev.kanso.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import java.util.Collections
import java.util.Enumeration

/**
 * `ForwardedHeaderFilter`, with the two headers nothing in front of this JVM writes taken
 * away from it before it reads them.
 *
 * The filter believes two families and prefers the wrong one. `ForwardedHeaderUtils`
 * reads RFC 7239 `Forwarded` *first* — `parseForwardedFor` returns on it before it looks at
 * `X-Forwarded-For`, and `adaptFromForwardedHeaders` puts the whole
 * `X-Forwarded-Proto`/`-Host`/`-Port` block in an `else`. Caddy writes only the `X-` family
 * and `docker/Caddyfile` sanitises only that family, so one `Forwarded:` header from any
 * caller outranked everything the edge had just set. It chose `getRemoteAddr()`, which is
 * what `RegistrationRateLimit`, `LoginAttemptLog` and `PublicController` each bucket on —
 * and it chose this instance's own origin, which is what `McpResource.fromCurrentRequest`
 * checks an RFC 8707 audience against and what the authorization server publishes as its
 * issuer, neither of which is a caller's to name.
 *
 * `X-Forwarded-Prefix` travels with them and decides the context path. Nothing here sets it
 * either, so it goes the same way.
 *
 * A wrapper and not `ForwardedHeaderFilter(false)`, which is the obvious answer and is not
 * available: that constructor arrived in 6.1.29/7.0.9 and this module resolves spring-web
 * 7.0.8 through the Boot BOM. Hiding the headers holds on either version and needs no
 * opinion about the BOM — and it is the same thing Caddy does, done again where it cannot be
 * skipped by a topology that has no Caddy in it. `ForwardedHeaderFilterCustomizer` is not a
 * way to do this: it reaches `setRemoveOnly` and `setRelativeRedirects` and nothing else.
 *
 * Wrapping inside `doFilterInternal` rather than ahead of the filter is what makes the order
 * unarguable. Boot registers its own at `HIGHEST_PRECEDENCE`, so there is no "before" to
 * register into; here the hiding happens on the same call stack, before `super` has read a
 * thing.
 */
class XForwardedOnly : ForwardedHeaderFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) = super.doFilterInternal(Hidden(request), response, filterChain)

	/**
	 * All three accessors, because the filter reads through `HttpHeaders` built from
	 * `getHeaderNames()`: overriding `getHeader` alone would leave the name enumerable and
	 * its value readable, which is the half-fix that looks like a fix.
	 *
	 * Case-insensitively, because a header name is, and a caller choosing `forwarded:` over
	 * `Forwarded:` is the first thing anybody would try.
	 */
	private class Hidden(request: HttpServletRequest) : HttpServletRequestWrapper(request) {

		override fun getHeader(name: String): String? =
			if (hidden(name)) null else super.getHeader(name)

		override fun getHeaders(name: String): Enumeration<String> =
			if (hidden(name)) Collections.emptyEnumeration() else super.getHeaders(name)

		override fun getHeaderNames(): Enumeration<String> =
			Collections.enumeration(super.getHeaderNames().toList().filterNot { hidden(it) })

		private fun hidden(name: String) = HIDDEN.any { it.equals(name, ignoreCase = true) }
	}

	private companion object {
		val HIDDEN = setOf("Forwarded", "X-Forwarded-Prefix")
	}
}

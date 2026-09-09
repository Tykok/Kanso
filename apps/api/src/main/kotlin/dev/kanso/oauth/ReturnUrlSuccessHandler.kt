package dev.kanso.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import java.time.Instant

/**
 * Where a member lands when a provider hands them back.
 *
 * The fixed handler this replaces sent everyone to the app root, which is right for
 * almost every sign-in and wrong for the one that matters here. `claude mcp add` opens
 * `/oauth2/authorize`; with no session, [ConsentController] redirects to the app's login
 * screen carrying the way back. A member who types a password returns through `apps/web`,
 * which still holds that value in its own URL. A member who clicks Google does not — the
 * round trip leaves the app entirely and comes back to the API's callback, which has
 * never heard of the consent page. So the way back is stashed in the session on the way
 * out, and read here on the way in.
 *
 * The session works because both ends are the API's own origin: the cookie set when the
 * consent page redirected is sent again with the top-level navigation to
 * `/oauth2/authorization/{provider}` and again with the provider's callback, `SameSite=Lax`
 * allowing both. Session-fixation protection is the default `changeSessionId`, which keeps
 * attributes across the authentication.
 *
 * Subclassing rather than implementing, and overriding only where the target is chosen:
 * every sign-in through a provider ends up in this method, and a member who cannot get in
 * at all is a far worse outcome than a member whose agent authorisation was abandoned.
 * With nothing stashed, `super` is the previous behaviour unchanged.
 *
 * The three-argument `determineTargetUrl` rather than the two-argument one it delegates to:
 * either would be dispatched, since the pair is virtual all the way down, and this one is
 * the one that carries the `Authentication` — a later reason to send a member somewhere
 * else is far likelier to be about who they are than about the callback they arrived on.
 */
class ReturnUrlSuccessHandler(defaultTarget: String) : SimpleUrlAuthenticationSuccessHandler(defaultTarget) {

	override fun determineTargetUrl(
		request: HttpServletRequest,
		response: HttpServletResponse,
		authentication: Authentication?,
	): String {
		val default = { super.determineTargetUrl(request, response, authentication) }
		// `false`: a sign-in that never went through the consent page has no session to
		// consult, and creating one here to find nothing in it would be its own small bug.
		val session = request.getSession(false) ?: return default()

		// Consumed whether or not it survives what follows. A return address that outlives
		// its own flow is a trap for the *next* sign-in in this session, which would be
		// silently redirected somewhere it never asked to go.
		val stashed = session.getAttribute(RETURN_URL_ATTRIBUTE) as? ReturnAddress
		session.removeAttribute(RETURN_URL_ATTRIBUTE)
		if (stashed == null || stashed.expiredAt(Instant.now())) return default()

		// Validated on the way out and not only on the way in. Today the only writer is
		// `ConsentController`, which built the URL rather than copying one; the check is
		// here because this value goes into a `Location` header a member follows without
		// reading, and the day it acquires a second writer nothing else will notice.
		return ReturnUrl.parse(stashed.url, originOf(request))?.toString() ?: default()
	}

	/**
	 * The API's own origin as this request saw it — always with a port, because
	 * [ReturnUrl] normalises the default ones on both sides of the comparison.
	 *
	 * Read off the request in hand rather than through `ServletUriComponentsBuilder`, and
	 * **not** because the holder is empty here: this file used to say the
	 * `DispatcherServlet` populates it, which is false. Boot's
	 * `WebMvcAutoConfigurationAdapter` registers `OrderedRequestContextFilter` at order
	 * `-105`, ahead of the security chain at `-100`, so `RequestContextHolder` is populated
	 * throughout the chain. The correction matters beyond this comment: `ResourceValidator`
	 * defaults its `expected` to `McpResource::fromCurrentRequest`, which calls
	 * `fromCurrentContextPath()` from inside an authentication provider on that same chain
	 * — it works, and acting on the sentence that was here would have broken the
	 * authorise-time half of the audience check. What is left is a preference rather than a
	 * necessity: a handler handed a request should read that request.
	 *
	 * These three reflect `X-Forwarded-*` only where forwarded headers are honoured, and
	 * `application.yml` now honours them: `server.forward-headers-strategy: framework`
	 * registers Boot's `ForwardedHeaderFilter` at `Ordered.HIGHEST_PRECEDENCE`, ahead even of
	 * the `-105` above, so the request reaching this method carries the browser's scheme and
	 * host rather than the proxy hop's. That line arrived with the distribution image, which
	 * terminates TLS in Caddy and speaks plain http to this process over the loopback — the
	 * deployment this comment used to argue for and can now describe.
	 *
	 * It stays written down because what it prevents is silent. `ConsentController` derives
	 * the stash from the same request this method derives its origin from, so *unwrapped the
	 * two agree*: both say `http`, the comparison passes, and the member is redirected from
	 * an `https` page to `http://…/oauth/consent` with nothing failing to announce it. A
	 * scheme downgrade rather than an open redirect, and invisible to any test that calls
	 * either half directly — `ForwardedHeadersTest` therefore asserts it through the filter
	 * chain, which is the only place the property exists at all.
	 */
	private fun originOf(request: HttpServletRequest): String =
		"${request.scheme}://${request.serverName}:${request.serverPort}"
}

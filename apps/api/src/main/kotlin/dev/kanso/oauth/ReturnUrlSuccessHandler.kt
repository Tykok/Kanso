package dev.kanso.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler

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

		// Consumed whether or not it survives validation. A return address that outlives
		// its own flow is a trap for the *next* sign-in in this session, which would be
		// silently redirected somewhere it never asked to go.
		val stashed = session.getAttribute(RETURN_URL_ATTRIBUTE) as? String
		session.removeAttribute(RETURN_URL_ATTRIBUTE)

		// Validated on the way out and not only on the way in. Today the only writer is
		// `ConsentController`, which built the URL rather than copying one; the check is
		// here because this value goes into a `Location` header a member follows without
		// reading, and the day it acquires a second writer nothing else will notice.
		return ReturnUrl.parse(stashed, originOf(request))?.toString() ?: default()
	}

	/**
	 * The API's own origin as this request saw it — always with a port, because
	 * [ReturnUrl] normalises the default ones on both sides of the comparison. Read off
	 * the request rather than from configuration: `ServletUriComponentsBuilder` cannot be
	 * used here because `RequestContextHolder` is populated by the `DispatcherServlet`,
	 * which runs *after* the security filter chain this handler lives in. Behind a reverse
	 * proxy these three reflect `X-Forwarded-*` only if forwarded headers are honoured —
	 * the same deployment note `ConsentController` carries, and the same one that decides
	 * what that controller wrote into the session in the first place.
	 */
	private fun originOf(request: HttpServletRequest): String =
		"${request.scheme}://${request.serverName}:${request.serverPort}"
}

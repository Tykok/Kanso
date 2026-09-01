package dev.kanso.oauth

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.service.BadRequestException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * The HTTP around [ConsentPage] — and nothing else, so the page stays testable as a
 * string.
 *
 * `@Controller` rather than `@RestController`: everything else under `dev.kanso` answers
 * a `fetch` with JSON, and this one answers a browser with a document. The three
 * parameters are the library's, named as it names them — it is `OAuth2AuthorizationEndpointFilter`
 * that redirects here, and renaming them would mean translating a contract we do not own.
 *
 * Three refusals, all of them before anything is rendered: an unregistered client, an
 * unknown scope, and an empty ask. A page that asked a member to approve a blank name or
 * an unrecognised permission would be worse than an error, because they would approve it.
 */
@Controller
class ConsentController(
	private val currentUser: CurrentUser,
	private val clients: RegisteredClientRepository,
	private val props: KansoProperties,
	private val settings: AuthorizationServerSettings,
) {

	@GetMapping(CONSENT_PAGE, produces = [MediaType.TEXT_HTML_VALUE])
	@ResponseBody
	fun consent(
		@RequestParam("client_id") clientId: String,
		@RequestParam("scope") scope: String,
		@RequestParam("state") state: String,
	): ResponseEntity<String> {
		// The client is looked up *before* the anonymous branch, and the order is the
		// point. `signIn` mints a session and plants a return address in it; leaving that
		// reachable for any `client_id` at all meant an attacker could register a client
		// through the open `/connect/register`, get a member to open one consent URL, and
		// have the member's *next* provider sign-in land on a consent screen for that
		// client — arriving right after they typed their own credentials, which is when a
		// consent prompt looks most legitimate. The authorisation endpoint only ever
		// redirects here for a registered client, so nothing legitimate is refused by
		// asking first.
		val client = clients.findByClientId(clientId)
			?: throw BadRequestException("No application is registered as '$clientId'")

		// `principalOrNull`, not `require`: arriving here with no session is the normal
		// first step of the flow, not a violation, and the answer to it is a round trip
		// through the login screen rather than a 403.
		val member = currentUser.principalOrNull() ?: return signIn(clientId, scope, state)

		// Space-delimited by RFC 6749, and `distinct` because a client may repeat one —
		// the same permission listed twice reads as two different asks.
		val scopes = scope.trim().split(WHITESPACE).filter { it.isNotBlank() }.distinct()
		if (scopes.isEmpty()) throw BadRequestException("The application asked for no permission at all")
		// Only to make it throw here rather than mid-render: `prose` refuses a scope this
		// instance does not grant, and `ApiExceptionHandler` turns that into a 400.
		scopes.forEach { OAuthScopes.prose(it) }

		return ResponseEntity.ok()
			// The charset is stated rather than defaulted: the copy has em dashes in it,
			// and a browser guessing latin-1 renders them as mojibake on the one page
			// where the wording is the product.
			.contentType(MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
				// The two facts a member can actually weigh, and neither is the client's
				// own text: where a code would be sent, and how old the registration is.
				// `/connect/register` is open, so "Claude Code" on this screen means only
				// that somebody typed it — see [ClientEvidence].
				.body(
					ConsentPage.render(
						clientName = client.clientName,
						email = member.kansoEmail,
						scopes = scopes,
						clientId = clientId,
						state = state,
						// The library's own path, read off the settings bean rather than
						// written here: a client finds it in the metadata document, so a
						// second copy of the string is a copy that can drift from what the
						// server serves. With the context path in front of it — see
						// [ConsentPage.render]'s note on the third instance of that bug.
						authorizeAction = ServletUriComponentsBuilder.fromCurrentContextPath()
							.path(settings.authorizationEndpoint)
							.build()
							// `UriComponents.getPath()` is @Nullable and cannot be null for a
							// builder given a path; the fallback is the un-prefixed endpoint,
							// which is what this page did before and is right at the root.
							.path ?: settings.authorizationEndpoint,
						evidence = ClientEvidence.of(
							redirectUris = client.redirectUris,
							registeredAt = client.clientIdIssuedAt,
							now = Instant.now(),
						),
					),
				)
	}

	/**
	 * To the app's login screen, with the way back.
	 *
	 * `next` is hand-encoded rather than handed to `queryParam(...).encode()`, and that is
	 * not a style choice: `&` and `=` are *legal* query characters, so `encode()` leaves
	 * them alone and the nested return URL splits into siblings of `next` — the app would
	 * read it truncated at `client_id` and send the member back to a consent page with no
	 * client. `NotionConnectController.back()` records the opposite half of the same
	 * lesson, where the missing `encode()` produced an illegal URI.
	 */
	private fun signIn(clientId: String, scope: String, state: String): ResponseEntity<String> {
		val back = returnUrl(clientId, scope, state)

		// Said twice, because the two ways back are not the same way. `next` is for the
		// member who types a password: they return through `apps/web`, which still holds
		// it. The session attribute is for the member who clicks a provider button: that
		// round trip leaves the app and comes back to `/login/oauth2/code/{provider}` on
		// the API, where nothing in the URL remembers this page. `true` creates the
		// session on purpose — there is none yet, and the cookie it sets is the only
		// thread that survives a trip through Google. It carries an expiry because a way
		// back that outlives its own flow is a way of redirecting somebody else's
		// sign-in; see [RETURN_URL_TTL].
		currentRequest().getSession(true)
			.setAttribute(RETURN_URL_ATTRIBUTE, ReturnAddress.validFrom(back, Instant.now()))

		val next = URLEncoder.encode(back, StandardCharsets.UTF_8)
		val target = "${props.webOrigin.trimEnd('/')}/login?next=$next"
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
	}

	/**
	 * The same request `ServletUriComponentsBuilder.fromCurrentContextPath()` reads, asked
	 * for directly because a session is not a URL component.
	 */
	private fun currentRequest(): HttpServletRequest =
		(RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

	/**
	 * This page's own URL — **rebuilt** from the three parameters it declares, never
	 * copied off the query string. Anything else a caller appended is not this page's
	 * business to carry through a login screen and hand back to a browser.
	 *
	 * The check below is a shape check and is deliberately not sold as more than one: the
	 * origin comes from the request that arrived, so comparing the result against that
	 * same request would prove nothing. What it does catch is a URL that has stopped
	 * being *this page* — another scheme, a `user@host` that reads as one host and
	 * resolves to another, a path that is not the consent page. The non-circular half of
	 * the defence is `safeNext` in `apps/web`, which knows the API's origin from
	 * configuration and refuses a `next` on any other; neither check substitutes for the
	 * other. Behind a reverse proxy this needs forwarded headers to be honoured, the same
	 * deployment note `NotionConnectController.callbackUri()` carries.
	 */
	private fun returnUrl(clientId: String, scope: String, state: String): String {
		val url = ServletUriComponentsBuilder.fromCurrentContextPath()
			.path(CONSENT_PAGE)
			.queryParam("client_id", clientId)
			.queryParam("scope", scope)
			.queryParam("state", state)
			.build()
			.encode()
			.toUriString()

		// `ReturnUrl` rather than a second check written here: the same string is read
		// back out of the session by `ReturnUrlSuccessHandler` after a provider round
		// trip, and two checks of one value are two chances to disagree. Origin against
		// itself is the tautology the paragraph above admits to; the path is not.
		val origin = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
		// The path *as this deployment serves it*, context path and all. `CONSENT_PAGE`
		// alone is only the same string when the application is mounted at the root: under
		// `/kanso` the URL above is `/kanso/oauth/consent`, so the comparison refused every
		// request such an instance ever made — a 400 in place of the whole first-run flow,
		// from a check written to catch a URL that had stopped being this page.
		val here = ServletUriComponentsBuilder.fromCurrentContextPath().path(CONSENT_PAGE).build().path
		val uri = ReturnUrl.parse(url, origin)
		if (uri == null || uri.path != here) {
			throw BadRequestException("This instance cannot build a usable return address for the consent page")
		}
		return url
	}

	private companion object {
		val WHITESPACE = Regex("\\s+")
	}
}

package dev.kanso.oauth

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.service.BadRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
) {

	@GetMapping(CONSENT_PAGE, produces = [MediaType.TEXT_HTML_VALUE])
	@ResponseBody
	fun consent(
		@RequestParam("client_id") clientId: String,
		@RequestParam("scope") scope: String,
		@RequestParam("state") state: String,
	): ResponseEntity<String> {
		// `principalOrNull`, not `require`: arriving here with no session is the normal
		// first step of the flow, not a violation, and the answer to it is a round trip
		// through the login screen rather than a 403.
		val member = currentUser.principalOrNull() ?: return signIn(clientId, scope, state)

		val client = clients.findByClientId(clientId)
			?: throw BadRequestException("No application is registered as '$clientId'")

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
			.body(ConsentPage.render(client.clientName, member.kansoEmail, scopes, clientId, state))
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
		val next = URLEncoder.encode(returnUrl(clientId, scope, state), StandardCharsets.UTF_8)
		val target = "${props.webOrigin.trimEnd('/')}/login?next=$next"
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
	}

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

		val uri = try {
			URI(url)
		} catch (_: URISyntaxException) {
			throw BadRequestException("This instance cannot build a usable return address for the consent page")
		}
		val refusal = uri.scheme?.lowercase() !in HTTP_SCHEMES || uri.rawUserInfo != null || uri.path != CONSENT_PAGE
		if (refusal) throw BadRequestException("This instance cannot build a usable return address for the consent page")
		return url
	}

	private companion object {
		val WHITESPACE = Regex("\\s+")
		val HTTP_SCHEMES = setOf("http", "https")
	}
}

package dev.kanso.mcp

import dev.kanso.auth.KansoAgentUser
import dev.kanso.oauth.DEV_MODE_REFUSAL
import dev.kanso.oauth.OAuthScopes
import dev.kanso.oauth.ResourceParameter
import dev.kanso.repo.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * A bearer token, into the same `User` a session produces.
 *
 * Its own class rather than Spring's `oauth2ResourceServer`, and the reason is the whole
 * integration: the built-in support ends at an `Authentication` holding scopes, and every
 * Kanso service takes a `dev.kanso.domain.User`. The translation is a handful of lines,
 * and they belong somewhere a reader can find them rather than inside a configurer.
 *
 * Runs only on `/api/mcp`. Everything it refuses, it refuses with the challenge from
 * [McpChallenge] — a 401 that does not say where to authorise is a wall, not a door.
 *
 * Constructed by `SecurityConfig`, not a `@Component`, which is how the other two filters
 * in this application are wired and here it is also load-bearing: Boot registers every
 * `Filter` *bean* with the servlet container as well, ahead of the security chain, and
 * `OncePerRequestFilter` would then let the copy inside the chain skip — leaving the
 * principal set on a context that `SecurityContextHolderFilter` has yet to replace.
 *
 * @param authMode taken as an argument rather than gating the class with
 *   `@ConditionalOnExpression`, which is what `AuthorizationServerConfig` does to the
 *   authorisation server's chain. The difference is deliberate: in dev mode this filter is
 *   the *only* thing standing at `/api/mcp`, and it has a refusal to serve. One that
 *   vanished in dev mode would leave the endpoint answering with whatever the session
 *   chain says — which in dev mode is "come in, name yourself in a header".
 */
class McpBearerFilter(
	private val authorizations: OAuth2AuthorizationService,
	private val clients: RegisteredClientRepository,
	private val users: UserRepository,
	private val authMode: String,
) : OncePerRequestFilter() {

	override fun shouldNotFilter(request: HttpServletRequest): Boolean =
		!request.requestURI.startsWith(McpResource.PATH)

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		// Before anything is read off the request, because in dev mode nothing about the
		// request can change the answer.
		if (authMode.equals("dev", ignoreCase = true)) return refuseDevMode(response)

		val presented = request.getHeader(HttpHeaders.AUTHORIZATION)
			?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
			?.substring(BEARER.length)
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return challenge(request, response)

		// A grant this server cannot read back is a refusal, not a 500. The library raises
		// `IllegalArgumentException` when a stored attribute will not deserialise, and a
		// repeated `resource` is one such row: Jackson's allowlist refuses to reconstitute
		// a `String[]`, so the row saves and only fails on the way out. `ResourceValidator`
		// makes that unreachable through the authorisation endpoint, which is the point —
		// a row shaped like that was written by something else, and something else is
		// exactly what is not honoured here. A database that is down throws a
		// `DataAccessException` instead, and still reaches the caller as a 500: that is
		// not the client's fault and must not read as "your token is bad".
		val authorization = try {
			authorizations.findByToken(presented, OAuth2TokenType.ACCESS_TOKEN)
		} catch (unreadable: IllegalArgumentException) {
			logger.warn("Refusing a bearer token whose stored grant will not deserialise", unreadable)
			null
		} ?: return challenge(request, response)

		// Expiry and revocation are one question to the library, and both answers are the
		// same refusal. `isActive` is false the moment a grant is revoked, because the row
		// it reads is the row revocation deletes — which is what makes "no window" true
		// rather than aspirational.
		if (authorization.accessToken?.isActive != true) return challenge(request, response)

		// RFC 8707's second enforcement point. Without the one at the authorisation
		// endpoint a token minted for another resource is accepted here; without this one
		// a token minted for Kanso is accepted anywhere. Neither is worth writing alone.
		if (!Audience.matches(authorization, McpResource.fromRequest(request))) {
			return challenge(request, response)
		}

		// A name that is not a uuid means the grant was written by something other than
		// `AgentPrincipalFilter`'s chain, so it is refused rather than looked up: the one
		// thing we know about it is that we do not know what it is.
		val user = runCatching { UUID.fromString(authorization.principalName) }
			.getOrNull()
			?.let(users::findById)
			?: return challenge(request, response)

		// The public `client_id`, not `registeredClientId`. The latter is the library's
		// surrogate key and means nothing outside its own table; the former is what the
		// client presents, what registration returned to it, and what a member will be
		// shown next to a change it made.
		val clientId = clients.findById(authorization.registeredClientId)?.clientId
			?: return challenge(request, response)

		val principal = KansoAgentUser(
			kansoUserId = user.id,
			kansoEmail = user.email,
			displayName = user.displayName,
			clientId = clientId,
			scopes = authorization.authorizedScopes.orEmpty().toSet(),
		)
		// Set on the holder and never saved to a repository, so no session is created:
		// `SecurityContextHolderFilter` persists nothing on its own, and a token that
		// left a cookie behind would outlive the request that carried it.
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

		filterChain.doFilter(request, response)
	}

	/**
	 * One refusal for every reason, on purpose. Distinguishing "no such token" from
	 * "revoked" from "wrong audience" would tell an attacker which of their guesses was
	 * closer, and tells a legitimate client nothing it can act on — the challenge already
	 * says what to do.
	 */
	private fun challenge(request: HttpServletRequest, response: HttpServletResponse) {
		response.setHeader(
			HttpHeaders.WWW_AUTHENTICATE,
			McpChallenge.header(OAuthScopes.ALL, McpResource.origin(request)),
		)
		response.status = HttpServletResponse.SC_UNAUTHORIZED
	}

	/**
	 * 503 and not 401, which is the only difference that matters here: a 401 carries a
	 * challenge naming an authorisation server, and in dev mode there is none — the client
	 * would set off into a flow that cannot exist and retry forever. Unavailable is the
	 * truth, and the sentence names the variable to change.
	 */
	private fun refuseDevMode(response: HttpServletResponse) {
		response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
		response.contentType = MediaType.TEXT_PLAIN_VALUE
		response.characterEncoding = Charsets.UTF_8.name()
		response.writer.write(DEV_MODE_REFUSAL)
	}

	private companion object {
		const val BEARER = "Bearer "
	}
}

/** RFC 8707's check, as a named thing so its absence would be visible. */
internal object Audience {

	/**
	 * The audience is read, never reconstructed. The library records the authorisation
	 * request it granted, `resource` and all, and that recorded value is the only evidence
	 * of what the member was asked to consent to — rebuilding it from the registered
	 * client or from today's request would be asking the token to vouch for itself.
	 *
	 * A *missing* `resource` is a refusal, and this is the line the whole check hangs on:
	 * a token with no recorded audience is a token good everywhere, so absence cannot be
	 * permission. Two values bind it to neither.
	 */
	fun matches(authorization: OAuth2Authorization, expected: String): Boolean {
		val granted = authorization
			.getAttribute<OAuth2AuthorizationRequest>(OAuth2AuthorizationRequest::class.java.name)
			?.additionalParameters
			?.get(ResourceParameter.NAME)
		val named = ResourceParameter.values(granted)
		return named.size == 1 && named.single() == expected
	}
}

package dev.kanso.tokens

import dev.kanso.auth.KansoTokenUser
import dev.kanso.mcp.McpResource
import dev.kanso.oauth.OAuthScopes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.OffsetDateTime

/**
 * `Authorization: Bearer kanso_pat_…`, into the same `User` a session cookie produces.
 *
 * ## The rule: a Bearer header is this filter's to answer, or nobody's
 *
 * If the header is absent, the request goes on to the cookie path untouched — that is the
 * browser, and it is the common case. If the header is **present**, this filter answers
 * it: with a principal, or with a 401. It never presents a bad token to the chain behind
 * it.
 *
 * That "never" is the point of the whole file, and it is not obvious, because the obvious
 * design is wrong in a way that is invisible. The obvious design tests whether the token
 * looks like one of ours — `ApiTokenSecret.MARKER`, say — and lets anything else fall
 * through. What falls through then reaches `DevAuthenticationFilter`, which names the
 * caller `dev@kanso.local`, **an admin**; or in oidc mode it reaches the session chain,
 * which reads whatever cookie came along for the ride. Either way a caller who presented a
 * credential Kanso rejected is served as somebody, and in the dev-mode case as somebody
 * with every right on the instance. So the test is on the *header*, not on the token's
 * shape: a caller who says "Bearer" has told us how they wish to be identified, and the
 * only honest answers are yes and no.
 *
 * The four ways that answer is no — a malformed token, an unknown one, one revoked a
 * second ago, and a valid one whose owner has been deactivated — are one refusal here, and
 * `ApiTokenLeakTest` pins each of them separately.
 *
 * ## Why it cannot simply step aside when somebody is already authenticated
 *
 * The tempting shortcut is `if (SecurityContextHolder.getContext().authentication != null)
 * return chain.doFilter(...)`, and it is a hole rather than an optimisation.
 * `SecurityContextHolderFilter` runs *before* `UsernamePasswordAuthenticationFilter` and
 * therefore before this filter, so a request carrying a session cookie arrives here with
 * its authentication already loaded. The shortcut would hand every request that has both a
 * cookie and a bad Bearer straight to the cookie's identity — which is the exact
 * fall-through described above, reintroduced by a line that reads like a fast path.
 *
 * ## Not on `/api/mcp`
 *
 * That door is `McpBearerFilter`'s, and it opens onto an OAuth2 grant with a consent
 * screen, an audience and a `client_id` the activity feed records. Two filters both
 * claiming the `Authorization` header on one path is one of them refusing the other's
 * tokens — concretely: `McpBearerFilter` authenticates a valid OAuth token and chains, and
 * this filter would then look that same token up in `api_tokens`, not find it, and answer
 * 401. So the paths are partitioned rather than ordered, and between the two
 * `shouldNotFilter` implementations every request belongs to exactly one of them.
 *
 * An API token therefore does *not* work against `/api/mcp` today. That is deliberate and
 * not a gap left open: MCP's door is interactive by design and already exists, and this
 * ticket's consumers — outbound webhooks, the GitHub push, a CLI — are REST callers.
 *
 * ## Both auth modes
 *
 * Unlike `McpBearerFilter`, this filter is not gated on `kanso.auth.mode` and has no
 * dev-mode refusal, because it needs no authorisation server: a token is a row in a table
 * this instance wrote. Wiring it in dev mode is also what *closes* the dev-mode
 * fall-through above, so gating it off there would remove the guard from the only mode
 * where the fall-through hands out an admin.
 *
 * Constructed by `SecurityConfig` rather than annotated `@Component`, for the reason
 * `McpBearerFilter` records at length: Boot registers a `Filter` *bean* with the servlet
 * container as well, ahead of the security chain, and `OncePerRequestFilter` would then let
 * the copy inside the chain skip — leaving a principal set on a context that
 * `SecurityContextHolderFilter` is about to replace.
 */
class ApiTokenFilter(
	private val tokens: ApiTokenService,
	private val limit: ApiTokenRateLimit,
) : OncePerRequestFilter() {

	/**
	 * `PathPatternRequestMatcher` and not `requestURI`, which is the bug
	 * `McpBearerFilter` documents: the raw URI keeps path parameters and percent-escapes,
	 * so `/api;x=y/mcp` and `/api/%6Dcp` both route to an `/api/mcp` handler while a
	 * string comparison stands aside. Here the consequence of getting it wrong is the
	 * mirror image of the one over there — this filter would *claim* the MCP endpoint and
	 * refuse every valid OAuth token on it — but it is the same derivation, so it is the
	 * same matcher.
	 */
	private val mcp = PathPatternRequestMatcher.withDefaults().matcher(McpResource.PATH + "/**")

	override fun shouldNotFilter(request: HttpServletRequest): Boolean = mcp.matches(request)

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val header = request.getHeader(HttpHeaders.AUTHORIZATION)

		// No `Authorization` at all is the browser, and `Basic` or anything else is not a
		// scheme Kanso offers — neither is a claim this filter has been asked to judge, so
		// both go on to the chain that knows about cookies. A *Bearer* is.
		if (header == null || !header.startsWith(BEARER, ignoreCase = true)) {
			return filterChain.doFilter(request, response)
		}

		val presented = header.substring(BEARER.length).trim()

		// One expression, and every failure in it lands on the same refusal: empty after
		// the scheme, not a token this instance issued, a token deleted since it was
		// issued, or a token whose owner is deactivated. `McpBearerFilter`'s argument for
		// one answer holds exactly: telling these apart would tell an attacker which guess
		// was closer and tells a legitimate client nothing it can act on.
		val bearer = presented.takeIf { it.isNotEmpty() }?.let { tokens.authenticate(it) }
			?: return refuse(response)

		// After the lookup, because the limit is per *token* and there is no token to key
		// on before it. `ApiTokenRateLimit` records what that does and does not protect.
		if (!limit.allow(bearer.token.tokenId)) return throttle(response)

		// **Best-effort, and this is the decision the ticket asks to see written down.**
		//
		// `last_used_at` is a fact *about* the request, not a precondition of it. The
		// caller has already presented a valid credential and passed the limit, so the
		// request is authorised; failing it now because a bookkeeping `UPDATE` could not be
		// written would convert a database hiccup, a lock wait or a read-only replica into
		// a 500 on a request that was in every way fine — and it would do so on *every*
		// request, since this write is on the hot path. A settings screen showing a stale
		// "last used" is the strictly smaller harm, and it is a harm that repairs itself on
		// the next request.
		//
		// Its own transaction, so a failure here cannot mark the request's own transaction
		// rollback-only — the request has not opened one yet. Logged at warn and not
		// swallowed silently: a stamp that fails forever is a real signal about the
		// database, and the only place it can be noticed is a log line.
		runCatching { tokens.stamp(bearer.token, OffsetDateTime.now()) }
			.onFailure { logger.warn("Could not stamp last_used_at for an API token; serving the request anyway", it) }

		// The scope gate, and it is *after* the stamp on purpose: the credential
		// authenticated, so it was used, and a settings screen showing "last used 2
		// minutes ago" beside a read-only token is how somebody finds the integration
		// that has been failing all afternoon. Refusing first would hide exactly that.
		//
		// Here rather than in an interceptor beside `ReadOnlySeatInterceptor`, and the
		// difference is what each rule needs to know. That one needs the *mapped pattern*,
		// because it carries a list of exempt routes, and a pattern only exists once a
		// handler has been chosen. This one needs the method and nothing else — so it can
		// be answered before the request reaches any controller at all, which is one fewer
		// component and one fewer place a caller could be let through.
		if (ApiTokenScopes.refuses(request.method, bearer.token.scopes)) {
			return insufficientScope(response)
		}

		val principal = KansoTokenUser(
			kansoUserId = bearer.owner.id,
			kansoEmail = bearer.owner.email,
			displayName = bearer.owner.displayName,
			tokenId = bearer.token.tokenId,
			scopes = bearer.token.scopes,
		)

		// A fresh context set on the holder, never an assignment into the one already there.
		// `AgentPrincipalFilter` carries the long version and `McpBearerFilter` repeats it;
		// here the precondition is not thin at all, it is the ordinary case: a request that
		// reaches this line may well have arrived with a `JSESSIONID`, and
		// `HttpSessionSecurityContextRepository` hands back the *stored* context instance
		// rather than a copy — so writing through `getContext()` would rewrite the session
		// of whoever's cookie came along, replacing their identity with the token's for
		// every later request they make.
		SecurityContextHolder.setContext(
			SecurityContextHolder.createEmptyContext().apply {
				this.authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
			},
		)

		filterChain.doFilter(request, response)
	}

	/**
	 * 401, terminal, with RFC 6750's `error="invalid_token"`.
	 *
	 * No `resource_metadata` and so not `McpChallenge`'s header: that one points a client
	 * at an authorisation server to start a flow with, and there is no flow here — an API
	 * token is created by a person on a settings screen. Telling a CLI to go and discover
	 * an OAuth endpoint would send it somewhere that cannot help it.
	 */
	private fun refuse(response: HttpServletResponse) {
		response.setHeader(
			HttpHeaders.WWW_AUTHENTICATE,
			"$BEARER_SCHEME error=\"invalid_token\", error_description=\"$INVALID\"",
		)
		response.status = HttpServletResponse.SC_UNAUTHORIZED
	}

	/**
	 * 403 and RFC 6750's `insufficient_scope`, which the RFC specifies for exactly this.
	 *
	 * Not 401: a 401 says "authenticate", and a client that obeyed it would present the
	 * same token again forever. The token is right, its permissions are not, and the only
	 * fix is a person making a new one — so the sentence names the scope that is missing.
	 * `McpController` answers the same situation with the same `error` code one layer up.
	 */
	private fun insufficientScope(response: HttpServletResponse) {
		response.setHeader(
			HttpHeaders.WWW_AUTHENTICATE,
			"$BEARER_SCHEME error=\"insufficient_scope\", " +
				"error_description=\"${ApiTokenScopes.WRITES_NEED_WRITE_SCOPE}\", " +
				"scope=\"${OAuthScopes.WRITE}\"",
		)
		response.status = HttpServletResponse.SC_FORBIDDEN
		response.contentType = MediaType.TEXT_PLAIN_VALUE
		response.characterEncoding = Charsets.UTF_8.name()
		response.writer.write(ApiTokenScopes.WRITES_NEED_WRITE_SCOPE)
	}

	/**
	 * 429 and not 401, because the credential is fine and retrying with a different one
	 * would be the wrong lesson. `Retry-After` in seconds so a client can obey it without
	 * parsing prose, and the prose names the per-token limit so whoever wrote the loop
	 * knows which knob exists.
	 */
	private fun throttle(response: HttpServletResponse) {
		response.setHeader(HttpHeaders.RETRY_AFTER, limit.retryAfterSeconds.toString())
		response.status = TOO_MANY_REQUESTS
		response.contentType = MediaType.TEXT_PLAIN_VALUE
		response.characterEncoding = Charsets.UTF_8.name()
		response.writer.write(THROTTLED)
	}

	companion object {
		private const val BEARER = "Bearer "
		private const val BEARER_SCHEME = "Bearer"

		/** `HttpStatus.TOO_MANY_REQUESTS` has no constant on `HttpServletResponse`. */
		const val TOO_MANY_REQUESTS = 429

		const val INVALID = "The API token is unknown, revoked, or belongs to a deactivated account"
		const val THROTTLED = "This API token has made too many requests; see Retry-After"
	}
}

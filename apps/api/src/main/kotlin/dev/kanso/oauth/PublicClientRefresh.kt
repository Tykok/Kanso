package dev.kanso.oauth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import java.time.Instant
import java.util.Base64

/**
 * The hour after which the connector would otherwise stop working.
 *
 * Access tokens live one hour ([ClientRegistrationService]). Every client this server
 * registers is public — no secret survives on a laptop, so there is none to issue — and
 * out of the box Spring Authorization Server refuses such a client a refresh token twice
 * over. Both refusals were confirmed against a running instance, not read out of the
 * source:
 *
 * 1. **Nothing to rotate.** `OAuth2RefreshTokenGenerator.generate` returns null when the
 *    grant is `authorization_code` and the client authenticated with
 *    [ClientAuthenticationMethod.NONE], so the code exchange answers with an access token
 *    and no `refresh_token` at all — `refresh_token_value` was null in
 *    `oauth2_authorization`, and the response body carried four fields.
 * 2. **Nowhere to present it.** None of the library's five client-authentication
 *    converters matches a `grant_type=refresh_token` request from a client with no
 *    credential — `PublicClientAuthenticationConverter` is gated on
 *    `matchesPkceTokenRequest`, which requires a `code_verifier`. With no authentication
 *    set, `anyRequest().authenticated()` refuses the request before the token endpoint
 *    filter is even reached, because 7.1.0 installs that filter *after*
 *    `AuthorizationFilter`. A refresh attempt answered **401 with an empty body**.
 *
 * Neither is a rule OAuth 2.1 asks for. §4.3.1 of the security BCP allows a refresh token
 * to a public client on either of two conditions, and this branch already chose one of
 * them: `reuseRefreshTokens(false)`, so every refresh rotates. The binding the RFC
 * actually requires is enforced by the library and not by anything here —
 * `OAuth2RefreshTokenAuthenticationProvider` compares the authenticated client's id
 * against the id recorded on the stored authorisation and answers `invalid_grant` when
 * they differ, so a token belonging to one client is worth nothing to another.
 *
 * The half of §4.3.1 that is **not** met is replay detection: reuse of a spent refresh
 * token is refused, and does not revoke the chain. The wiki's `Follow-ups` page records
 * it, and it stopped being theoretical the moment this file made refresh tokens exist.
 */

/**
 * The library's own refresh-token generator, minus the clause that says public clients do
 * not get one.
 *
 * A copy rather than a subclass because the clause is inside `generate`, which is the
 * whole method: `OAuth2RefreshTokenGenerator` offers nothing to override around it. What
 * is copied is small and is the library's — 96 bytes of URL-safe randomness, and the
 * client's own `refreshTokenTimeToLive` — so this stays in step with it by being the same
 * three lines rather than by being clever.
 */
class PublicClientRefreshTokenGenerator : OAuth2TokenGenerator<OAuth2RefreshToken> {

	private val keys = Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), TOKEN_BYTES)

	override fun generate(context: OAuth2TokenContext): OAuth2RefreshToken? {
		// Asked for every token type; answers for one. `DelegatingOAuth2TokenGenerator`
		// takes the first non-null, which is what makes this composable at all.
		if (OAuth2TokenType.REFRESH_TOKEN != context.tokenType) return null

		val issuedAt = Instant.now()
		return OAuth2RefreshToken(
			keys.generateKey(),
			issuedAt,
			issuedAt.plus(context.registeredClient.tokenSettings.refreshTokenTimeToLive),
		)
	}

	private companion object {
		/** `OAuth2RefreshTokenGenerator`'s own key length. Not a number this file chose. */
		const val TOKEN_BYTES = 96
	}
}

/**
 * A public client presenting itself on a refresh, which the library's own converters do
 * not recognise.
 *
 * Deliberately narrow, and every condition below is load-bearing. Anything carrying a
 * credential — an `Authorization` header, a `client_secret`, a JWT assertion — is left to
 * the library's four converters, because a *confidential* client that reached this one
 * would be authenticated on the strength of its `client_id` alone, which is the whole
 * point of it having a secret. And anything that is not a refresh request is left alone
 * too: the code exchange is already handled, by a converter that checks a PKCE verifier
 * this one has no business skipping.
 *
 * What is left is the case OAuth 2.1 describes: a client with no credential to present,
 * whose evidence is the refresh token itself. That token is checked by the library, and
 * checked against the client named here.
 *
 * @param settings read for one string, and read rather than written because the endpoint
 *   this opens on has to be the endpoint the library serves. A path constant would agree
 *   with it today and drift the day the setting moves, in the direction that leaves the
 *   converter answering on a path that is by then somebody else's.
 */
class PublicClientRefreshConverter(settings: AuthorizationServerSettings) : AuthenticationConverter {

	/**
	 * Which endpoint, and it is the first question rather than an afterthought.
	 *
	 * `OAuth2ClientAuthenticationFilter` stands in front of *five* POST endpoints, not one:
	 * the token endpoint, plus introspection, revocation, device authorization and pushed
	 * authorization requests. `grant_type` means nothing on the other four and nothing
	 * refuses it there, so a converter reading only the method and the parameters
	 * authenticates a bare `client_id` at `/oauth2/introspect` — a request
	 * `anyRequest().authenticated()` refused outright before this file existed, and one
	 * `OAuth2TokenIntrospectionAuthenticationProvider` does not check the ownership of. It
	 * costs an attacker one open `/connect/register` call to hold a `client_id`.
	 *
	 * `PathPatternRequestMatcher` for [McpBearerFilter][dev.kanso.mcp.McpBearerFilter]'s
	 * reason and built the way the library builds its own: the path Spring routes on, not
	 * the URI Tomcat received. The method is folded in here because it is the same
	 * question — a credential-free GET carrying these parameters is a link.
	 */
	private val tokenEndpoint = PathPatternRequestMatcher.withDefaults()
		.matcher(HttpMethod.POST, settings.tokenEndpoint)

	override fun convert(request: HttpServletRequest): Authentication? {
		if (!tokenEndpoint.matches(request)) return null
		if (request.getParameter(OAuth2ParameterNames.GRANT_TYPE) != AuthorizationGrantType.REFRESH_TOKEN.value) {
			return null
		}
		if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) return null
		if (request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null) return null
		if (request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) return null

		val presented = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID) ?: return null
		// Two `client_id` parameters is a malformed request and not a choice to make on the
		// client's behalf — the library's own converters refuse the same shape, for the same
		// reason: whichever one you pick, the other one was ignored.
		if (presented.size != 1) throw OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST)
		val clientId = presented.single().takeIf { it.isNotBlank() } ?: return null

		return OAuth2ClientAuthenticationToken(
			clientId,
			ClientAuthenticationMethod.NONE,
			null,
			// Carried through the way the library's converters carry them, minus the two
			// credential fields. `grant_type` travelling in here is what lets the provider
			// below recognise its own request rather than one of the library's.
			request.parameterMap
				.filterKeys { it != OAuth2ParameterNames.CLIENT_ID && it != OAuth2ParameterNames.CLIENT_SECRET }
				.mapValues { (_, values) -> if (values.size == 1) values.single() as Any else values.toList() as Any },
		)
	}
}

/**
 * The other half: turning that request into an authenticated client.
 *
 * Registered ahead of the library's providers, so it is asked first — and it answers null
 * for everything that is not its own request, which is how the PKCE code exchange still
 * reaches `PublicClientAuthenticationProvider` untouched. `ProviderManager` moves on when
 * a provider returns null, so being first costs nothing.
 *
 * The one check that is not bookkeeping is the second, and it is worth stating in the
 * direction the code actually runs: a client is **accepted** when
 * [ClientAuthenticationMethod.NONE] is among the methods it registered — not refused for
 * having registered anything besides. A client declaring both `NONE` and
 * `client_secret_post` would pass here. That is the library's own reading of that column:
 * it is the set of methods a client *may* present, so a client that registered `NONE` has
 * been told it may present nothing, and narrowing it here would be one rule about a fact
 * the rest of the server settles elsewhere. `ClientRegistrationService` is the only place
 * a client is created and it registers `NONE` alone, so no such client exists to try it.
 *
 * What the check does refuse is the client that registered a credential and not `NONE` —
 * without it this provider is a way for a confidential client to authenticate with no
 * secret, on a request shape it never has to use.
 */
class PublicClientRefreshProvider(private val clients: RegisteredClientRepository) : AuthenticationProvider {

	override fun authenticate(authentication: Authentication): Authentication? {
		val request = authentication as OAuth2ClientAuthenticationToken
		if (ClientAuthenticationMethod.NONE != request.clientAuthenticationMethod) return null
		if (request.additionalParameters[OAuth2ParameterNames.GRANT_TYPE] != AuthorizationGrantType.REFRESH_TOKEN.value) {
			return null
		}

		val client = clients.findByClientId(request.principal as String)
			?: throw OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)
		if (ClientAuthenticationMethod.NONE !in client.clientAuthenticationMethods) {
			throw OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT)
		}

		// Whether this client may use the refresh grant at all, and whether the token it
		// presented belongs to it, are both the library's to answer — and it answers them a
		// few lines later in `OAuth2RefreshTokenAuthenticationProvider`. Repeating either
		// here would be a second rule about the same fact.
		return OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null)
	}

	override fun supports(authentication: Class<*>): Boolean =
		OAuth2ClientAuthenticationToken::class.java.isAssignableFrom(authentication)
}

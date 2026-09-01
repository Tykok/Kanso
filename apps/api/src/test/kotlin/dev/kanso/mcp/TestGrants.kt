package dev.kanso.mcp

import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Issues a grant the way the token endpoint would, without a browser.
 *
 * The suite runs in dev mode, where the authorisation server is deliberately absent
 * (`DevModeRefusalTest` says why), so there is no endpoint to drive. Writing through the
 * library's own `OAuth2AuthorizationService` is the next-closest thing to the real path:
 * the same rows, written by the same code, so [McpBearerFilter] reads back exactly what
 * the token endpoint would have written — including the JSON round trip of the
 * authorisation request, which is where the `resource` parameter lives and where a
 * hand-written fake would have quietly stopped being representative.
 *
 * A plain class, constructed by the test rather than a `@TestComponent` it imports:
 * `@Import` on a test class changes the context cache key, and `SecurityBootstrapTest`
 * explains what a second context configuration costs this suite. Three constructor
 * arguments are cheaper than a second Spring context.
 */
class TestGrants(
	private val clients: RegisteredClientRepository,
	private val authorizations: OAuth2AuthorizationService,
	private val consents: OAuth2AuthorizationConsentService,
) {

	/**
	 * The library's service can find an authorisation by token or by id and by nothing
	 * else, so [revoke] needs what [issue] built. Kanso's own revocation screen will
	 * query the table directly; a test helper reaching for JDBC would be inventing that
	 * query early and in the wrong place.
	 */
	private val issued = mutableMapOf<Pair<UUID, String>, OAuth2Authorization>()

	/**
	 * The authorisation [issue] would store, built and handed back unsaved.
	 *
	 * Separate because one of the shapes it can build cannot survive the store: a repeated
	 * `resource` saves and will not read back, so a rule about repeated values can only be
	 * asserted on an authorisation held in the hand. Same construction either way, so what
	 * is asserted in memory is what would have been written.
	 *
	 * @param resource what the client asked the token be bound to. `Any?` rather than
	 *   `String` on purpose: RFC 8707 allows the parameter to repeat, `null` is a client
	 *   that never sent one, and every one of those shapes is a refusal the filter has to
	 *   be shown refusing.
	 * @param principalName overridable so a grant written by something that is *not*
	 *   `AgentPrincipalFilter`'s chain can be shown being refused rather than looked up.
	 */
	fun authorize(
		owner: User,
		scopes: Set<String>,
		clientId: String,
		resource: Any? = "http://localhost/api/mcp",
		principalName: String = owner.id.toString(),
	): OAuth2Authorization {
		val client = clients.findByClientId(clientId) ?: register(clientId)
		val issuedAt = Instant.now()

		val request = OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("http://localhost/oauth2/authorize")
			.clientId(clientId)
			.redirectUri(client.redirectUris.first())
			.scopes(scopes)
			.state(UUID.randomUUID().toString())
			.additionalParameters(if (resource == null) emptyMap() else mapOf("resource" to resource))
			.build()

		return OAuth2Authorization.withRegisteredClient(client)
			// The Kanso user id, which is what `AgentPrincipalFilter` leaves here on the
			// real path and what the filter maps back through.
			.principalName(principalName)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizedScopes(scopes)
			.attribute(OAuth2AuthorizationRequest::class.java.name, request)
			.accessToken(
				OAuth2AccessToken(
					OAuth2AccessToken.TokenType.BEARER,
					"kat_${UUID.randomUUID()}",
					issuedAt,
					issuedAt.plus(Duration.ofHours(1)),
					scopes,
				),
			)
			.build()
	}

	/** @return the opaque token value a client would hold. */
	fun issue(
		owner: User,
		scopes: Set<String>,
		clientId: String,
		resource: Any? = "http://localhost/api/mcp",
		principalName: String = owner.id.toString(),
	): String {
		val authorization = authorize(owner, scopes, clientId, resource, principalName)

		authorizations.save(authorization)
		// The consent row the screen would have written. Not read by the filter, and
		// saved anyway: revocation has to remove both, and a helper that only ever
		// created half of the state would let a half-finished revocation pass.
		consents.save(
			OAuth2AuthorizationConsent.withId(authorization.registeredClientId, principalName)
				.apply { scopes.forEach { scope(it) } }
				.build(),
		)
		issued[owner.id to clientId] = authorization
		return requireNotNull(authorization.accessToken).token.tokenValue
	}

	fun revoke(owner: User, clientId: String) {
		val authorization = requireNotNull(issued.remove(owner.id to clientId)) {
			"no grant was issued to $clientId for ${owner.email}"
		}
		authorizations.remove(authorization)
		consents.findById(authorization.registeredClientId, owner.id.toString())?.let(consents::remove)
	}

	/** What `/connect/register` would have created: public, PKCE, opaque tokens. */
	private fun register(clientId: String): RegisteredClient {
		val client = RegisteredClient.withId(UUID.randomUUID().toString())
			.clientId(clientId)
			.clientName(clientId)
			.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
			.redirectUri("http://127.0.0.1:8765/callback")
			.apply { OAuthScopes.ALL.forEach { scope(it) } }
			.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
			.tokenSettings(
				TokenSettings.builder()
					.accessTokenFormat(OAuth2TokenFormat.REFERENCE)
					.accessTokenTimeToLive(Duration.ofHours(1))
					.build(),
			)
			.build()
		clients.save(client)
		return client
	}
}

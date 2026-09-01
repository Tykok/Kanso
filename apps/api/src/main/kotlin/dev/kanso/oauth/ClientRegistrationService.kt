package dev.kanso.oauth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import java.time.Duration
import java.util.UUID

/**
 * RFC 7591's request, as much of it as this endpoint reads.
 *
 * Everything else a client sends is ignored rather than honoured — a field asking for a
 * confidential client, a secret, or a grant type is not an error and not a permission.
 */
data class ClientRegistrationRequest(
	@JsonProperty("redirect_uris") val redirectUris: List<String>?,
	@JsonProperty("client_name") val clientName: String?,
	val scope: String?,
)

/** RFC 7591's response. No secret, and no registration access token to steal. */
data class ClientRegistrationResponse(
	@JsonProperty("client_id") val clientId: String,
	@JsonProperty("client_id_issued_at") val clientIdIssuedAt: Long,
	@JsonProperty("client_name") val clientName: String,
	@JsonProperty("redirect_uris") val redirectUris: List<String>,
	@JsonProperty("grant_types") val grantTypes: List<String>,
	@JsonProperty("response_types") val responseTypes: List<String>,
	@JsonProperty("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
	val scope: String,
)

/** Carries RFC 7591's error code, which is not one of Kanso's three exceptions. */
class ClientRegistrationRefused(val code: String, val reason: String) : RuntimeException(reason)

/**
 * Creating a client, and nothing else.
 *
 * No initial access token, no registration access token, no update and no delete — **a
 * client that cannot be edited cannot be edited by an attacker either**, and the only
 * thing lost is a capability nobody asked for.
 *
 * Open registration is acceptable rather than merely convenient because of what a
 * registration *is not*. It is a name that may ask. Until a member says yes on the
 * consent screen it can read nothing, and where it may be sent a code is decided by
 * [RedirectUriPolicy] rather than by the request.
 *
 * @param clientCount how many clients exist, for the cap. A function rather than a
 *   repository call because [RegisteredClientRepository] has no count, and a service that
 *   reaches for `JdbcClient` to answer one question is a service that cannot be tested
 *   without a database.
 */
class ClientRegistrationService(
	private val clients: RegisteredClientRepository,
	private val policy: RedirectUriPolicy,
	private val clientCount: () -> Int,
	private val maxClients: Int,
) {

	fun register(request: ClientRegistrationRequest): ClientRegistrationResponse {
		// The cap first: refusing early means a full table costs one count, not a
		// validation pass, for whoever is hammering the endpoint.
		//
		// Read outside the insert, so two registrations arriving at the cap can both pass
		// it — the overshoot is bounded by how many requests are in flight, and left as it
		// is on purpose: closing it means a transaction around a count and a save, or a
		// lock on a table the library owns, to hold a number that is a safety valve rather
		// than a quota.
		if (clientCount() >= maxClients) {
			throw ClientRegistrationRefused(
				"invalid_client_metadata",
				"This instance already holds $maxClients registered clients and will not accept more. " +
					"An administrator can revoke unused ones from the settings screen.",
			)
		}

		val redirectUris = request.redirectUris.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
		if (redirectUris.isEmpty()) {
			throw ClientRegistrationRefused(
				"invalid_redirect_uri",
				"At least one redirect_uris entry is required.",
			)
		}
		// Lengths, before anything is parsed and long before anything is saved. The columns
		// are the library's — `client_name varchar(200)`, and `redirect_uris varchar(1000)`
		// holding the whole comma-joined list — copied verbatim from its schema and not
		// ours to widen, so what does not fit has to be refused here. Unchecked, it reached
		// Postgres instead and came back as a 500 from an endpoint that needs no
		// credential, where RFC 7591 asks for a code the client can read. Kotlin counts
		// UTF-16 units and Postgres counts characters, so this bound errs on the strict
		// side for anything outside the BMP, which is the harmless direction.
		if (redirectUris.size > MAX_REDIRECT_URIS || redirectUris.joinToString(",").length > MAX_REDIRECT_URIS_LENGTH) {
			throw ClientRegistrationRefused(
				"invalid_client_metadata",
				"redirect_uris must be at most $MAX_REDIRECT_URIS entries and " +
					"$MAX_REDIRECT_URIS_LENGTH characters in total.",
			)
		}
		// One bad entry refuses the whole request. A partial registration is a client that
		// works until it does not, and the moment it does not is a member's, not ours.
		redirectUris.firstOrNull { !policy.isAllowed(it) }?.let {
			throw ClientRegistrationRefused(
				"invalid_redirect_uri",
				"$it is not an address this server will send an authorisation code to. " +
					"Loopback on any port, or one of this instance's configured client hosts over https.",
			)
		}

		// Asked-for scopes are checked and then discarded: every client is registered for
		// both, and what a *grant* covers is the member's decision on the consent screen.
		// Narrowing silently would leave a client believing it holds something it does
		// not, and the symptom would land as a 403 at its first write.
		request.scope?.split(" ")?.map { it.trim() }?.filter { it.isNotBlank() }
			?.firstOrNull { it !in OAuthScopes.ALL }
			?.let {
				throw ClientRegistrationRefused(
					"invalid_client_metadata",
					"Unknown scope '$it' — this instance grants ${OAuthScopes.ALL.joinToString(" and ")}.",
				)
			}

		val name = request.clientName?.trim()?.takeIf { it.isNotBlank() } ?: "An unnamed client"
		if (name.length > MAX_NAME_LENGTH) {
			throw ClientRegistrationRefused(
				"invalid_client_metadata",
				"client_name must be at most $MAX_NAME_LENGTH characters.",
			)
		}
		val clientId = UUID.randomUUID().toString()

		val client = RegisteredClient.withId(UUID.randomUUID().toString())
			.clientId(clientId)
			// No secret is ever issued, so a leaked registration response is worth
			// nothing on its own.
			.clientName(name)
			.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			// Declared, or there is no refresh token and the rotation MUST has nothing to
			// rotate — which is exactly what the spike observed.
			.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
			.apply { redirectUris.forEach { redirectUri(it) } }
			.apply { OAuthScopes.ALL.forEach { scope(it) } }
			.clientSettings(
				ClientSettings.builder()
					// Clients here are public — no secret survives on a laptop — so the
					// code challenge is what stops an intercepted code being redeemed.
					.requireProofKey(true)
					// The screen is the only thing between a self-made client and
					// somebody's backlog.
					.requireAuthorizationConsent(true)
					.build(),
			)
			.tokenSettings(
				TokenSettings.builder()
					// Opaque, not a JWT: Kanso is both the authorisation server and the
					// resource server over one Postgres, so a signed token would buy
					// stateless validation it does not need and cost a revocation window.
					.accessTokenFormat(OAuth2TokenFormat.REFERENCE)
					.accessTokenTimeToLive(Duration.ofHours(1))
					.refreshTokenTimeToLive(Duration.ofDays(60))
					// Consumed on use and replaced. Reuse of a spent one revokes the grant.
					.reuseRefreshTokens(false)
					.build(),
			)
			.build()

		clients.save(client)

		return ClientRegistrationResponse(
			clientId = clientId,
			clientIdIssuedAt = System.currentTimeMillis() / 1000,
			clientName = name,
			redirectUris = redirectUris,
			grantTypes = listOf("authorization_code", "refresh_token"),
			responseTypes = listOf("code"),
			tokenEndpointAuthMethod = "none",
			scope = OAuthScopes.ALL.joinToString(" "),
		)
	}

	private companion object {

		/** `oauth2_registered_client.client_name`, which is `varchar(200)`. */
		const val MAX_NAME_LENGTH = 200

		/**
		 * `redirect_uris` is one `varchar(1000)` for the joined list, so the total is the
		 * real bound. The count is here as well because a request naming five hundred
		 * short callbacks is not a client, and refusing it early is cheaper than parsing
		 * each one.
		 */
		const val MAX_REDIRECT_URIS = 10
		const val MAX_REDIRECT_URIS_LENGTH = 1000
	}
}

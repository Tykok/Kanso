package dev.kanso.oauth

import dev.kanso.tokens.ApiTokenSecret
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.core.OAuth2Token
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType

/**
 * The one table in this schema where a dump was a set of live credentials, wrapped so it is
 * not.
 *
 * Everything else that acts as a bearer is already unusable from a backup, and the schema
 * says so in as many words — `V27__api_tokens.sql` stores only a SHA-256 of a personal
 * access token and writes *"a stolen database dump is not a set of live credentials"* beside
 * the column; `V31__outbound_webhooks.sql` and `WebhookSecret` encrypt the signing secret
 * under a key held outside the database; `SecretBox` does the same for the Notion, Google
 * and GitHub credentials. `oauth2_authorization` was the exception, because its columns are
 * the library's and were taken as given: `access_token_value` and `refresh_token_value` held
 * the live strings, and `McpBearerFilter` resolved a presented bearer by SQL equality
 * against them.
 *
 * It is worse than a generic library default here, because this repository deliberately gave
 * these clients refresh tokens. [PublicClientRefreshTokenGenerator] is the library's
 * generator with the "public clients get no refresh token" clause removed, and
 * `PublicClientRefreshConverter` authenticates a refresh on a bare `client_id` with no
 * credential of any kind. So before that code existed a dump yielded a one-hour access
 * token; after it, a sixty-day refresh token whose only companion — the `client_id` — sits
 * in `oauth2_registered_client` in the same dump. Anyone who could read Postgres could mint
 * live MCP access for any member who had ever consented, from anywhere, renewably.
 *
 * **Hashed and not encrypted**, which is `V27`'s argument reused rather than re-derived:
 * these values are only ever *looked up*, never read back out and shown to anyone, so there
 * is nothing to decrypt for. An unsalted fast digest is right for the same reason it is
 * right for a PAT — the input is 256 bits of `SecureRandom`, so there is no dictionary to
 * run and nothing a salt would defend against.
 *
 * ### The prefix is load-bearing
 *
 * [stored] is idempotent and [presented] is not, and they have to differ. `save` is called
 * with authorizations that came back out of [findByToken] — the code exchange re-saves the
 * spent authorization code, a revocation re-saves an invalidated token — so a `save` that
 * hashed unconditionally would store a digest of a digest and the row would stop matching.
 * Hence [MARKER], which makes a second pass a no-op.
 *
 * Lookups must **not** share that shortcut. If [findByToken] passed a marked value straight
 * through, a caller who had read the token column — which is the whole threat being closed
 * — could present the digest itself as a bearer and match the row. So the two directions are
 * two functions, and that asymmetry is the point rather than an oversight.
 *
 * ### What is covered
 *
 * The four token types this authorization server can issue. Device and user codes are not
 * among them — no device grant is enabled — and if one is ever turned on, its columns join
 * this list or they store plaintext silently. `state` is deliberately left alone: it is a
 * one-shot correlation value for an authorization request in flight, not a bearer, and
 * `findByToken(value, null)` has to keep matching it for the flow to work.
 */
class HashedOAuthTokens(
	private val delegate: OAuth2AuthorizationService,
) : OAuth2AuthorizationService {

	override fun save(authorization: OAuth2Authorization) = delegate.save(hashed(authorization))

	/** By id in the delegate's SQL, so the token values are not what finds the row. */
	override fun remove(authorization: OAuth2Authorization) = delegate.remove(authorization)

	override fun findById(id: String): OAuth2Authorization? = delegate.findById(id)

	override fun findByToken(token: String, tokenType: OAuth2TokenType?): OAuth2Authorization? =
		delegate.findByToken(presented(token), tokenType)

	/**
	 * The same authorization with every bearer value replaced by its digest.
	 *
	 * Each token is rebuilt rather than mutated because `OAuth2Authorization.Token` has no
	 * setter, and its metadata is carried across explicitly — `invalidated` lives there, and
	 * dropping it would turn a revoked token back into a live one.
	 */
	private fun hashed(authorization: OAuth2Authorization): OAuth2Authorization {
		val builder = OAuth2Authorization.from(authorization)
		authorization.replace<OAuth2AuthorizationCode>(builder) {
			// The only one of the four whose constructor demands both instants. They are
			// never absent — the code is built by the library's generator, which sets them
			// — and `requireNotNull` is deliberately loud rather than a fallback that would
			// quietly put the code back in the column in plaintext.
			OAuth2AuthorizationCode(
				stored(it.tokenValue),
				requireNotNull(it.issuedAt) { "an authorization code with no issuedAt" },
				requireNotNull(it.expiresAt) { "an authorization code with no expiresAt" },
			)
		}
		authorization.replace<OAuth2AccessToken>(builder) {
			OAuth2AccessToken(it.tokenType, stored(it.tokenValue), it.issuedAt, it.expiresAt, it.scopes)
		}
		authorization.replace<OAuth2RefreshToken>(builder) {
			OAuth2RefreshToken(stored(it.tokenValue), it.issuedAt, it.expiresAt)
		}
		authorization.replace<OidcIdToken>(builder) {
			OidcIdToken(stored(it.tokenValue), it.issuedAt, it.expiresAt, it.claims)
		}
		return builder.build()
	}

	private inline fun <reified T : OAuth2Token> OAuth2Authorization.replace(
		builder: OAuth2Authorization.Builder,
		rebuild: (T) -> T,
	) {
		val held = getToken(T::class.java) ?: return
		builder.token(rebuild(held.token)) { it.putAll(held.metadata) }
	}

	/** Idempotent: see the class doc. What goes into the column. */
	private fun stored(value: String) =
		if (value.startsWith(MARKER)) value else MARKER + ApiTokenSecret.hash(value)

	/** Never idempotent: see the class doc. What a caller's bearer is compared as. */
	private fun presented(value: String) = MARKER + ApiTokenSecret.hash(value)

	private companion object {
		/**
		 * Marks a value as already hashed, and says so in the table to whoever looks next.
		 * The `$` cannot appear in a token: every value this server issues is base64url of
		 * `SecureRandom` bytes, or a JWT, and neither alphabet has one.
		 */
		const val MARKER = "sha256\$"
	}
}

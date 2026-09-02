package dev.kanso.tokens

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A member's own API tokens, and the lookup the Bearer filter performs.
 *
 * **No user id parameter on the three methods a person calls.** `list`, `create` and
 * `revoke` read the actor from the security context, for the reason `GrantService` gives
 * about connected applications: with no argument to pass, there is nothing a call site
 * could pass that would point one of them at somebody else's credentials. The isolation is
 * a property of the signature rather than of every caller remembering to scope.
 *
 * [authenticate] is the exception and takes the presented secret, because it runs *before*
 * there is a security context — it is what creates one.
 *
 * `@Transactional` on the methods rather than a `TransactionTemplate` in the filter, which
 * is the shape `DevAuthenticationFilter` already uses: a filter runs outside every
 * transaction and Exposed refuses a query without one, and reaching the database through a
 * transactional bean is how the other header-reading filter in this application solves
 * that. `McpBearerFilter` carries a template instead, and its comment says why — it needs
 * a transaction around one call in the middle of two library lookups that need none.
 */
/**
 * A valid token and the member it speaks for, resolved together.
 *
 * One type rather than two returns because they are one answer — `ApiTokenFilter` cannot
 * do anything useful with either half alone, and a signature that handed back the token
 * and left the owner to a second call would put a window between them where the account
 * could be deactivated.
 */
data class TokenBearer(val token: AuthenticatedToken, val owner: User)

@Service
class ApiTokenService(
	private val currentUser: CurrentUser,
	private val tokens: ApiTokenRepository,
	private val users: UserRepository,
) {

	@Transactional(readOnly = true)
	fun list(): List<ApiToken> = tokens.findByUser(currentUser.requireId())

	/**
	 * The only method that ever returns a plaintext secret, and it returns a type that
	 * exists to say so.
	 *
	 * The secret is generated here and not by the caller: a controller that could pass one
	 * in is a controller a test, a fixture or a future endpoint could pass a weak one into,
	 * and there is no reason for that door to exist.
	 */
	@Transactional
	fun create(name: String, scopes: Collection<String>?): NewApiToken {
		val trimmed = name.trim()
		// The same bound `V27`'s CHECK holds, refused here so the answer is a sentence
		// rather than a constraint violation flattened into a 409.
		if (trimmed.isEmpty() || trimmed.length > NAME_MAX) {
			throw BadRequestException("A token's name must be between 1 and $NAME_MAX characters")
		}
		val granted = ApiTokenScopes.requested(scopes)
		val secret = ApiTokenSecret.generate()
		val token = tokens.insert(
			userId = currentUser.requireId(),
			name = trimmed,
			prefix = ApiTokenSecret.prefixOf(secret),
			hash = ApiTokenSecret.hash(secret),
			scopes = granted,
		)
		return NewApiToken(token, secret)
	}

	/**
	 * Revocation. The row is gone when this returns, so the next request carrying that
	 * secret finds nothing — see `V27` on why there is no `revoked_at` for anybody to
	 * forget.
	 *
	 * A 404 for an id that is not the actor's, deliberately indistinguishable from an id
	 * that never existed.
	 */
	@Transactional
	fun revoke(id: UUID) {
		if (!tokens.delete(currentUser.requireId(), id)) {
			throw NotFoundException("No API token $id")
		}
	}

	/**
	 * The digest lookup and the owner behind it — no stamping, no rate limit, no policy.
	 *
	 * Both halves in one read transaction because a token that resolves to no owner is not
	 * a token: `V27`'s cascade means the row cannot outlive the account, so a null here is
	 * a database mid-delete, and answering "authenticated, user unknown" for that instant
	 * is the one answer that must not be possible.
	 *
	 * **`active` is re-checked on every single use**, and that is a real difference from the
	 * cookie path rather than belt-and-braces. `LocalAuthService` checks it once, at
	 * sign-in, and that is sound for a session — it is minted at the moment of the check
	 * and expires. A token is minted once and lives until somebody deletes it, so
	 * "deactivate this account" would otherwise stop their browser and leave every token
	 * they ever made answering. Deactivating is the gesture an admin reaches for when
	 * somebody leaves, which makes this the case it most has to cover.
	 */
	@Transactional(readOnly = true)
	fun authenticate(presented: String): TokenBearer? {
		val token = tokens.findByHash(ApiTokenSecret.hash(presented)) ?: return null
		val owner = users.findById(token.userId)?.takeIf { it.active } ?: return null
		return TokenBearer(token, owner)
	}

	/**
	 * `last_used_at`, written coarsely and on purpose.
	 *
	 * Skipped when the stored value is already newer than [STAMP_RESOLUTION], which turns a
	 * write-per-request into a write-per-minute-per-token. What the column is read for is
	 * "is this token still in use, and is this the one I can safely revoke" — a question
	 * answered no better by a timestamp accurate to the microsecond, and a webhook consumer
	 * polling at 10 rps would otherwise mean ten `UPDATE`s a second on a row for the sole
	 * benefit of a settings screen nobody is looking at.
	 *
	 * The consequence is stated rather than hidden: the value can be up to a minute behind,
	 * and a token used exactly once shows the instant of that use because null is never
	 * newer than anything.
	 *
	 * Returns nothing and throws nothing the caller has to handle. Whether a *failure* to
	 * stamp is allowed to fail the request is [ApiTokenFilter]'s decision, and it is
	 * recorded there because that is where the catch is.
	 */
	@Transactional
	fun stamp(token: AuthenticatedToken, now: OffsetDateTime) {
		val last = token.lastUsedAt
		if (last != null && Duration.between(last, now) < STAMP_RESOLUTION) return
		tokens.stamp(token.tokenId, now)
	}

	companion object {
		const val NAME_MAX = 80

		/** How stale `last_used_at` is allowed to be. See [stamp]. */
		val STAMP_RESOLUTION: Duration = Duration.ofMinutes(1)
	}
}

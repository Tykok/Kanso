package dev.kanso.github

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A member who has said yes. One row of `github_accounts`.
 *
 * **There is no token on this type, and that is the point.** A domain object carrying an
 * access token is a token in the next log line, the next response body and the next stack
 * trace, none of which anybody chose. The token is read by the one method that spends it
 * and by nothing else; everything a screen or the feed wants to know is here without it.
 *
 * [refreshable] is the *presence* of a refresh token rather than the token, for the same
 * reason `TicketPullRequest.linkedByMember` is the presence of a member rather than an id:
 * nothing outside the writer needs the secret, and plenty needs the fact.
 */
data class GithubAccount(
	val userId: UUID,
	/**
	 * GitHub's own numeric id, and the identity that survives a rename. `UNIQUE` in `V36`
	 * so an inbound payload can only ever resolve to one member.
	 */
	val githubUserId: Long,
	/** As GitHub last spelled it. Matched case-insensitively — see `github_accounts_login_idx`. */
	val githubLogin: String,
	/** Null when the App never opted into expiring tokens. See [GithubTokenState]. */
	val expiresAt: OffsetDateTime?,
	val refreshable: Boolean,
	val linkedAt: OffsetDateTime,
) {
	/**
	 * Whether the token this row holds can still be spent, **computed on read**.
	 *
	 * Derived and never stored, which is this repository's rule: an `expired` column is a
	 * fact that becomes false while nobody is looking at it, and the only way to keep it
	 * true is a scheduler whose whole job is to write down what a comparison already knows.
	 *
	 * @param now passed in rather than read from the clock, so the four branches are a
	 *   table a test can state rather than four moments a test has to wait for.
	 */
	fun tokenState(now: OffsetDateTime): GithubTokenState = when {
		expiresAt == null -> GithubTokenState.PERMANENT
		expiresAt.isAfter(now) -> GithubTokenState.ACTIVE
		refreshable -> GithubTokenState.REFRESHABLE
		else -> GithubTokenState.EXPIRED
	}
}

/**
 * What can still be done with a linked member's token.
 *
 * Four words because `V36` made `refresh_token_enc` and `expires_at` nullable
 * independently, and **both of GitHub's shapes are real**: a user-to-server token never
 * expires unless the App opted into expiring tokens, in which case a refresh token
 * arrives beside it. The migration says outright that "no refresh token" has to be
 * storable rather than assumed away, so it is a word here rather than a branch nobody
 * wrote.
 *
 * [EXPIRED] is the shape GitHub does not document and the schema still admits — an expiry
 * with nothing to renew it. It is a word rather than a refusal because **the row is still
 * consent**, and that distinction is the whole of this ticket: gating a member's *name* on
 * their token would make their own history flicker back to *KAN-142 moved to Done via
 * #418* eight hours after they linked, for a reason that has nothing to do with them. A
 * row in `github_accounts` exists *because* somebody completed a consent flow —
 * `access_token_enc` is `NOT NULL` for exactly that reason — and consent is not undone by
 * a clock. Writing *as* a member needs a live token; naming them does not, and nothing in
 * `GithubAccountRepository.memberFor` looks at these four words.
 */
enum class GithubTokenState(val wire: String) {
	/** No expiry. The App did not opt into expiring tokens; this token works until revoked. */
	PERMANENT("permanent"),

	/** Expires, and has not yet. */
	ACTIVE("active"),

	/** Expired, and a refresh token can renew it without asking the member again. */
	REFRESHABLE("refreshable"),

	/** Expired with nothing to renew it. The member walks the consent screen again. */
	EXPIRED("expired");

	companion object {
		fun from(raw: String): GithubTokenState = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown GitHub token state '$raw'")
	}
}

/**
 * What GitHub answered, on its way to `SecretBox` and no further.
 *
 * [refreshToken] and [expiresAt] are both null for the non-expiring shape and both set for
 * the expiring one, and the type admits the mixtures rather than forbidding them: what
 * GitHub sends is not something this codebase gets to constrain, and a `require` here
 * would turn an unexpected response into a 500 in the middle of a consent flow.
 */
data class GithubToken(
	val accessToken: String,
	val refreshToken: String?,
	val expiresAt: OffsetDateTime?,
)

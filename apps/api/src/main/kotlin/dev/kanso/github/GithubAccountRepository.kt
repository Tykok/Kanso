package dev.kanso.github

import dev.kanso.config.SecretBox
import dev.kanso.repo.text
import dev.kanso.repo.timestamp
import dev.kanso.repo.timestampOrNull
import dev.kanso.repo.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * `github_accounts`, which is the whole of what a member's consent leaves behind.
 *
 * Raw SQL beside [GithubRepository] and for the reason that file gives: the write is an
 * `ON CONFLICT` on a natural key, and the read the feed depends on is a `lower()` match
 * against a functional index. Neither is expressible in the Exposed DSL, and both are the
 * part worth being able to read.
 *
 * [SecretBox] is injected rather than the tokens being encrypted by callers, so that there
 * is no path into this table that stores a plaintext token — `access_token_enc` is `BYTEA`
 * and the `_enc` suffix is what stops the next reader handing the column to an HTTP
 * client, but only if the encrypting lives on this side of the boundary.
 */
@Repository
class GithubAccountRepository(
	private val jdbc: JdbcClient,
	private val secrets: SecretBox,
) {

	private val mapper = RowMapper { rs, _ ->
		GithubAccount(
			userId = rs.uuid("user_id"),
			githubUserId = rs.getLong("github_user_id"),
			githubLogin = rs.text("github_login"),
			expiresAt = rs.timestampOrNull("expires_at"),
			// The presence, never the token. `refresh_token_enc IS NOT NULL` is computed by
			// the statement rather than by reading the bytes into the JVM and asking, which
			// would pull a secret across a boundary to answer a question about its absence.
			refreshable = rs.getBoolean("refreshable"),
			linkedAt = rs.timestamp("linked_at"),
		)
	}

	private companion object {
		/**
		 * Every read goes through this list rather than `SELECT *`, so that
		 * `access_token_enc` and `refresh_token_enc` cannot arrive in a row nobody meant to
		 * ask for them in. [tokenFor] names them explicitly, which is the only place they
		 * are wanted.
		 */
		const val COLUMNS =
			"user_id, github_user_id, github_login, expires_at, " +
				"(refresh_token_enc IS NOT NULL) AS refreshable, linked_at"
	}

	// ------------------------------------------------------------------ consent

	/**
	 * Records a completed consent flow, or replaces the one this member already had.
	 *
	 * `ON CONFLICT (user_id)` is re-linking: walking the consent screen twice is ordinary —
	 * it is what a member does when their token expired with nothing to refresh it — and it
	 * must land on the row they already have rather than a second one.
	 *
	 * **The other conflict is not handled here, on purpose.** `github_user_id` is `UNIQUE`,
	 * so a member claiming a GitHub identity that already belongs to somebody else violates
	 * that constraint, and one `ON CONFLICT` clause cannot absorb two keys. Nor should it:
	 * the two outcomes are opposites. Re-linking is an update; claiming somebody else's
	 * identity is a refusal, because the `UNIQUE` exists precisely so that an inbound
	 * payload resolves to exactly one member — two members behind one login would make the
	 * feed name the wrong person, which is worse than naming nobody. [ownerOf] is what turns
	 * it into a sentence; the constraint stays underneath as the actual guard.
	 *
	 * Both token shapes go in as they arrived. A null [GithubToken.refreshToken] and a null
	 * [GithubToken.expiresAt] are the non-expiring shape and are stored as nulls rather than
	 * as an invented expiry, which is what `V36` means by "no refresh token has to be
	 * storable rather than assumed away".
	 */
	fun link(userId: UUID, githubUserId: Long, githubLogin: String, token: GithubToken): GithubAccount =
		jdbc.sql(
			"""
			INSERT INTO github_accounts (
				user_id, github_user_id, github_login,
				access_token_enc, refresh_token_enc, expires_at
			) VALUES (
				:userId, :githubUserId, :login,
				:accessToken, :refreshToken, :expiresAt
			)
			ON CONFLICT (user_id) DO UPDATE SET
				github_user_id    = EXCLUDED.github_user_id,
				github_login      = EXCLUDED.github_login,
				access_token_enc  = EXCLUDED.access_token_enc,
				refresh_token_enc = EXCLUDED.refresh_token_enc,
				expires_at        = EXCLUDED.expires_at,
				-- Re-consenting is consenting. The row's age is the age of the *current*
				-- grant, which is what a screen saying "linked in March" has to mean, or a
				-- member who reconnected yesterday reads a date that predates the token.
				linked_at         = now()
			RETURNING $COLUMNS
			""".trimIndent()
		)
			.param("userId", userId)
			.param("githubUserId", githubUserId)
			.param("login", githubLogin)
			.param("accessToken", secrets.encrypt(token.accessToken))
			.param("refreshToken", token.refreshToken?.let(secrets::encrypt))
			.param("expiresAt", token.expiresAt)
			.query(mapper)
			.single()

	/** Which member holds this GitHub identity, if anybody. The sentence behind the `UNIQUE`. */
	fun ownerOf(githubUserId: Long): UUID? = jdbc.sql(
		"SELECT user_id FROM github_accounts WHERE github_user_id = :id"
	)
		.param("id", githubUserId)
		.query(UUID::class.java)
		.optional()
		.orElse(null)

	fun find(userId: UUID): GithubAccount? = jdbc.sql(
		"SELECT $COLUMNS FROM github_accounts WHERE user_id = :userId"
	)
		.param("userId", userId)
		.query(mapper)
		.optional()
		.orElse(null)

	/**
	 * Forgets a member's grant.
	 *
	 * A `DELETE` and not a flag: the row *is* the consent, so withdrawing it has to remove
	 * the row — a `revoked_at` column would leave an encrypted token in the database after
	 * the person who owned it asked for it to be gone. Returns whether there was one, so
	 * unlinking twice is not an error.
	 *
	 * The name goes off the feed's future lines with it, and its past lines keep it:
	 * `activity.actor_id` is a foreign key to `users`, not to this table, so a row already
	 * written stays attributed. Withdrawing consent stops the next attribution rather than
	 * rewriting history, which is the only version of this that does not lose an audit log.
	 */
	fun unlink(userId: UUID): Boolean =
		jdbc.sql("DELETE FROM github_accounts WHERE user_id = :userId")
			.param("userId", userId)
			.update() == 1

	// ------------------------------------------------------------------ the name

	/**
	 * A GitHub identity on a payload, to a Kanso member — **the lookup this whole ticket
	 * exists for**, and the one that decides whether the feed reads *Elie moved KAN-142 to
	 * Done* or *KAN-142 moved to Done via #418*.
	 *
	 * `null` for anybody who never linked, and that is a correct answer rather than a
	 * missing one. It becomes `actor_id = NULL`, which `V8` has always allowed and which
	 * the feed already renders as a sentence with no person in it. Nothing here invents a
	 * placeholder member, and nothing returns the raw login as a stand-in for a name: an
	 * "unknown" in a feed is a claim about a person, and the honest line has no subject at
	 * all.
	 *
	 * **The id wins outright when it is there.** `github_user_id` is the identity that
	 * survives a rename, so when a payload carries one, a login that happens to match a
	 * different row is not a fallback — it is a wrong answer waiting for somebody to rename
	 * themselves and somebody else to take the name. The login lookup runs only when there
	 * is no id, which is the case `github_accounts_login_idx` was created for, lowercased
	 * because GitHub logins are case-insensitive and a payload's capitalisation is not
	 * stable enough to join on.
	 *
	 * Neither branch reads a token or an expiry. See [GithubTokenState].
	 */
	fun memberFor(githubUserId: Long?, login: String?): UUID? {
		if (githubUserId != null) return ownerOf(githubUserId)
		val name = login?.takeIf { it.isNotBlank() } ?: return null
		return jdbc.sql(
			"SELECT user_id FROM github_accounts WHERE lower(github_login) = lower(:login)"
		)
			.param("login", name)
			.query(UUID::class.java)
			.optional()
			.orElse(null)
	}

	// ------------------------------------------------------------------ the token

	/**
	 * The secrets, for the one caller that spends them.
	 *
	 * Separate from [find] rather than a field on [GithubAccount], so that reading a
	 * member's link — which every screen does — cannot decrypt a token by accident. Returns
	 * null when there is no row; a row always has an access token, because `V36` made the
	 * column `NOT NULL` on the argument that a row without one means nothing.
	 *
	 * `SecretBox.decrypt` answers null rather than throwing on a key that no longer opens
	 * the payload, so a rotated `kanso.security.secret-key` presents as an unusable link
	 * the member can re-establish, not as a 500. That is why the access token is nullable
	 * on the way out of a `NOT NULL` column.
	 */
	fun tokenFor(userId: UUID): GithubToken? = jdbc.sql(
		"SELECT access_token_enc, refresh_token_enc, expires_at FROM github_accounts WHERE user_id = :userId"
	)
		.param("userId", userId)
		.query { rs, _ ->
			GithubToken(
				accessToken = secrets.decrypt(rs.getBytes("access_token_enc")).orEmpty(),
				refreshToken = secrets.decrypt(rs.getBytes("refresh_token_enc")),
				expiresAt = rs.timestampOrNull("expires_at"),
			)
		}
		.optional()
		.orElse(null)
}

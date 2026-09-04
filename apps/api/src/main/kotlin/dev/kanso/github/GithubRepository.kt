package dev.kanso.github

import dev.kanso.repo.text
import dev.kanso.repo.timestampOrNull
import dev.kanso.repo.uuid
import dev.kanso.repo.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Every statement the inbound path needs, in raw SQL for the reason `OutboundJobRepository`
 * gives: the upserts here are `ON CONFLICT` on a natural key and the delivery record is
 * `ON CONFLICT DO NOTHING`, neither of which the Exposed DSL expresses, and both of which
 * are what make a redelivery a no-op instead of a duplicate.
 */
@Repository
class GithubRepository(private val jdbc: JdbcClient) {

	private val prMapper = RowMapper { rs, _ ->
		GithubPullRequest(
			id = rs.uuid("id"),
			installationId = rs.getLong("installation_id"),
			repoFullName = rs.text("repo_full_name"),
			number = rs.getInt("number"),
			title = rs.text("title"),
			url = rs.text("url"),
			state = PrState.from(rs.text("state")),
			draft = rs.getBoolean("draft"),
			// `PrReviewState.from` maps GitHub's five words onto two and null; a stored value
			// is already one of the two, so this is the same call doing nothing.
			reviewState = PrReviewState.from(rs.getString("review_state")),
			authorLogin = rs.getString("author_login"),
			headRef = rs.text("head_ref"),
			baseRef = rs.text("base_ref"),
			mergedAt = rs.timestampOrNull("merged_at"),
		)
	}

	// ------------------------------------------------------------------ installations

	/**
	 * Called from **any** payload that names an installation, not only from the
	 * `installation` event.
	 *
	 * `github_pull_requests.installation_id` is a foreign key, so a `pull_request` delivery
	 * that arrives before we have ever seen an `installation` one would fail on the FK — and
	 * that is not a corner case: Kanso can be installed on an organisation before this
	 * migration ran, and GitHub does not re-send the installation event for an App that was
	 * already there. Upserting from whatever arrives is what keeps "GitHub events do
	 * nothing" from being the first symptom.
	 *
	 * `suspended_at` is deliberately **not** touched here. It is lifecycle, owned by the
	 * `installation` event's own branches, and a `pull_request` payload carries no opinion
	 * about it — resetting it from one would silently un-suspend an App on the next push.
	 */
	fun rememberInstallation(id: Long, accountLogin: String, accountType: String) {
		jdbc.sql(
			"""
			INSERT INTO github_installations (id, account_login, account_type)
			VALUES (:id, :login, :type)
			ON CONFLICT (id) DO UPDATE SET
				account_login = EXCLUDED.account_login,
				account_type  = EXCLUDED.account_type
			""".trimIndent()
		)
			.param("id", id)
			.param("login", accountLogin)
			// GitHub sends 'Organization' or 'User'; anything else would trip the CHECK, which
			// is the correct place for it to stop rather than being coerced to a default here.
			.param("type", accountType)
			.update()
	}

	fun setInstallationSuspended(id: Long, suspended: Boolean) {
		jdbc.sql("UPDATE github_installations SET suspended_at = :at WHERE id = :id")
			.param("at", if (suspended) OffsetDateTime.now() else null)
			.param("id", id)
			.update()
	}

	/** The cascade takes the pull requests and their links with it. See `V36`. */
	fun forgetInstallation(id: Long) {
		jdbc.sql("DELETE FROM github_installations WHERE id = :id").param("id", id).update()
	}

	// ------------------------------------------------------------------ pull requests

	/**
	 * The whole of what makes the inbound path idempotent, and it returns the row's id so
	 * the caller can link without a second query.
	 *
	 * Keyed on `(repo_full_name, number)`, which is what a payload and a human both carry.
	 * GitHub sends `opened`, `synchronize`, `edited` and `closed` for one pull request and
	 * every one of them lands here, so applying the same delivery twice reaches the same row
	 * rather than a second one.
	 *
	 * **`review_state` is excluded from the update on purpose.** It is set by
	 * `pull_request_review` and a `pull_request` payload does not carry it, so writing
	 * `EXCLUDED.review_state` would erase an approval every time somebody pushed a commit.
	 * `merged_at` is coalesced rather than overwritten for the same shape of reason: a later
	 * payload for a merged pull request must not be able to forget when it merged.
	 */
	fun upsertPullRequest(
		installationId: Long,
		repoFullName: String,
		number: Int,
		nodeId: String,
		title: String,
		url: String,
		state: PrState,
		draft: Boolean,
		authorLogin: String?,
		headRef: String,
		baseRef: String,
		openedAt: OffsetDateTime?,
		mergedAt: OffsetDateTime?,
	): UUID = jdbc.sql(
		"""
		INSERT INTO github_pull_requests (
			installation_id, repo_full_name, number, node_id, title, url,
			state, draft, author_login, head_ref, base_ref, opened_at, merged_at
		) VALUES (
			:installationId, :repo, :number, :nodeId, :title, :url,
			:state, :draft, :author, :headRef, :baseRef, :openedAt, :mergedAt
		)
		ON CONFLICT (repo_full_name, number) DO UPDATE SET
			installation_id = EXCLUDED.installation_id,
			node_id         = EXCLUDED.node_id,
			title           = EXCLUDED.title,
			url             = EXCLUDED.url,
			state           = EXCLUDED.state,
			draft           = EXCLUDED.draft,
			author_login    = EXCLUDED.author_login,
			head_ref        = EXCLUDED.head_ref,
			base_ref        = EXCLUDED.base_ref,
			opened_at       = COALESCE(github_pull_requests.opened_at, EXCLUDED.opened_at),
			merged_at       = COALESCE(EXCLUDED.merged_at, github_pull_requests.merged_at),
			updated_at      = now()
		RETURNING id
		""".trimIndent()
	)
		.param("installationId", installationId)
		.param("repo", repoFullName)
		.param("number", number)
		.param("nodeId", nodeId)
		.param("title", title)
		.param("url", url)
		.param("state", state.wire)
		.param("draft", draft)
		.param("author", authorLogin)
		.param("headRef", headRef)
		.param("baseRef", baseRef)
		.param("openedAt", openedAt)
		.param("mergedAt", mergedAt)
		.query(UUID::class.java)
		.single()

	/** Set by `pull_request_review` alone, which is why it is not part of the upsert. */
	fun setReviewState(pullRequestId: UUID, reviewState: PrReviewState?) {
		jdbc.sql(
			"UPDATE github_pull_requests SET review_state = :state, updated_at = now() WHERE id = :id"
		)
			.param("state", reviewState?.wire)
			.param("id", pullRequestId)
			.update()
	}

	fun findByRepoAndNumber(repoFullName: String, number: Int): GithubPullRequest? = jdbc.sql(
		"SELECT * FROM github_pull_requests WHERE repo_full_name = :repo AND number = :number"
	)
		.param("repo", repoFullName)
		.param("number", number)
		.query(prMapper)
		.optional()
		.orElse(null)

	// ------------------------------------------------------------------ the links

	/**
	 * Draws a link, or updates the one that is there.
	 *
	 * `linked_by` is coalesced so that **re-parsing cannot demote a person's link to a
	 * detected one**. A member links KAN-142 by hand, somebody later renames the branch to
	 * mention it, the parser links it again with `linkedBy = null` — and `COALESCE` keeps
	 * the member. Without it the row would quietly become removable by the next edit, which
	 * is the exact failure the design names.
	 *
	 * `closes` *is* overwritten, because it is a fact about the pull request's current text
	 * rather than about who drew the link: a branch renamed away from a key should stop
	 * closing.
	 */
	fun link(ticketId: UUID, pullRequestId: UUID, closes: Boolean, linkedBy: UUID?) {
		jdbc.sql(
			"""
			INSERT INTO ticket_pull_requests (ticket_id, pull_request_id, closes, linked_by)
			VALUES (:ticketId, :prId, :closes, :linkedBy)
			ON CONFLICT (ticket_id, pull_request_id) DO UPDATE SET
				closes    = EXCLUDED.closes,
				linked_by = COALESCE(ticket_pull_requests.linked_by, EXCLUDED.linked_by)
			""".trimIndent()
		)
			.param("ticketId", ticketId)
			.param("prId", pullRequestId)
			.param("closes", closes)
			.param("linkedBy", linkedBy)
			.update()
	}

	/**
	 * Removes the links this pull request no longer names — **and only the detected ones.**
	 *
	 * `linked_by IS NULL` is the one `if` that carries "automation does not undo a person",
	 * and it is in the `WHERE` clause rather than in Kotlin so that no caller can forget it.
	 * A member's link survives an edit that removes the mention, a retitle, and everything
	 * except that member unlinking it.
	 *
	 * An empty [keep] is a real instruction — a pull request whose keys were all removed —
	 * so the `<> ALL` form is used rather than `NOT IN`, which is what makes an empty array
	 * mean "keep none" instead of matching nothing.
	 */
	fun removeDetectedLinksExcept(pullRequestId: UUID, keep: Collection<UUID>): Int = jdbc.sql(
		"""
		DELETE FROM ticket_pull_requests
		WHERE pull_request_id = :prId
		  AND linked_by IS NULL
		  AND ticket_id <> ALL (:keep)
		""".trimIndent()
	)
		.param("prId", pullRequestId)
		.param("keep", keep.toTypedArray())
		.update()

	/** Which tickets this pull request may move. The merge handler's read. */
	fun ticketsClosedBy(pullRequestId: UUID): List<UUID> = jdbc.sql(
		"SELECT ticket_id FROM ticket_pull_requests WHERE pull_request_id = :prId AND closes"
	)
		.param("prId", pullRequestId)
		.query(UUID::class.java)
		.list()
		// `ticket_id` is `NOT NULL`, so this drops nothing; it is what turns JDBC's
		// platform type into a list Kotlin will not let a caller dereference unchecked.
		.filterNotNull()

	/**
	 * Every ticket's pull requests, for a whole page in one statement.
	 *
	 * Batched by design, following what `TicketDetails.of` already does for assignees, docs
	 * and field values: a list of 200 tickets costs one more query than it did before this
	 * feature, not 200. Ordered newest-first by the pull request's own number within a
	 * repository, so the row a person is waiting on is at the top.
	 *
	 * **The `LEFT JOIN` on `github_accounts` is what a member's consent buys**, and it is a
	 * join rather than a second query because it costs nothing: `github_accounts_login_idx`
	 * is the functional index `V36` created for exactly this lookup. `LEFT` and not `INNER`
	 * is the load-bearing word — an inner join would drop every pull request whose author
	 * never linked, which is most of them, and turn a missing name into a missing row.
	 *
	 * Matched on `lower(github_login)` and not on `github_user_id`, because a
	 * `github_pull_requests` row carries the author's *login* and not their id — the id
	 * path is the one an inbound payload takes, and it lives in
	 * `GithubAccountRepository.memberFor`. Lowercased on both sides because GitHub logins
	 * are case-insensitive and a payload's capitalisation is not stable enough to join on.
	 */
	fun forTickets(ticketIds: Collection<UUID>): Map<UUID, List<TicketPullRequest>> {
		if (ticketIds.isEmpty()) return emptyMap()
		val rows = jdbc.sql(
			"""
			SELECT tpr.ticket_id, tpr.closes, tpr.linked_by, ga.user_id AS author_user_id, pr.*
			FROM ticket_pull_requests tpr
			JOIN github_pull_requests pr ON pr.id = tpr.pull_request_id
			LEFT JOIN github_accounts ga ON lower(ga.github_login) = lower(pr.author_login)
			WHERE tpr.ticket_id = ANY (:ids)
			ORDER BY pr.repo_full_name, pr.number DESC
			""".trimIndent()
		)
			.param("ids", ticketIds.toTypedArray())
			.query { rs, i ->
				rs.uuid("ticket_id") to TicketPullRequest(
					pullRequest = prMapper.mapRow(rs, i)!!,
					closes = rs.getBoolean("closes"),
					// The *presence* of a member, never the id: nothing downstream needs to
					// know which member drew a link, only that automation must not undo it.
					linkedByMember = rs.getObject("linked_by") != null,
					// Here the id *is* wanted, because the answer is a person to name rather
					// than a rule to apply. Null for an author who never consented.
					authorUserId = rs.uuidOrNull("author_user_id"),
				)
			}
			.list()
		return rows.groupBy({ it.first }, { it.second })
	}

	// ------------------------------------------------------------------ deliveries

	/**
	 * Records a delivery, and says whether it is the first time.
	 *
	 * `false` means GitHub has retried and the caller must do nothing. The pull request
	 * upsert would survive being applied twice; the activity feed would not, and a history
	 * saying a ticket moved to Done twice is a history somebody has to explain.
	 *
	 * Insert-or-skip rather than a `SELECT` then an `INSERT`, because two deliveries of the
	 * same event can be in flight at once and the check-then-act version lets both through.
	 */
	fun firstDelivery(deliveryId: UUID): Boolean = jdbc.sql(
		"INSERT INTO github_deliveries (delivery_id) VALUES (:id) ON CONFLICT DO NOTHING"
	)
		.param("id", deliveryId)
		.update() == 1

	fun pruneDeliveriesBefore(cutoff: OffsetDateTime): Int = jdbc.sql(
		"DELETE FROM github_deliveries WHERE received_at < :cutoff"
	)
		.param("cutoff", cutoff)
		.update()
}

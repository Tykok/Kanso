package dev.kanso.github

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Where a pull request is, in three words rather than two booleans.
 *
 * `merged` and `closed` are not independent — a merged pull request is closed — so a pair
 * of booleans would admit a fourth combination GitHub cannot produce and every reader would
 * have to know the rule. Closed here and by `github_pull_requests_state_chk`; see `V36`.
 */
enum class PrState(val wire: String) {
	OPEN("open"),
	MERGED("merged"),
	CLOSED("closed");

	companion object {
		fun from(raw: String): PrState = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown pull request state '$raw'")
	}
}

/**
 * Whether somebody has blocked or unblocked a pull request. Two words where GitHub's own
 * review vocabulary has five.
 *
 * `commented`, `dismissed` and `pending` all mean *no change* here, and that narrowing
 * lives in [from] rather than in the display so two readers cannot disagree about what a
 * `commented` review meant. Absence is a real value — nobody has reviewed yet — which is
 * why there is no `PENDING` entry: a fourth word would give "not reviewed" two spellings.
 */
enum class PrReviewState(val wire: String) {
	APPROVED("approved"),
	CHANGES_REQUESTED("changes_requested");

	companion object {
		/** Null for every review GitHub sends that does not move the pill. */
		fun from(raw: String?): PrReviewState? = when (raw?.lowercase()) {
			"approved" -> APPROVED
			"changes_requested" -> CHANGES_REQUESTED
			else -> null
		}
	}
}

/** A pull request as Kanso last heard about it. One row of `github_pull_requests`. */
data class GithubPullRequest(
	val id: UUID,
	val installationId: Long,
	val repoFullName: String,
	val number: Int,
	val title: String,
	val url: String,
	val state: PrState,
	val draft: Boolean,
	val reviewState: PrReviewState?,
	val authorLogin: String?,
	val headRef: String,
	val baseRef: String,
	val mergedAt: OffsetDateTime?,
)

/**
 * A pull request *on a ticket* — the join row's two columns carried alongside it, because
 * every reader of one wants the other.
 *
 * [closes] says this pull request may move the ticket. [linkedByMember] is what makes
 * "automation does not undo a person" expressible: false for a link `PrLinkParser` found,
 * true for one somebody drew, and only the first kind is ever removed by parsing again.
 */
data class TicketPullRequest(
	val pullRequest: GithubPullRequest,
	val closes: Boolean,
	val linkedByMember: Boolean,
)

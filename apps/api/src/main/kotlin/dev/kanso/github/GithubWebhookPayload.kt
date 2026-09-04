package dev.kanso.github

import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/**
 * What Kanso reads out of a GitHub delivery, and nothing else.
 *
 * **A reader over `JsonNode` rather than data classes bound by Jackson**, following
 * `GithubUserOAuth` and `MappedPageReader`. A `pull_request` payload carries some four
 * hundred fields across a dozen nested objects; binding it would mean either modelling all
 * of them or annotating every class to ignore the rest, and the failure mode of the second
 * is the one that matters — the day GitHub adds or renames a field this code does not read,
 * a bound payload can refuse the whole delivery while a reader carries on not reading it.
 *
 * Every accessor answers null rather than throwing, for the same reason: this is input from
 * the network on an endpoint that has already proved its signature, and a delivery whose
 * shape surprises us is one to drop with a line in the log — never a 500, which GitHub
 * would retry for three days.
 */
object GithubWebhookPayload {

	/**
	 * A `pull_request` delivery, flattened.
	 *
	 * [merged] is carried beside [state] because the payload says both and they answer
	 * different questions: `action = closed` with `merged = true` is the only merge signal
	 * GitHub sends, and `state` is merely `closed` by then. Reading them together here is
	 * what lets the service ask `if (merged)` rather than re-deriving it.
	 */
	data class PullRequestEvent(
		val action: String,
		val installationId: Long,
		val repoFullName: String,
		val ownerLogin: String,
		val ownerType: String,
		val number: Int,
		val nodeId: String,
		val title: String,
		val body: String?,
		val url: String,
		val state: PrState,
		val merged: Boolean,
		val draft: Boolean,
		val authorLogin: String?,
		val authorId: Long?,
		val headRef: String,
		val baseRef: String,
		val openedAt: OffsetDateTime?,
		val mergedAt: OffsetDateTime?,
		val senderLogin: String?,
		val senderId: Long?,
		/**
		 * **The event's own timestamp, which is guard two's whole point** — never `now()`. A
		 * delivery retried an hour later must not win a race it lost when it was first sent,
		 * so the comparison needs the instant GitHub acted rather than the instant we heard.
		 *
		 * `merged_at` when there is one, because for the transition that matters it *is* the
		 * event; `updated_at` otherwise. Both are read off the pull request rather than off
		 * the delivery, which carries no timestamp worth trusting.
		 */
		val eventAt: OffsetDateTime,
	)

	/** An `installation` delivery. Lifecycle only — there is no repositories table. */
	data class InstallationEvent(
		val action: String,
		val installationId: Long,
		val accountLogin: String,
		val accountType: String,
	)

	/** A `pull_request_review` delivery. Displayed, and it transitions nothing. */
	data class ReviewEvent(
		val repoFullName: String,
		val number: Int,
		val reviewState: PrReviewState?,
	)

	fun pullRequest(root: JsonNode): PullRequestEvent? {
		val pr = root.path("pull_request")
		val repo = root.path("repository")
		val merged = pr.path("merged").asBoolean(false)
		val mergedAt = instant(pr.path("merged_at"))
		return PullRequestEvent(
			action = root.path("action").asText(null) ?: return null,
			installationId = number(root.path("installation").path("id")) ?: return null,
			repoFullName = text(repo.path("full_name")) ?: return null,
			// The installation's account, taken from the repository's owner because a
			// `pull_request` payload's `installation` object carries only an id — while
			// `github_installations` needs a login and a type that are both `NOT NULL`. They
			// agree by construction: every repository in an installation belongs to the
			// account the App was installed on. `V36` argues why this upsert cannot wait for
			// the `installation` event, and the alternative is an FK violation on a webhook,
			// which presents as "GitHub events do nothing".
			ownerLogin = text(repo.path("owner").path("login")) ?: return null,
			ownerType = text(repo.path("owner").path("type")) ?: return null,
			number = number(pr.path("number"))?.toInt() ?: return null,
			nodeId = text(pr.path("node_id")) ?: return null,
			title = pr.path("title").asText("") ,
			body = text(pr.path("body")),
			url = text(pr.path("html_url")) ?: return null,
			// `state` is read rather than derived from [merged], and it is the one field a
			// delivery is dropped over: a third word GitHub has not sent before means its
			// vocabulary has grown past `github_pull_requests_state_chk`, and guessing would
			// write a row the CHECK is about to refuse anyway, one layer further from the cause.
			state = if (merged) PrState.MERGED else state(pr.path("state")) ?: return null,
			merged = merged,
			draft = pr.path("draft").asBoolean(false),
			// GitHub's `user` is null for a pull request whose author has since deleted their
			// account — which is why `author_login` is nullable — so both halves are read
			// independently and neither is required.
			authorLogin = text(pr.path("user").path("login")),
			authorId = number(pr.path("user").path("id")),
			headRef = text(pr.path("head").path("ref")) ?: return null,
			baseRef = text(pr.path("base").path("ref")) ?: return null,
			openedAt = instant(pr.path("created_at")),
			mergedAt = mergedAt,
			senderLogin = text(root.path("sender").path("login")),
			senderId = number(root.path("sender").path("id")),
			eventAt = mergedAt ?: instant(pr.path("updated_at")) ?: OffsetDateTime.now(),
		)
	}

	fun installation(root: JsonNode): InstallationEvent? {
		val installation = root.path("installation")
		return InstallationEvent(
			action = root.path("action").asText(null) ?: return null,
			installationId = number(installation.path("id")) ?: return null,
			accountLogin = text(installation.path("account").path("login")) ?: return null,
			accountType = text(installation.path("account").path("type")) ?: return null,
		)
	}

	fun review(root: JsonNode): ReviewEvent? = ReviewEvent(
		repoFullName = text(root.path("repository").path("full_name")) ?: return null,
		number = number(root.path("pull_request").path("number"))?.toInt() ?: return null,
		// `PrReviewState.from` is what narrows GitHub's five review words to the two that
		// move the pill: `commented`, `dismissed` and `pending` all arrive here and all mean
		// null. Not repeated — the narrowing lives in `Github.kt` so two readers cannot
		// disagree about what a `commented` review meant.
		reviewState = PrReviewState.from(text(root.path("review").path("state"))),
	)

	/** Blank is absent. An empty string in a `full_name` is not a repository. */
	private fun text(node: JsonNode): String? = node.asText(null)?.takeIf { it.isNotBlank() }

	/**
	 * Numbers are read as numbers rather than coerced from text, so a `"number": "418"` —
	 * which GitHub does not send and a forged payload might — is absent rather than 418.
	 */
	private fun number(node: JsonNode): Long? = node.takeIf { it.isNumber }?.asLong()

	private fun state(node: JsonNode): PrState? =
		text(node)?.let { runCatching { PrState.from(it) }.getOrNull() }

	/**
	 * GitHub's timestamps are ISO-8601 with a `Z`. A null or an unparseable one is null and
	 * not an exception: `opened_at` and `merged_at` are both nullable columns, so an absent
	 * instant has somewhere to go.
	 */
	private fun instant(node: JsonNode): OffsetDateTime? =
		text(node)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
}

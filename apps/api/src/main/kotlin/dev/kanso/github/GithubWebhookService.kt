package dev.kanso.github

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.ActivityService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The fan-out: one verified delivery becomes rows, links and at most one status move.
 *
 * Named by `V36` before it existed, which is why it is named this. Everything it calls was
 * written by KAN-18 and had **no caller in production** — `PrTransition`, `upsertPullRequest`,
 * `ticketsClosedBy` and `firstDelivery` were all reachable only from their own tests, so
 * `github_pull_requests` had no write point and the fallback sentence `V36` documents had
 * never once executed. This file is what arms all of it.
 *
 * **The signature is not checked here.** `GithubWebhookController` does that against the raw
 * bytes and does not call this method until it passes, which is the only ordering that works
 * — see `GithubSignature` for why a parsed object cannot be re-signed. This class therefore
 * takes bytes it is entitled to trust and never sees a request.
 */
@Service
class GithubWebhookService(
	private val github: GithubRepository,
	private val accounts: GithubAccountRepository,
	private val teams: TeamRepository,
	private val tickets: TicketRepository,
	private val ticketService: TicketService,
	private val users: UserRepository,
	private val access: TicketAccess,
	private val activity: ActivityService,
	private val activityLog: ActivityRepository,
	private val objectMapper: ObjectMapper,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * One delivery, one transaction.
	 *
	 * The transaction is what makes [GithubRepository.firstDelivery] mean anything: the
	 * delivery row and everything the fan-out writes commit together, so a fan-out that
	 * fails half way takes the marker with it and GitHub's retry is a clean retry rather
	 * than a delivery recorded as handled and half applied.
	 *
	 * Nothing in here throws on a payload it does not understand. GitHub retries a 5xx for
	 * about three days, so a delivery whose shape surprises us is dropped with a line in the
	 * log; the endpoint's answer is the same either way, which is also what stops it being
	 * an oracle telling a stranger which payloads got somewhere.
	 */
	@Transactional
	fun receive(event: String, deliveryId: UUID, body: ByteArray) {
		// First, and before anything is parsed. GitHub retries, and what cannot survive being
		// applied twice is the feed — `V36` is explicit that the pull request upsert would be
		// idempotent on its own and the activity rows would not.
		if (!github.firstDelivery(deliveryId)) {
			log.debug("GitHub delivery {} is a redelivery; nothing to do", deliveryId)
			return
		}
		val root = runCatching { objectMapper.readTree(body) }.getOrNull()
		if (root == null) {
			log.warn("GitHub delivery {} carried a body that is not JSON", deliveryId)
			return
		}
		when (event) {
			"pull_request" -> pullRequest(root, deliveryId)
			"pull_request_review" -> review(root)
			"installation" -> installation(root)
			// Logged and stored nowhere, which is a decision rather than an omission: there
			// is no repositories table, because the selection is edited on GitHub by people
			// who are not looking at Kanso and a mirror of it goes stale silently. Every
			// inbound payload carries `installation.id` and `repository.full_name`, which is
			// all routing needs. `V36` argues it.
			"installation_repositories" ->
				log.info("GitHub changed the repository selection on installation {}", root.path("installation").path("id"))
			else -> log.debug("GitHub sent a '{}' event, which Kanso does not subscribe to", event)
		}
	}

	// --- pull requests -------------------------------------------------------

	private fun pullRequest(root: JsonNode, deliveryId: UUID) {
		val event = GithubWebhookPayload.pullRequest(root)
		if (event == null) {
			log.warn("GitHub delivery {} is a pull_request Kanso could not read", deliveryId)
			return
		}
		// The one action that is answered with nothing at all, and the only one that can be:
		// new commits change no field the parser reads and no field the row stores, so an
		// upsert would be a write that says nothing on the noisiest event GitHub sends.
		if (event.action == SYNCHRONIZE) return

		// Before the pull request, because the pull request references it. `V36`: an
		// installation row may not exist yet — Kanso can have been installed before the
		// migration ran, and a `pull_request` delivery can be the first one that ever arrives
		// — and the alternative is an FK violation presenting as "GitHub events do nothing".
		github.rememberInstallation(event.installationId, event.ownerLogin, event.ownerType)
		val pullRequestId = github.upsertPullRequest(
			installationId = event.installationId,
			repoFullName = event.repoFullName,
			number = event.number,
			nodeId = event.nodeId,
			title = event.title,
			url = event.url,
			state = event.state,
			draft = event.draft,
			authorLogin = event.authorLogin,
			headRef = event.headRef,
			baseRef = event.baseRef,
			openedAt = event.openedAt,
			mergedAt = event.mergedAt,
		)

		reparse(pullRequestId, event)
		targetFor(event)?.let { move(pullRequestId, event, it) }
	}

	/**
	 * Which status this event asks for, or null for the events that ask for none.
	 *
	 * **Two transitions, not five**, and the two absences are the argued part.
	 * `converted_to_draft` sending a ticket back to In progress and `closed` sending it back
	 * are both defensible and both rejected together: they are the automatic *backward*
	 * moves, and an automatic backward move is the origin of every "why did my ticket
	 * change" conversation. Forward is inferable; backward is a judgement.
	 *
	 * An `opened` pull request that is not a draft transitions too, and that clause is easy
	 * to leave out: `ready_for_review` fires only when a draft is *promoted*, so a pull
	 * request opened ready would otherwise move nothing at all — which is most pull requests.
	 */
	private fun targetFor(event: GithubWebhookPayload.PullRequestEvent): TicketStatus? = when {
		event.merged -> TicketStatus.DONE
		event.action == READY_FOR_REVIEW -> TicketStatus.IN_REVIEW
		event.action == OPENED && !event.draft -> TicketStatus.IN_REVIEW
		else -> null
	}

	/**
	 * The links this pull request's text currently justifies, and only those.
	 *
	 * Run on every action except `synchronize` rather than on the four the design names
	 * (`opened`, `edited`, `reopened`, `ready_for_review`). The difference is `closed`, and
	 * it is deliberate: re-parsing there is an exact no-op for a pull request Kanso has seen
	 * before — the same text yields the same links and [GithubRepository.link] is an upsert —
	 * while it is the *only* thing that makes a merge work for a pull request that was opened
	 * before the App was installed, whose first delivery is its last. A gap that costs one
	 * `when` branch to close was not worth leaving for the report.
	 *
	 * Unknown keys drop out here rather than raising: `PrLinkParser` returns candidates and
	 * `ARCH-12` in a body is somebody else's tracker, not a refusal.
	 */
	private fun reparse(pullRequestId: UUID, event: GithubWebhookPayload.PullRequestEvent) {
		val links = PrLinkParser.parse(event.headRef, event.title, event.body)
		val resolved = links.mapNotNull { link -> ticketFor(link.key)?.let { it.id to link.closes } }
		// Read before the writes: `link` upserts, so afterwards there is no way to tell a link
		// this delivery drew from one that already existed — and the feed row belongs only to
		// the first. See `GithubRepository.linkedTickets`.
		val known = github.linkedTickets(pullRequestId).toSet()
		// The author, not the sender: this row answers "why is this pull request on my
		// ticket", and the answer is whoever wrote the pull request that names it. Null for an
		// author who never linked their GitHub account, which is most authors on most
		// repositories and is the documented state rather than a missing value.
		val author = accounts.memberFor(event.authorId, event.authorLogin)

		for ((ticketId, closes) in resolved) {
			github.link(ticketId, pullRequestId, closes, linkedBy = null)
			if (ticketId in known) continue
			activity.record(
				ActivityEntity.TICKET,
				ticketId,
				author,
				ActivityKind.PULL_REQUEST_LINKED,
				mapOf(
					"repo" to event.repoFullName,
					"number" to event.number,
					"url" to event.url,
					"closes" to closes,
				),
			)
		}
		// Only the detected ones, and the `WHERE linked_by IS NULL` that guarantees it is in
		// the SQL rather than here, so no caller can forget it: **automation does not undo a
		// person.** A link a member drew by hand survives an edit that removes the mention.
		github.removeDetectedLinksExcept(pullRequestId, resolved.map { it.first })
	}

	/**
	 * `KAN-142` to a live ticket, or null.
	 *
	 * Null and not an exception for the reason `PrLinkParser` is pure: the parser is liberal
	 * about what a key looks like precisely so that narrowing to teams that exist happens
	 * here, where the team list is. A pull request that mentions another tracker is not a
	 * pull request Kanso refuses.
	 */
	private fun ticketFor(key: String): Ticket? {
		val split = key.lastIndexOf('-').takeIf { it > 0 } ?: return null
		val number = key.substring(split + 1).toIntOrNull() ?: return null
		// `findByKey` is asked in upper case because that is how Kanso stores a team key and
		// `PrLinkParser` already normalises what it found on a lowercase branch name.
		val team = teams.findByKey(key.substring(0, split).uppercase()) ?: return null
		return tickets.findByTeamAndNumber(team.id, number)
	}

	// --- the transition ------------------------------------------------------

	/**
	 * At most one status move per ticket this pull request closes.
	 *
	 * `closes = true` is passed to [PrTransition.decide] because
	 * [GithubRepository.ticketsClosedBy] has already answered that question in its `WHERE`
	 * clause — guard three is enforced in SQL, so what is passed here states what the query
	 * guarantees rather than bypassing it. A bare-mention link is never in this list.
	 */
	private fun move(
		pullRequestId: UUID,
		event: GithubWebhookPayload.PullRequestEvent,
		target: TicketStatus,
	) {
		// **The sender, and the author only as a fallback.** The sender is by definition
		// whoever performed the action this transition is caused by, which is the case the
		// design singles out — a merge somebody other than the author performed — and it is
		// the right answer for `ready_for_review` too. GitHub puts a sender on every webhook,
		// so the fallback is very nearly unreachable; it is there because `author_login` is
		// the field the row stores and the one a reader would expect to be asked.
		val member = accounts.memberFor(event.senderId, event.senderLogin)
			?: accounts.memberFor(event.authorId, event.authorLogin)
		val via = "${event.repoFullName}#${event.number}"

		for (ticketId in github.ticketsClosedBy(pullRequestId)) {
			val ticket = tickets.findById(ticketId) ?: continue
			val decision = PrTransition.decide(
				closes = true,
				current = ticket.status,
				target = target,
				lastHumanStatusChangeAt = activityLog.lastHumanStatusChangeAt(ticketId),
				eventAt = event.eventAt,
			)
			if (decision !is TransitionDecision.Move) {
				log.debug("{} does not move {}: {}", via, ticketId, decision)
				continue
			}
			// Through `TicketService` and never through the repository, which is the premise
			// the whole design opens with: **an integration is not a new kind of user.** A
			// pull request does not gain rights over a ticket — it becomes a *reason* a
			// ticket moved. Going this way is what keeps the mirror push, the feed row, the
			// assignees' notification and the schedule cascade attached to a move that
			// arrived from GitHub, rather than a status column somebody edited behind them.
			ticketService.patch(
				actor = actorFor(member, ticket),
				id = ticketId,
				patch = TicketPatch(status = decision.to),
				viaPullRequest = via,
			)
		}
	}

	/**
	 * Who to credit — and this is **attribution, not authorisation.**
	 *
	 * The distinction is the subtle one in this file. Whether the ticket moves was settled
	 * before we got here, by the installation an admin performed, by `closes`, and by
	 * `PrTransition`'s three guards. This only decides whose name the feed reads: a member
	 * when the sender resolves through `github_accounts`, and nobody when they do not —
	 * *KAN-142 moved to Done via #418*, which `V36` calls the documented fallback and not a
	 * degradation, and which until this file existed had never actually run.
	 *
	 * **So the check is here rather than left to `TicketService`**, and the reason is
	 * mechanical as well as principled. Principled: a member Kanso can name is a member whose
	 * seat and teams it can ask about, and borrowing an identity we may not act as would
	 * launder a viewer's merge into an edit — `TicketAccess.requireSeatThatWrites` is
	 * documented as the deep half of the read-only seat, catching exactly the callers that
	 * never touch HTTP. Mechanical: `TicketService.patch` is `@Transactional` and joins this
	 * transaction, so letting it throw `AccessDeniedException` here and catching it would mark
	 * the whole delivery rollback-only — the refusal has to be asked *before* the call, not
	 * caught after it.
	 *
	 * The move still happens with no actor, and that is coherent rather than a hole: it
	 * happens for an author nobody has linked too, because its authority is the repository
	 * and the App installed on it, never the person. What a resolvable member adds is a name.
	 */
	private fun actorFor(memberId: UUID?, ticket: Ticket): User? {
		val member = memberId?.let { users.findById(it) } ?: return null
		if (member.instanceRole.mayWrite && access.mayEdit(member, ticket)) return member
		log.debug("{} may not edit {}, so the move is recorded with no actor", member.id, ticket.id)
		return null
	}

	// --- reviews and installations -------------------------------------------

	private fun review(root: JsonNode) {
		if (root.path("action").asText(null) != SUBMITTED) return
		val event = GithubWebhookPayload.review(root) ?: return
		// **A null is "no change", never "clear the approval".** `PrReviewState.from` maps
		// `commented`, `dismissed` and `pending` to null, and writing that would erase an
		// approval every time somebody left a comment. Returning here is what makes the two
		// meanings of null different in the one place they could be confused.
		val state = event.reviewState ?: return
		val pullRequest = github.findByRepoAndNumber(event.repoFullName, event.number) ?: return
		github.setReviewState(pullRequest.id, state)
	}

	private fun installation(root: JsonNode) {
		val event = GithubWebhookPayload.installation(root) ?: return
		when (event.action) {
			"created", "new_permissions_accepted" ->
				github.rememberInstallation(event.installationId, event.accountLogin, event.accountType)
			// The cascade takes the pull requests and their links with it: a state pill nobody
			// can refresh is worse than an absent one. `V36` argues the direction.
			"deleted" -> github.forgetInstallation(event.installationId)
			// Remembered first and suspended second, in that order, because
			// `rememberInstallation` deliberately does not touch `suspended_at` — so an
			// installation Kanso has never seen still gets a row to carry the flag.
			"suspend" -> {
				github.rememberInstallation(event.installationId, event.accountLogin, event.accountType)
				github.setInstallationSuspended(event.installationId, suspended = true)
			}
			"unsuspend" -> {
				github.rememberInstallation(event.installationId, event.accountLogin, event.accountType)
				github.setInstallationSuspended(event.installationId, suspended = false)
			}
			else -> log.debug("GitHub sent installation action '{}', which changes nothing here", event.action)
		}
	}

	private companion object {
		const val SYNCHRONIZE = "synchronize"
		const val READY_FOR_REVIEW = "ready_for_review"
		const val OPENED = "opened"
		const val SUBMITTED = "submitted"
	}
}

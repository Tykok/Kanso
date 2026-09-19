package dev.kanso.github

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.Ticket
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.ActivityService
import dev.kanso.service.StatusCategories
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
	private val statusCategories: StatusCategories,
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
	private fun targetFor(event: GithubWebhookPayload.PullRequestEvent): Target? = when {
		event.merged -> Target(DefaultStatus.DONE.wire, StatusCategory.COMPLETED)
		event.action == READY_FOR_REVIEW -> Target(DefaultStatus.IN_REVIEW.wire, StatusCategory.STARTED)
		event.action == OPENED && !event.draft -> Target(DefaultStatus.IN_REVIEW.wire, StatusCategory.STARTED)
		else -> null
	}

	/**
	 * Where an event wants the ticket: a preferred word, and the meaning it settles for.
	 *
	 * **This door is the exception to `KAN-90`'s rule for the eight hard-coded writes**,
	 * and the reason is that the category vocabulary cannot express what it means. The
	 * seven others name a meaning and take the team's first status of it. This one wants
	 * *in review*, and `StatusCategory` has no value for review — `in_progress` and
	 * `in_review` are both `STARTED`, deliberately, because a reviewer is work in flight.
	 * So a meaning-only rule would resolve every opened pull request to *in progress* and
	 * quietly retire the transition this feature exists for, on every instance, including
	 * ones whose teams changed nothing. `V36` documents the sentence it writes.
	 *
	 * Hence [key] first and [category] only as the fallback: a team that renamed its
	 * statuses keeps today's behaviour exactly, because a rename never moves a key; a team
	 * that *removed* `in_review` gets its first started status instead; and a team with no
	 * started status at all is left alone, which is the other half of this door's
	 * exception — a pull request opening must not invent a movement nobody asked for.
	 */
	private data class Target(val key: String, val category: StatusCategory)

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
				// `via_pr` under that name because it is the key the feed reads — the same
				// reader, `viaPr`, serves this kind and `status_changed`, so a second
				// spelling here would make one of the two sentences say "a pull request"
				// where the other names it.
				mapOf(
					"via_pr" to "#${event.number}",
					"repo" to event.repoFullName,
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
		target: Target,
	) {
		// **The sender, and the author only as a fallback.** The sender is by definition
		// whoever performed the action this transition is caused by, which is the case the
		// design singles out — a merge somebody other than the author performed — and it is
		// the right answer for `ready_for_review` too. GitHub puts a sender on every webhook,
		// so the fallback is very nearly unreachable; it is there because `author_login` is
		// the field the row stores and the one a reader would expect to be asked.
		val member = accounts.memberFor(event.senderId, event.senderLogin)
			?: accounts.memberFor(event.authorId, event.authorLogin)
		// **`#418`, and not `tykok/kanso#418`.** The repository-qualified form is more precise
		// and it is the wrong value, because the consumer is already written and already
		// tested: `project-copy.ts`'s `viaPr` prepends a `#` to anything that does not start
		// with one, so the qualified form prints *via #tykok/kanso#418* — two hashes and a
		// path, which is the "feed somebody has to explain" that KAN-74's own test exists to
		// prevent. Nothing is lost: `github_pull_requests.repo_full_name` holds the
		// repository, and the feed's job is a short sentence. `V36` documents this exact
		// string — *KAN-142 moved to Done via #418*.
		val via = "#${event.number}"

		for (ticketId in github.ticketsClosedBy(pullRequestId)) {
			val ticket = tickets.findById(ticketId) ?: continue
			// **Per ticket, not per event** — `KAN-90`. One pull request can close tickets
			// in several teams, and each team names the destination itself: its first
			// status of the target category, by the position it chose. Resolving once for
			// the event would write one team's word onto another team's ticket, which
			// `tickets_status_fk` would refuse — as a 500 on a webhook delivery GitHub then
			// retries.
			val catalogue = statusCategories.forTeam(ticket.teamId)
			// The word this event prefers, then the meaning it settles for — see [Target].
			val to = target.key.takeIf { it in catalogue }
				?: catalogue.entries.firstOrNull { it.value == target.category }?.key
			if (to == null) {
				// The ticket is left alone, and this is the one door that does not refuse.
				// A team with no status of this meaning has said something by not having
				// one, and a pull request opening must not invent a movement nobody asked
				// for — the alternative is automation picking a status the team removed.
				log.debug("{} does not move {}: team has no {} status", via, ticketId, target.category.wire)
				continue
			}
			val decision = PrTransition.decide(
				closes = true,
				current = ticket.status,
				target = to,
				catalogue = catalogue,
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
			// **No member to act as, no move.** `actorFor` answers null both for "nobody is
			// linked" and for "this member may not edit this ticket", and `TicketService.patch`
			// skips `access.require` entirely on a null actor — so the second answer was a way
			// past the check rather than a note about attribution. What arrives here is text
			// the *pull request's author* wrote: `PrLinkParser` reads the branch name, the
			// title and the body, and `opened` needs no relationship with the repository at
			// all. On a public repository that is a stranger typing `Fixes KAN-142` and moving
			// a ticket in a team they are not in, on an instance where they have no account;
			// with an account on a read-only seat it is `requireSeatThatWrites` bypassed. The
			// feed sentence `V36` documents — *KAN-142 moved to Done via #418*, nobody named —
			// was an argument about **attribution**, and the link row below still writes it.
			// It was never an argument for moving the ticket.
			val actor = actorFor(member, ticket)
			if (actor == null) {
				log.debug("{} does not move {}: nobody it may be attributed to", via, ticketId)
				continue
			}
			ticketService.patch(
				actor = actor,
				id = ticketId,
				patch = TicketPatch(status = decision.to),
				viaPullRequest = via,
			)
		}
	}

	/**
	 * Who to act as — and who to credit, which this file used to treat as the same question
	 * answered for one reason and now treats as the same question answered for two.
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
	 * **The move no longer happens with no actor.** It used to, on the argument that the
	 * authority here is the repository and the App installed on it rather than the person —
	 * and that argument holds for everything on this delivery *except the part a stranger
	 * writes*. The installation is an admin's act; `Fixes KAN-142` in a pull request title is
	 * not, and `targetFor` accepts it on `opened` from anyone who can open one. With no
	 * actor, `TicketService.patch` takes its `requireStatusOnly` branch, which checks that no
	 * field but `status` is set and checks nothing about who is asking — so "nobody to
	 * credit" quietly meant "nobody to refuse". A null here is now a skipped transition, and
	 * the link row and its `PULL_REQUEST_LINKED` activity are still written, because those
	 * were what the documented nameless sentence was ever about.
	 *
	 * The cost is named: a merge by a maintainer with no linked Kanso account stops moving
	 * the ticket. That is the same answer Kanso gives every other unidentified writer, and
	 * linking an account is one button on the settings screen.
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

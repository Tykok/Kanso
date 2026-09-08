package dev.kanso.service

import dev.kanso.domain.rebase
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.EffortPoints
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.SyncState
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.github.TicketPullRequest
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DocRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.TicketQueryRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.TicketScope
import dev.kanso.repo.UserRepository
import dev.kanso.sync.outbound.deletePayload
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `KAN-142`, spelled once.
 *
 * [TicketDetail.identifier] was the definition and `ActivityService` became a second reader
 * that holds a key and a number without holding a whole detail — six statements and a
 * `GithubRepository` to compose one string is not a trade a feed should make, so the
 * composition moved here instead of being written out twice. `TimelineService` has a third
 * copy that substitutes `?` for a missing key: deliberately not folded in, because printing
 * a placeholder is the opposite of what [TicketDetail]'s own comment argues for below, and
 * changing what a timeline draws is not this change.
 *
 * Both halves are required, where the getter this replaced only asked about the key: the
 * pair is all-or-nothing by `tickets_team_number_together_chk`, so no row can reach the
 * stricter branch — but "KAN-null" is the string the looser one would have produced if one
 * ever did.
 */
fun ticketIdentifier(teamKey: String?, number: Int?): String? =
	if (teamKey == null || number == null) null else "$teamKey-$number"

/**
 * A ticket with its team key (for `KAN-142`) and its relations already loaded.
 *
 * [teamKey] and [identifier] are null for a ticket no team has claimed. Null rather than a
 * placeholder on purpose: a draft's name would be guaranteed to change the moment it gains
 * a team, and a name that changes is worse than no name — it gets pasted into a comment or
 * a chat message and rots there silently, while an absence cannot. What addresses a draft
 * until then is its id, which never changes. What a person calls it is its title.
 */
data class TicketDetail(
	val ticket: Ticket,
	val teamKey: String?,
	val assigneeIds: List<UUID>,
	val docIds: List<UUID>,
	/**
	 * `V35`'s values, by field id — empty for a ticket whose team has defined none, and for
	 * every draft, which has no team to have defined any.
	 *
	 * Keyed by id rather than by name for the reason every other relation here is a list of
	 * ids: a name is what a *screen* calls a thing, the screen already fetches the team's
	 * definitions to draw its inputs, and a map keyed by name would break on a rename. The
	 * value is one of the three scalars `FieldValueCodec` produces.
	 *
	 * Defaulted, so that the twenty-odd places that build a detail did not all have to grow
	 * an argument — and so that a caller which forgets it gets an empty map rather than a
	 * wrong one.
	 */
	val customFields: Map<UUID, Any> = emptyMap(),
	/**
	 * `V36`'s pull requests, newest first within a repository. Empty for every ticket on an
	 * instance that has never connected a GitHub App, which is most of them.
	 *
	 * Defaulted for the same two reasons `customFields` is: the twenty-odd places that build
	 * a detail did not have to grow an argument, and a caller that forgets it gets an empty
	 * list rather than a wrong one.
	 *
	 * On the row rather than behind a `GET /api/tickets/:id/pull-requests`, which is the
	 * opposite of what `V33`'s links did — and the difference is what each one is *for*. A
	 * link's payload is a whole other ticket, so serving links inline would nest a
	 * `TicketResponse` in every row of a list. A pull request is a small flat record, and the
	 * question "does this ticket have an open pull request" is one a board column wants to
	 * answer without a request per card. Same reason `parentId` rides on the row.
	 */
	val pullRequests: List<TicketPullRequest> = emptyList(),
	/**
	 * The members behind [pullRequests]' authors, keyed by user id — the people whose GitHub
	 * account is linked, and nobody else.
	 *
	 * A map rather than a `User?` on each pull request, because a page's authors repeat:
	 * three pull requests on a ticket are usually one person, and thirty across a board are
	 * usually five. It is resolved once per page by `TicketDetails` and read by
	 * `PullRequestDto.of`.
	 *
	 * Empty is the ordinary state and not an unfinished one. It is empty for every instance
	 * with no GitHub App, and it stays empty for every author who never walked the consent
	 * flow — which is what makes their row show `@tykok`, the same handle it showed before
	 * any of this existed.
	 */
	val pullRequestAuthors: Map<UUID, User> = emptyMap(),
) {
	val identifier: String? get() = ticketIdentifier(teamKey, ticket.number)

	/**
	 * The branch to create, derived from the identifier and the title — **computed on read,
	 * never stored**, like every other derived value in this schema.
	 *
	 * Not stored because it is a function of two columns that both change: a retitled ticket
	 * would keep a stale suggestion, and a stored branch name would also start looking like a
	 * promise about a branch that exists. This is a suggestion to copy, and nothing more.
	 *
	 * Null for a draft, which has no identifier to name a branch after.
	 *
	 * Kanso does not create the branch. That needs `contents: write`, which would tell an
	 * organisation at install time that Kanso may write to every repository it selected —
	 * forever, whether or not the feature that needed it is still used — in exchange for not
	 * typing `git switch -c`. The design weighs that trade and refuses it, so what a ticket
	 * offers is the name.
	 */
	val branchName: String? get() = identifier?.let { id ->
		val slug = ticket.title.lowercase()
			// Anything that is not a letter, a digit or a separator becomes a separator, then
			// runs of separators collapse. Doing it in two passes rather than one clever
			// pattern is what keeps `A/B testing: round 2` from becoming `a-b--testing--round-2`.
			.replace(Regex("[^a-z0-9]+"), "-")
			.trim('-')
			// Bounded, because a branch name is pasted into a shell and a title has no limit.
			// Cut on a separator so the tail is a whole word rather than half of one.
			.take(48).trimEnd('-')
		"feat/${id.lowercase()}" + if (slug.isEmpty()) "" else "-$slug"
	}
}

/**
 * Fields a PATCH may change. `null` means "leave alone"; to actually clear a
 * nullable column, name it in [unset]. JSON cannot otherwise distinguish an
 * absent key from an explicit null, and silently ignoring `"due": null`
 * would make clearing a date impossible.
 */
data class TicketPatch(
	val title: String? = null,
	val description: String? = null,
	/** A status key of the ticket's team — a `String` since `KAN-90`, validated on the way in. */
	val status: String? = null,
	val priority: TicketPriority? = null,
	val estimate: Int? = null,
	val start: KansoInstant? = null,
	val due: KansoInstant? = null,
	val projectId: UUID? = null,
	val teamId: UUID? = null,
	val archived: Boolean? = null,
	val assigneeIds: List<UUID>? = null,
	val docIds: List<UUID>? = null,
	val unset: Set<String> = emptySet(),
)

@Service
class TicketService(
	private val tickets: TicketRepository,
	/**
	 * The one predicate, reached directly rather than through [tickets]: the list is the
	 * caller that wants all twelve filters, and `TicketRepository.search` is the narrow
	 * call shape over the same query for the callers that want four.
	 */
	private val ticketQuery: TicketQueryRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val outbox: OutboundJobRepository,
	private val events: EventPublisher,
	private val schedule: ScheduleService,
	private val access: TicketAccess,
	private val activity: ActivityService,
	/**
	 * Written at the same points as [activity] and in the same transaction, and it is not
	 * the same row: an activity row says what happened to the ticket, a notification says
	 * a person has to be told. Two of the inbox's four tabs are fed from here and from
	 * nowhere else.
	 */
	private val notifications: NotificationService,
	private val statusCategories: StatusCategories,
	/**
	 * The catalogue itself, for `rebase` — which needs the destination's rows in order,
	 * not just what each of them means. Through the service, so it runs in this
	 * transaction; `TeamStatusService` does not depend on this one, which is the whole
	 * reason that direction is available.
	 */
	private val statuses: TeamStatusService,
	/**
	 * The repository, not `TrashService`: that one is built out of [TrashSource] beans and
	 * one of them is built out of this service, so depending on it here would close a
	 * cycle. Writing the entry is a row insert and belongs at this level anyway — the
	 * trash's own service owns *removing* it, which is the half that has three exits.
	 */
	private val trash: TrashRepository,
	/**
	 * Grouping, which is two queries rather than one and so is not [list]'s business.
	 * Shared with [SavedViewService], because the list is a saved view nobody saved and
	 * the two have to stack their rows the same way.
	 */
	private val groups: TicketGroups,
	/**
	 * The relation loader this file used to have a private copy of.
	 *
	 * `TicketDetails` was extracted precisely because four organising services needed the
	 * same three queries, and its own comment names this class's `decorate` as the copy it
	 * was extracted from — a copy that was then left in place. `V35` is the fifth relation
	 * that comment predicted, so the duplicate is collapsed here rather than doubled: one
	 * place loads a ticket's relations, and a sixth one cannot be added to half the app.
	 */
	private val details: TicketDetails,
) {

	/**
	 * The main list, which is a saved view nobody saved.
	 *
	 * It takes the same [TicketFilters] a stored question parses to and runs the same
	 * predicate, so a filter reaching the list and a filter reaching a view are the same
	 * filter. Resolving [includeDescendants] here rather than in the repository keeps the
	 * hierarchy walk on the service side, where every other one lives.
	 */
	@Transactional(readOnly = true)
	fun list(
		teamId: UUID?,
		includeDescendants: Boolean,
		includeArchived: Boolean,
		filters: TicketFilters,
		sortBy: ViewSortBy,
		limit: Int,
		offset: Long,
	): List<TicketDetail> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		return decorate(
			ticketQuery.matching(
				scope = TicketScope(teamIds = teamIds, includeArchived = includeArchived),
				filters = filters,
				sortBy = sortBy,
				limit = limit,
				offset = offset,
			)
		)
	}

	/**
	 * The same list, stacked — every bucket the question has, each with a count of the
	 * whole match and the rows of it this page reached.
	 *
	 * A second method rather than a shape [list] switches into: a response whose type
	 * depends on a query parameter is one every caller has to branch on, and the flat
	 * answer is what every other screen in the app already reads.
	 */
	@Transactional(readOnly = true)
	fun grouped(
		teamId: UUID?,
		includeDescendants: Boolean,
		includeArchived: Boolean,
		filters: TicketFilters,
		groupBy: ViewGroupBy,
		sortBy: ViewSortBy,
		limit: Int,
		offset: Long,
	): List<TicketGroup> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		return groups.of(
			scope = TicketScope(teamIds = teamIds, includeArchived = includeArchived),
			filters = filters,
			groupBy = groupBy,
			sortBy = sortBy,
			limit = limit,
			offset = offset,
		)
	}

	/** The four-filter shape, kept for the callers and the tests that only ever wanted it. */
	@Transactional(readOnly = true)
	fun search(
		teamId: UUID?,
		includeDescendants: Boolean,
		projectId: UUID?,
		statuses: List<DefaultStatus>,
		assigneeId: UUID?,
		includeArchived: Boolean,
		limit: Int,
		offset: Long,
	): List<TicketDetail> = list(
		teamId = teamId,
		includeDescendants = includeDescendants,
		includeArchived = includeArchived,
		filters = TicketFilters(
			statuses = statuses,
			projectIds = listOfNotNull(projectId),
			assigneeIds = listOfNotNull(assigneeId),
		),
		sortBy = ViewSortBy.UPDATED,
		limit = limit,
		offset = offset,
	)

	/**
	 * [actor] is here for the one kind of ticket a reader can be refused: a draft is private
	 * to whoever wrote it. A 404 rather than a 403, for the reason [requireLive] gives — a
	 * reader who cannot act on it is either stale or guessing, and neither should learn that
	 * somebody else's draft exists.
	 */
	@Transactional(readOnly = true)
	fun get(actor: User, id: UUID): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		if (!access.mayRead(actor, ticket)) throw NotFoundException("No ticket $id")
		return decorate(listOf(ticket)).single()
	}

	/** The row, with no reader in mind. Internal callers and tests only; the door is [get]. */
	@Transactional(readOnly = true)
	fun get(id: UUID): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		return decorate(listOf(ticket)).single()
	}

	/**
	 * The drafts — every list on every other screen goes through the one predicate, and that
	 * predicate excludes them, so this is the only place they are.
	 *
	 * Scoped to the caller by the same rule that decides whether they may edit one, in SQL
	 * rather than by filtering afterwards: a page of 200 that quietly drops other people's
	 * rows is a page that under-fills, and paging over it skips work.
	 */
	@Transactional(readOnly = true)
	fun drafts(actor: User, limit: Int = 200): List<TicketDetail> = decorate(
		tickets.findDrafts(
			authorId = if (actor.instanceRole.canConfigureInstance) null else actor.id,
			limit = limit,
		)
	)

	@Transactional(readOnly = true)
	fun getByIdentifier(teamKey: String, number: Int): TicketDetail {
		val team = teams.findByKey(teamKey.uppercase())
			?: throw NotFoundException("No team with key $teamKey")
		val ticket = tickets.findByTeamAndNumber(team.id, number)
			?: throw NotFoundException("No ticket $teamKey-$number")
		requireLive(ticket)
		// Through `decorate` like every other read, rather than assembling a detail by hand:
		// the hand-built one was two queries that happened to match the loader's and, once
		// `V35` added a fourth relation, would have been the one read in the app answering
		// `customFields` as empty — and it is the read `kanso_get_ticket` goes through.
		return decorate(listOf(ticket)).single()
	}

	/**
	 * A ticket in the trash is not live work.
	 *
	 * It answers 404 to every read and refuses every edit — exactly what a destroyed one
	 * used to do, which is what keeps soft deletion invisible to every caller that has no
	 * business knowing about it. Screen 26 is the one surface that can see it, and it goes
	 * through `TrashService`, never through here.
	 *
	 * A 404 rather than a 410 or a 409: nothing in the interface can act on a ticket it
	 * cannot see, so a caller reaching one is either stale or guessing, and both are best
	 * answered with the same sentence as an id that never existed.
	 */
	private fun requireLive(ticket: Ticket): Ticket {
		if (trash.find(TrashKind.TICKET, ticket.id) != null) {
			throw NotFoundException("No ticket ${ticket.id}")
		}
		return ticket
	}

	/**
	 * [teamId] null files a draft: no counter is asked, no identifier exists yet, and
	 * [actor] becomes the only person who may see or edit it until a team does.
	 *
	 * Naming a [projectId] that belongs to a team is the other way in. The team follows the
	 * project, because a ticket's project belongs to its team and the alternative is a row
	 * that breaks that invariant the instant it is written.
	 *
	 * [actor] is null for one caller, [dev.kanso.sync.inbound.RequestSiphon]: a page written
	 * in a Notion requests base was written by somebody with no Kanso account, so there is
	 * no member to check and none to credit. Nullable in the same shape and for the same
	 * class of reason as [purge] — where the consent was given earlier, by somebody else,
	 * and the write happens with nobody left to ask. Here the earlier consent is an admin
	 * registering the base against a named team, which is why the team below is not
	 * *checked* against an actor but is still not arbitrary: `V37` holds the argument.
	 *
	 * A null actor may only file *into a team*. Passing null with no team would write a
	 * draft owned by nobody — instance-admin-visible, in no queue, comparable to nothing —
	 * which is exactly the dead letter box `V37` refuses, so it is refused here too.
	 */
	@Transactional
	fun create(
		actor: User?,
		teamId: UUID?,
		title: String,
		description: String?,
		status: String,
		priority: TicketPriority,
		start: KansoInstant?,
		due: KansoInstant?,
		projectId: UUID?,
		assigneeIds: List<UUID>,
		docIds: List<UUID>,
		estimate: Int? = null,
	): TicketDetail {
		// A project's team is adopted only when none was named — never as an override, so
		// this cannot become a way to file into a team the explicit check below would refuse.
		val effectiveTeamId = teamId ?: projectId?.let { projects.findById(it)?.teamId }
		// First, because `nextTicketNumber` below takes an exclusive row lock on the
		// team — a check placed after it would serialise every legitimate creator in that
		// team behind a request already destined for 403, for the rest of this
		// transaction. (The counter itself is not at risk either way: the UPDATE rolls
		// back with the refusal, this method being @Transactional.) The same reason
		// `patch` checks its destination side before doing anything else.
		//
		// Asked of the adopted team too: attaching through a project must not be a door into
		// a team the actor may not edit, which would be the whole rule defeated in one field.
		//
		// Skipped for a null actor, like `purge`, and the refusal below is what keeps that
		// from being a hole: with no actor there is no seat to check and no membership to
		// walk, so the only safe null case is the one whose destination was decided by an
		// admin at registration time rather than by the caller.
		if (actor == null && effectiveTeamId == null) {
			throw BadRequestException("A ticket created by no one must name the team whose queue it joins")
		}
		actor?.let { who -> effectiveTeamId?.let { access.requireTeam(who, it) } }
		val team = effectiveTeamId?.let {
			teams.findById(it) ?: throw BadRequestException("No team $it")
		}
		validateDates(start, due)
		// A ticket's project belongs to its team — the same invariant `patch` upholds,
		// and the same answer for the same reason: a project named explicitly and
		// belonging to another team is a mistake worth a 400, not something to swallow.
		// The composer only bounds what it offers; this is what makes the rule true of
		// every caller. A team-less project is transverse and belongs everywhere — and
		// leaves a team-less ticket team-less, having none of its own to lend.
		projectId?.let {
			val project = requireProject(it)
			if (project.teamId != null && project.teamId != effectiveTeamId) {
				throw BadRequestException(
					"Project $it belongs to team ${project.teamId}, not team $effectiveTeamId",
				)
			}
		}
		requireUsers(assigneeIds)
		requireDocs(docIds)
		// Checked here rather than at the edge, unlike a status: a status arrives as a wire
		// string and the controller has to parse it anyway, while an estimate is already an
		// Int by the time it lands. Parsing belongs at the edge; a rule belongs with the
		// write, where the importer and any later caller meet it too.
		val points = EffortPoints.from(estimate)

		// Allocated inside this transaction: the row lock on the team serialises
		// concurrent creates, so two people pressing "c" at once get 41 and 42. No team, no
		// counter to ask and nothing to allocate — the pair stays null on both sides.
		val number = effectiveTeamId?.let { teams.nextTicketNumber(it) }
		val ticket = tickets.insert(
			id = UUID.randomUUID(),
			number = number,
			teamId = effectiveTeamId,
			// Recorded whatever happens, because the write that attaches a team later is not
			// the write that would know who to ask. It stops deciding anything the moment a
			// team is named; see `TicketAccess` — which is what makes a null here harmless
			// on a siphoned request: it always has a team, so this column never decides its
			// access, and `V20` already says a null author resolves to admins only.
			createdBy = actor?.id,
			title = title,
			description = description,
			// The same guard as `patch`, for the same reason and with the same sentence:
			// an import, a siphoned request or an agent may name a status the destination
			// team does not have, and `tickets_status_fk` would answer that with a 500.
			status = statusCategories.require(effectiveTeamId, status),
			priority = priority,
			estimate = points,
			start = start,
			due = due,
			projectId = projectId,
		)
		tickets.setAssignees(ticket.id, assigneeIds)
		tickets.setDocs(ticket.id, docIds)

		// A draft is not mirrored. The Notion row is built from the identifier and the team
		// relation, and it has neither — the page would be a blank nothing could reconcile,
		// and it would have to be found and rewritten the day the ticket is attached. The
		// attach is the push, and `patch` makes it.
		if (effectiveTeamId != null) {
			outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, ticket.id, OutboundOperation.UPSERT)
		}
		// No payload: a creation has no before, and the title a feed wants to print is on
		// the row it is already reading. What the log adds is who, and when.
		//
		// A null actor still writes the row: `V8` made `actor_id` nullable and `V36` argues
		// at length that an event whose author is not a Kanso member is an ordinary event
		// with nobody to name, not a different kind. The feed reads "created" with no
		// person, which is the truth about a request somebody typed in Notion.
		activity.record(ActivityEntity.TICKET, ticket.id, actor?.id, ActivityKind.CREATED)
		// A caller with no actor cannot assign anyone — the siphon passes none — so this is
		// the same no-op it already was for an empty list, said in the type system.
		actor?.let { recordAssigneeChanges(it, ticket.id, before = emptyList(), after = assigneeIds) }
		events.publish(KansoEvent.ticket(ChangeKind.CREATED, ticket.id, effectiveTeamId, projectId))
		return TicketDetail(ticket, team?.key, assigneeIds, docIds)
	}

	/**
	 * The hot path: a status change from the keyboard. Loads, merges, writes the
	 * full row, then queues one mirror push.
	 *
	 * [actor] is null for one caller, [dev.kanso.github.GithubWebhookService]: a pull
	 * request merged by somebody who never linked their GitHub account moves the ticket with
	 * nobody to credit, and `activity.actor_id` is nullable precisely so the feed can say
	 * *KAN-142 moved to Done via #418* rather than inventing a person. Nullable in the same
	 * shape and for the same class of reason as [purge] and [create] — the consent was given
	 * earlier, by somebody else, and the write happens with nobody left to ask.
	 *
	 * **The compensation is different from [create]'s, because the exposure is.** A null
	 * actor skips [access], so an actorless call is an unchecked write. `create` answers that
	 * by constraining *where* the row lands — it has no row yet, so naming a team is the only
	 * thing left to pin. This method already has its row, named by the caller, so the
	 * destination is not what is loose: **the breadth of the write is.** So an actorless
	 * patch may change **the status and nothing else**.
	 *
	 * That is not a convenience: it is the earlier consent read literally. What a member
	 * declared by naming a branch `feat/kan-142-x` is *this pull request may finish this
	 * ticket*. It is not "GitHub may retitle my ticket", nor re-prioritise it, nor archive it,
	 * nor move it to another team — and every one of those would be reachable from an
	 * unauthenticated endpoint if this refusal were not here. What remains reachable is one
	 * field, bounded a second time by `PrTransition`'s three guards.
	 *
	 * [viaPullRequest] names the pull request on the `status_changed` row, which is how the
	 * feed answers *why* a ticket moved without automation getting an `ActivityKind` of its
	 * own — `V36` argues that at length: giving it one would split the single question a
	 * history is kept for across two vocabularies.
	 */
	@Transactional
	fun patch(
		actor: User?,
		id: UUID,
		patch: TicketPatch,
		viaPullRequest: String? = null,
	): TicketDetail {
		val current = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		// Skipped for a null actor, like `purge` and `create`, and the refusal below is what
		// keeps that from being a hole: with no actor there is no seat to check and no
		// membership to walk, so the only safe null case is the narrowest possible write.
		if (actor == null) requireStatusOnly(patch) else access.require(actor, current)
		// Losing a team would lose the identifier people already say out loud, and there is
		// no counter that hands the same number back. A draft goes forwards only.
		if ("teamId" in patch.unset) {
			throw BadRequestException("A ticket cannot be detached from its team; $id would lose its identifier")
		}

		// The two ways a ticket gains a team: naming one, or naming a project that already
		// belongs to one. The second is a real attach and not a side effect — a ticket's
		// project belongs to its team, so a draft filed into somebody's project *is* work in
		// that team, and leaving it team-less would break the invariant the coherence check
		// below enforces for everybody else.
		//
		// The project's team is read only when the ticket has none and none was named: for a
		// ticket that already has a team the project must match it, which is the existing
		// rule and is checked below rather than quietly overridden here.
		val teamId = patch.teamId
			?: current.teamId
			?: patch.projectId?.let { projects.findById(it)?.teamId }
		// Both ends, not one. `TicketPatch` carries `teamId`, so a single-sided check
		// lets anyone move a foreign ticket into a team of their own and then edit it
		// freely — the whole rule defeated in two requests. Asked of a team arriving via a
		// project too, for exactly the same reason: otherwise the project field is the
		// second request.
		if (teamId != null && teamId != current.teamId) actor?.let { access.requireTeam(it, teamId) }
		if (patch.teamId != null && teams.findById(patch.teamId) == null) {
			throw BadRequestException("No team ${patch.teamId}")
		}
		// A ticket's project must belong to its effective team (`teamId` above, which
		// accounts for a `teamId` change in this same patch). Not a database constraint:
		// making it one would also forbid the team-less projects the sidebar shows in
		// their own section, which are the transverse case on purpose.
		//
		// The two ways a stale project can appear get different answers, on purpose:
		// an explicitly requested project that doesn't belong to the effective team is a
		// mistake worth a 400, same class as an unknown teamId above; an inherited
		// project left behind by a team move was never asked for, so it is dropped
		// silently as a consequence of the move, not rejected.
		val projectId = when {
			"projectId" in patch.unset -> null
			patch.projectId != null -> {
				val project = requireProject(patch.projectId)
				if (project.teamId != null && project.teamId != teamId) {
					throw BadRequestException(
						"Project ${patch.projectId} belongs to team ${project.teamId}, not team $teamId",
					)
				}
				patch.projectId
			}
			else -> current.projectId?.takeIf { inheritedId ->
				val projectTeamId = projects.findById(inheritedId)?.teamId
				projectTeamId == null || projectTeamId == teamId
			}
		}
		val start = if ("start" in patch.unset) null else patch.start ?: current.start
		val due = if ("due" in patch.unset) null else patch.due ?: current.due
		validateDates(start, due)
		// Un-estimating is a real edit, so it has to be spelled: an absent field leaves the
		// points alone, and `unset` is the only way back to "nobody has sized this".
		val estimate =
			if ("estimate" in patch.unset) null else EffortPoints.from(patch.estimate) ?: current.estimate

		patch.assigneeIds?.let { requireUsers(it) }
		patch.docIds?.let { requireDocs(it) }

		// **Validated against the team the ticket ends up in, and this is the one guard.**
		// Every write door reaches a status through here — the controller, the bulk strip,
		// the three MCP tools, the triage ruling, the GitHub webhook — so one refusal here
		// is one sentence for all of them, naming the team's own words. Without it the
		// only thing refusing an unknown key is `tickets_status_fk`, which surfaces as a
		// 500 from the driver on what is a caller's mistake.
		//
		// Only when the patch names one: an untouched status is already in the catalogue,
		// and re-checking it would refuse a patch of the *title* on a ticket whose team
		// has meanwhile removed the status it sits in — punishing an edit for a decision
		// somebody else made. `KAN-90`'s `remove` moves those rows itself.
		val status = patch.status?.let { statusCategories.require(teamId, it) }
			// Nobody named one, and the ticket may be crossing into another team's
			// vocabulary — `KAN-90`'s `rebase`. Only when the team actually changes: for
			// every other patch `current.status` is already in the catalogue, and running
			// the rule anyway would let a status the team removed be silently rewritten by
			// an edit to the title.
			?: if (teamId != null && teamId != current.teamId) {
				rebase(
					current.status,
					statuses.forTeam(teamId),
					statusCategories.categoryOf(current.teamId, current.status),
				)
			} else {
				current.status
			}
		// Resolved against the team the ticket *ends up in*, and the old one against the
		// team it is leaving — `KAN-90`. A move across teams is also a status change, and
		// asking one catalogue about both words would read the destination's meaning for a
		// status the source team defined, which is how `completed_at` gets cleared on a
		// ticket nobody reopened.
		val completed = statusCategories.categoryOf(teamId, status) == StatusCategory.COMPLETED
		val wasCompleted =
			statusCategories.categoryOf(current.teamId, current.status) == StatusCategory.COMPLETED

		// Crossing into another team means taking that team's next number, from its own
		// counter. `UNIQUE (team_id, number)` leaves no choice: keeping the old number
		// either collides with one already in use over there, or squats one the
		// destination's counter will hand out again later. Same allocation the
		// disposition makes for a whole block, for one ticket.
		//
		// The same write is a draft's first naming: it arrives with the pair null on both
		// sides and leaves with both set, which is the only transition
		// `tickets_team_number_together_chk` allows out of that state.
		if (teamId != null && teamId != current.teamId) {
			tickets.moveToTeam(id, teamId, teams.nextTicketNumber(teamId), status)
			// The mirror was switched off while this had no team — see `TicketRepository.insert`
			// — so the first attach switches it back on. Before the row is read back below, so
			// the response carries the state the push it is about to queue will act on.
			if (current.teamId == null) tickets.markSyncState(id, SyncState.PENDING)
		}

		// Written here rather than in a trigger: the rule belongs next to the status
		// logic that owns it, and a trigger would be the only part of the transition
		// invisible from thiMPLETED
		val completedAt = when {
			completed && !wasCompleted -> OffsetDateTime.now()
			!completed -> null
			else -> current.completedAt
		}

		val updated = tickets.update(
			id = id,
			teamId = teamId,
			title = patch.title ?: current.title,
			description = if ("description" in patch.unset) null else patch.description ?: current.description,
			status = status,
			priority = patch.priority ?: current.priority,
			estimate = estimate,
			start = start,
			due = due,
			completedAt = completedAt,
			projectId = projectId,
			archived = patch.archived ?: current.archived,
		) ?: throw NotFoundException("No ticket $id")

		recordScalarChanges(actor, before = current, after = updated, viaPullRequest = viaPullRequest)
		notifyStatusMoved(actor, id, before = current, after = updated)
		patch.assigneeIds?.let { wanted ->
			// Read before the write, not after: the log's whole value is the difference.
			val before = tickets.assigneeIds(id)
			tickets.setAssignees(id, wanted)
			// A caller with no actor cannot assign anyone — `requireStatusOnly` refuses the
			// field outright — so this block is unreachable for one, said in the type system
			// rather than left to a reader to work out. Same shape as `create`'s.
			actor?.let { recordAssigneeChanges(it, id, before, wanted) }
		}
		patch.docIds?.let { tickets.setDocs(id, it) }

		// Still nothing to mirror while it has no identifier and no team relation — the same
		// argument `create` makes. The first push is the one that follows the attach, and
		// by then this row carries both.
		if (updated.teamId != null) {
			outbox.enqueue(
				Destination.NOTION,
				OutboundEntityType.TICKET,
				id,
				if (updated.archived) OutboundOperation.ARCHIVE else OutboundOperation.UPSERT,
			)
		}
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, updated.teamId, updated.projectId))

		// The cascade runs inside this transaction, so the event published just above —
		// which `EventPublisher` defers to `afterCommit` — already announces it. One
		// event for the whole cascade, not one per moved ticket: `pg_notify` caps
		// payloads at 8000 bytes and two hundred UUIDs alone come to 7200, and receivers
		// refetch rather than read ids off the event, which is the doctrine every other
		// event here already follows.
		schedule.cascadeFrom(id)
		return decorate(listOf(updated)).single()
	}

	/**
	 * Throws the ticket away. It is not destroyed: a `trash_entries` row starts a
	 * thirty-day countdown, and screen 26 is where it can be restored, archived instead, or
	 * finally destroyed.
	 *
	 * Every live read stops answering for it at once, which is why the event is still
	 * `DELETED` — from the point of view of any list on any other screen, it *is* gone, and
	 * a receiver that only invalidates its ticket query needs to hear nothing else.
	 *
	 * The mirror gets `ARCHIVE`, not `DELETE`. Notion has no hard delete worth relying on
	 * either way, so both operations end up archiving the page — but only `ARCHIVE` leaves
	 * `notion_page_id` on the row, and a ticket that may come back in twenty-nine days has
	 * to come back to the same page rather than to a second one. [purge] is where the page
	 * goes for good.
	 *
	 * Idempotent: deleting something already in the trash restarts nothing. Two clicks on
	 * one row would otherwise buy it another thirty days.
	 */
	@Transactional
	fun delete(actor: User, id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		access.require(actor, ticket)
		if (trash.find(TrashKind.TICKET, id) != null) return
		trash.add(TrashKind.TICKET, id, actor.id)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.ARCHIVE)
		events.publish(KansoEvent.ticket(ChangeKind.DELETED, id, ticket.teamId, ticket.projectId))
	}

	/**
	 * The first exit of screen 26. Removing the trash entry is [dev.kanso.trash.TrashService]'s
	 * half, and it is the whole of what makes the ticket visible again — the row itself was
	 * never touched by the delete, so a restore has nothing to put back and cannot get the
	 * parent wrong. `CREATED` is the honest event for a reader whose list is about to grow a
	 * row it had already dropped.
	 *
	 * A ticket that was archived *and* then thrown away comes back archived: the delete
	 * asked nothing about that flag, so the restore does not answer for it either.
	 */
	@Transactional
	fun restore(actor: User, id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		access.require(actor, ticket)
		outbox.enqueue(
			Destination.NOTION,
			OutboundEntityType.TICKET,
			id,
			if (ticket.archived) OutboundOperation.ARCHIVE else OutboundOperation.UPSERT,
		)
		events.publish(KansoEvent.ticket(ChangeKind.CREATED, id, ticket.teamId, ticket.projectId))
	}

	/**
	 * The middle exit: out of the trash and into the archives, the countdown off because
	 * somebody made a decision instead of letting the clock make it.
	 *
	 * A method of its own rather than a `patch(archived = true)`, because [patch] refuses a
	 * ticket that is in the trash — right for every other caller, and exactly the state
	 * this one starts from. It writes the one flag, so it cannot clobber a concurrent edit
	 * to a field it has no business touching.
	 */
	@Transactional
	fun archiveFromTrash(actor: User, id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		access.require(actor, ticket)
		tickets.setArchived(id, true)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.ARCHIVE)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
	}

	/**
	 * The last exit, and the only one that does not come back. What [delete] used to do.
	 *
	 * [actor] is null for the retention sweep: thirty days is the consent, and there is
	 * nobody left to ask by the time it fires.
	 */
	@Transactional
	fun purge(actor: User?, id: UUID) {
		val ticket = tickets.findById(id) ?: throw NotFoundException("No ticket $id")
		actor?.let { access.require(it, ticket) }
		// Read before the delete: the job carries the Notion page id, and by the time the
		// worker runs there is no row left to look it up from.
		outbox.enqueue(
			Destination.NOTION,
			OutboundEntityType.TICKET,
			id,
			OutboundOperation.DELETE,
			payload = deletePayload(ticket.mirror.notionPageId),
		)
		tickets.delete(id)
		events.publish(KansoEvent.ticket(ChangeKind.DELETED, id, ticket.teamId, ticket.projectId))
	}

	@Transactional
	fun setAssignees(actor: User, id: UUID, userIds: List<UUID>): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		access.require(actor, ticket)
		requireUsers(userIds)
		val before = tickets.assigneeIds(id)
		tickets.setAssignees(id, userIds)
		recordAssigneeChanges(actor, id, before, userIds)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
		return decorate(listOf(ticket)).single()
	}

	@Transactional
	fun setDocs(actor: User, id: UUID, docIds: List<UUID>): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		access.require(actor, ticket)
		requireDocs(docIds)
		tickets.setDocs(id, docIds)
		outbox.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
		return decorate(listOf(ticket)).single()
	}

	// --- the log -------------------------------------------------------------

	/**
	 * One row per scalar that actually changed, and nothing for a patch that changed
	 * none. A row per call would make the feed a list of the times somebody pressed a
	 * key; a row per changed field is what "moved KAN-142 to in progress" is written from.
	 *
	 * The two bounds are separate rows carrying which one moved, rather than one row with
	 * four keys: they are two scalars, and a reader that has to work out which of them
	 * changed is a reader doing the log's job.
	 *
	 * The cascade the caller runs afterwards is not logged. It moves other tickets' dates,
	 * and the same argument `follow-ups.md` records for the event applies to the log: two
	 * hundred rows nobody reads, for a change every receiver answers by refetching.
	 */
	private fun recordScalarChanges(
		actor: User?,
		before: Ticket,
		after: Ticket,
		viaPullRequest: String? = null,
	) {
		// NO `ref` IS WRITTEN HERE, AND THAT IS THE ANSWER TO `KAN-84` RATHER THAN AN OMISSION.
		//
		// The feed reads `payload.ref` to say "moved KAN-142 to Done" instead of "moved a
		// ticket to Done", and this method holds the two halves of that name — `after.number`
		// and `after.teamId` — but not the team's key. Resolving it here would have been one
		// extra SELECT on the path every `j`/`k` followed by a status write takes, which is
		// affordable; what is not affordable is what the value would then *be*. A team's key
		// is editable (`TeamService.update`), and crossing teams re-numbers the ticket from
		// the destination's counter a few lines above, so a stored `ref` is stale the moment
		// either happens — and stale in the one place a reader goes to reconstruct what
		// happened. `TicketDetail.branchName` already refuses to store a derived value in
		// those words, for the same two moving columns.
		//
		// It would also have left every row already in the table saying "a ticket" for good,
		// since `KAN-81`'s precedent is not to rebuild history from a guess.
		//
		// So it is resolved on read, for the whole page in two statements —
		// `ActivityService.refsFor`, which is also the only place a future eighth log here,
		// or a ninth writer in another service, has to not forget.
		fun log(kind: ActivityKind, payload: Map<String, Any?>) =
			activity.record(ActivityEntity.TICKET, after.id, actor?.id, kind, payload)

		if (after.title != before.title) {
			log(ActivityKind.RENAMED, mapOf("from" to before.title, "to" to after.title))
		}
		if (after.status != before.status) {
			// `via_pr` is put in only when there is one, rather than always with a null: an
			// absent key is how every other payload here spells "does not apply", and a
			// `"via_pr": null` on the thousands of rows a keyboard writes would make the
			// exception look like the shape.
			log(
				ActivityKind.STATUS_CHANGED,
				mapOf("from" to before.status, "to" to after.status) +
					(viaPullRequest?.let { mapOf("via_pr" to it) } ?: emptyMap()),
			)
		}
		if (after.priority != before.priority) {
			log(ActivityKind.PRIORITY_CHANGED, mapOf("from" to before.priority.wire, "to" to after.priority.wire))
		}
		if (after.archived != before.archived) {
			// Both directions: coming back out of the archive is a decision too.
			log(ActivityKind.ARCHIVED, mapOf("from" to before.archived, "to" to after.archived))
		}
		if (after.estimate != before.estimate) {
			// Null on either end is left in the payload as an absent key, which is how a
			// reader tells "sized at 13" from "un-sized": both directions are real edits,
			// and `unset` is the only way back to nobody having sized it.
			log(ActivityKind.ESTIMATED, mapOf("from" to before.estimate, "to" to after.estimate))
		}
		if (after.start != before.start) {
			log(ActivityKind.SCHEDULED, bound("start", before.start, after.start))
		}
		if (after.due != before.due) {
			log(ActivityKind.SCHEDULED, bound("due", before.due, after.due))
		}
	}

	/**
	 * The one scalar move somebody who is not looking at the ticket has to hear about.
	 *
	 * Only the status, of the seven [recordScalarChanges] logs. A rename, a priority, an
	 * estimate and a date are all worth a line in the feed under the ticket; none of them
	 * is worth an unread count on somebody's sidebar, and an inbox that filled up with
	 * them is one nobody would open to find the assignment underneath.
	 *
	 * Called before the patch's own assignee change is applied, so the recipients are the
	 * assignees as they were when the status moved. Somebody added in the same request is
	 * already being told they have the ticket; a second row about a status they never held
	 * is noise.
	 */
	private fun notifyStatusMoved(actor: User?, id: UUID, before: Ticket, after: Ticket) {
		if (after.status == before.status) return
		notifications.record(
			recipients = tickets.assigneeIds(id),
			kind = NotificationKind.STATUS_MOVED,
			entityType = "ticket",
			entityId = id,
			// Null when a merge moved it and its author is nobody here. The inbox still owes
			// the assignees the row — *their* ticket reached Done is the notification, and who
			// pushed the button is the part that may be unknown. `NotificationService.record`
			// already takes a nullable actor, and it is what subtracts the actor from the
			// recipients, so a null simply subtracts nobody.
			actorId = actor?.id,
			payload = mapOf("from" to before.status, "to" to after.status),
		)
	}

	/** The instant alone. The granularity flag is how a date is *drawn*, not what changed. */
	private fun bound(field: String, before: KansoInstant?, after: KansoInstant?): Map<String, Any?> =
		mapOf("field" to field, "from" to before?.at?.toString(), "to" to after?.at?.toString())

	/**
	 * A row per person, not one row carrying a list: the inbox slice (D) turns each of
	 * these into a notification for exactly one reader, and a list would make it split
	 * the payload back apart to find them.
	 */
	private fun recordAssigneeChanges(actor: User, ticketId: UUID, before: List<UUID>, after: List<UUID>) {
		val added = after.toSet() - before.toSet()
		for (person in added) {
			activity.record(
				ActivityEntity.TICKET, ticketId, actor.id, ActivityKind.ASSIGNED,
				mapOf("userId" to person.toString()),
			)
		}
		for (removed in before.toSet() - after.toSet()) {
			activity.record(
				ActivityEntity.TICKET, ticketId, actor.id, ActivityKind.UNASSIGNED,
				mapOf("userId" to removed.toString()),
			)
		}

		// Here rather than at the three call sites — [create], [patch] and [setAssignees]
		// all reach the log through this method, and a fourth written later inherits the
		// notification instead of forgetting it. Only the people *added*: being handed work
		// is the news, and only they are the ones it is news to.
		//
		// No payload. The sentence the inbox draws for `assigned` is the actor and the
		// ticket's own name, and the row carries both already.
		notifications.record(added, NotificationKind.ASSIGNED, "ticket", ticketId, actor.id)

		// Nothing for a removal, and not because it does not matter: the vocabulary is
		// closed by a `CHECK` in `V13` and has no `unassigned`, so there is no row to write
		// and inventing a kind is a migration. The activity row above is where it is said.
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * One extra query per relation for the whole page, instead of two per row — and now
	 * [TicketDetails]'s copy of that rather than a second one beside it.
	 *
	 * Kept as a private one-liner instead of replacing the twenty call sites: the name reads
	 * better at each of them than `details.of(...)` does, and the point of the change is that
	 * there is one implementation, not one spelling.
	 */
	private fun decorate(found: List<Ticket>): List<TicketDetail> = details.of(found)

	private fun validateDates(start: KansoInstant?, due: KansoInstant?) {
		if (start != null && due != null && due.at.isBefore(start.at)) {
			throw BadRequestException("due ${due.at} is before start ${start.at}")
		}
	}

	/**
	 * [patch]'s compensation for a null actor: the status, and nothing else.
	 *
	 * Enumerated field by field rather than checked with `copy(status = null) ==
	 * TicketPatch()`, which would have been shorter and would have silently admitted every
	 * field added after today. A new field on [TicketPatch] has to be named here to be
	 * writable by nobody, and the direction that mistake falls in is refusal — which is the
	 * same argument `ReadOnlySeat` makes for listing its exceptions instead of matching them.
	 *
	 * `unset` is refused whole rather than per key: every one of its keys clears a field this
	 * list already forbids setting, and "may not set the title" while "may clear the title"
	 * is not a rule anybody could hold in their head.
	 */
	private fun requireStatusOnly(patch: TicketPatch) {
		val named = buildList {
			if (patch.title != null) add("title")
			if (patch.description != null) add("description")
			if (patch.priority != null) add("priority")
			if (patch.estimate != null) add("estimate")
			if (patch.start != null) add("start")
			if (patch.due != null) add("due")
			if (patch.projectId != null) add("projectId")
			if (patch.teamId != null) add("teamId")
			if (patch.archived != null) add("archived")
			if (patch.assigneeIds != null) add("assigneeIds")
			if (patch.docIds != null) add("docIds")
			if (patch.unset.isNotEmpty()) add("unset")
		}
		if (named.isNotEmpty()) {
			throw BadRequestException(
				"A ticket patched by no one may only change its status; $named needs somebody to ask",
			)
		}
	}

	private fun requireProject(id: UUID) =
		projects.findById(id) ?: throw BadRequestException("No project $id")

	private fun requireUsers(ids: List<UUID>) {
		if (ids.isEmpty()) return
		val found = users.findAllById(ids).map { it.id }.toSet()
		val missing = ids.toSet() - found
		if (missing.isNotEmpty()) throw BadRequestException("Unknown users: ${missing.joinToString()}")
	}

	private fun requireDocs(ids: List<UUID>) {
		if (ids.isEmpty()) return
		val found = docs.findAllById(ids).map { it.id }.toSet()
		val missing = ids.toSet() - found
		if (missing.isNotEmpty()) throw BadRequestException("Unknown docs: ${missing.joinToString()}")
	}
}

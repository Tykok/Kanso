package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.DocRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.deletePayload
import dev.kanso.sync.SyncOperation
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** A ticket with its team key (for `KAN-142`) and its relations already loaded. */
data class TicketDetail(
	val ticket: Ticket,
	val teamKey: String,
	val assigneeIds: List<UUID>,
	val docIds: List<UUID>,
) {
	val identifier: String get() = "$teamKey-${ticket.number}"
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
	val status: TicketStatus? = null,
	val priority: TicketPriority? = null,
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
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val users: UserRepository,
	private val docs: DocRepository,
	private val syncJobs: SyncJobRepository,
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
	/**
	 * The repository, not `TrashService`: that one is built out of [TrashSource] beans and
	 * one of them is built out of this service, so depending on it here would close a
	 * cycle. Writing the entry is a row insert and belongs at this level anyway — the
	 * trash's own service owns *removing* it, which is the half that has three exits.
	 */
	private val trash: TrashRepository,
) {

	@Transactional(readOnly = true)
	fun search(
		teamId: UUID?,
		includeDescendants: Boolean,
		projectId: UUID?,
		statuses: List<TicketStatus>,
		assigneeId: UUID?,
		includeArchived: Boolean,
		limit: Int,
		offset: Long,
	): List<TicketDetail> {
		val teamIds = teamId?.let { if (includeDescendants) teams.descendantIds(it) else listOf(it) }
		val found = tickets.search(teamIds, projectId, statuses, assigneeId, includeArchived, limit, offset)
		return decorate(found)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		return decorate(listOf(ticket)).single()
	}

	@Transactional(readOnly = true)
	fun getByIdentifier(teamKey: String, number: Int): TicketDetail {
		val team = teams.findByKey(teamKey.uppercase())
			?: throw NotFoundException("No team with key $teamKey")
		val ticket = tickets.findByTeamAndNumber(team.id, number)
			?: throw NotFoundException("No ticket $teamKey-$number")
		requireLive(ticket)
		return TicketDetail(ticket, team.key, tickets.assigneeIds(ticket.id), tickets.docIds(ticket.id))
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

	@Transactional
	fun create(
		actor: User,
		teamId: UUID,
		title: String,
		description: String?,
		status: TicketStatus,
		priority: TicketPriority,
		start: KansoInstant?,
		due: KansoInstant?,
		projectId: UUID?,
		assigneeIds: List<UUID>,
		docIds: List<UUID>,
	): TicketDetail {
		// First, because `nextTicketNumber` below takes an exclusive row lock on the
		// team — a check placed after it would serialise every legitimate creator in that
		// team behind a request already destined for 403, for the rest of this
		// transaction. (The counter itself is not at risk either way: the UPDATE rolls
		// back with the refusal, this method being @Transactional.) The same reason
		// `patch` checks its destination side before doing anything else.
		access.requireTeam(actor, teamId)
		val team = teams.findById(teamId) ?: throw BadRequestException("No team $teamId")
		validateDates(start, due)
		// A ticket's project belongs to its team — the same invariant `patch` upholds,
		// and the same answer for the same reason: a project named explicitly and
		// belonging to another team is a mistake worth a 400, not something to swallow.
		// The composer only bounds what it offers; this is what makes the rule true of
		// every caller. A team-less project is transverse and belongs everywhere.
		projectId?.let {
			val project = requireProject(it)
			if (project.teamId != null && project.teamId != teamId) {
				throw BadRequestException(
					"Project $it belongs to team ${project.teamId}, not team $teamId",
				)
			}
		}
		requireUsers(assigneeIds)
		requireDocs(docIds)

		// Allocated inside this transaction: the row lock on the team serialises
		// concurrent creates, so two people pressing "c" at once get 41 and 42.
		val number = teams.nextTicketNumber(teamId)
		val ticket = tickets.insert(
			id = UUID.randomUUID(),
			number = number,
			teamId = teamId,
			title = title,
			description = description,
			status = status,
			priority = priority,
			start = start,
			due = due,
			projectId = projectId,
		)
		tickets.setAssignees(ticket.id, assigneeIds)
		tickets.setDocs(ticket.id, docIds)

		syncJobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
		// No payload: a creation has no before, and the title a feed wants to print is on
		// the row it is already reading. What the log adds is who, and when.
		activity.record(ActivityEntity.TICKET, ticket.id, actor.id, ActivityKind.CREATED)
		recordAssigneeChanges(actor, ticket.id, before = emptyList(), after = assigneeIds)
		events.publish(KansoEvent.ticket(ChangeKind.CREATED, ticket.id, teamId, projectId))
		return TicketDetail(ticket, team.key, assigneeIds, docIds)
	}

	/**
	 * The hot path: a status change from the keyboard. Loads, merges, writes the
	 * full row, then queues one mirror push.
	 */
	@Transactional
	fun patch(actor: User, id: UUID, patch: TicketPatch): TicketDetail {
		val current = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		access.require(actor, current)
		// Both ends, not one. `TicketPatch` carries `teamId`, so a single-sided check
		// lets anyone move a foreign ticket into a team of their own and then edit it
		// freely — the whole rule defeated in two requests.
		patch.teamId?.let { access.requireTeam(actor, it) }

		val teamId = patch.teamId ?: current.teamId
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

		patch.assigneeIds?.let { requireUsers(it) }
		patch.docIds?.let { requireDocs(it) }

		// Crossing into another team means taking that team's next number, from its own
		// counter. `UNIQUE (team_id, number)` leaves no choice: keeping the old number
		// either collides with one already in use over there, or squats one the
		// destination's counter will hand out again later. Same allocation the
		// disposition makes for a whole block, for one ticket.
		if (patch.teamId != null && patch.teamId != current.teamId) {
			tickets.moveToTeam(id, patch.teamId, teams.nextTicketNumber(patch.teamId))
		}

		// Written here rather than in a trigger: the rule belongs next to the status
		// logic that owns it, and a trigger would be the only part of the transition
		// invisible from this file.
		val status = patch.status ?: current.status
		val completedAt = when {
			status == TicketStatus.DONE && current.status != TicketStatus.DONE -> OffsetDateTime.now()
			status != TicketStatus.DONE -> null
			else -> current.completedAt
		}

		val updated = tickets.update(
			id = id,
			teamId = teamId,
			title = patch.title ?: current.title,
			description = if ("description" in patch.unset) null else patch.description ?: current.description,
			status = status,
			priority = patch.priority ?: current.priority,
			start = start,
			due = due,
			completedAt = completedAt,
			projectId = projectId,
			archived = patch.archived ?: current.archived,
		) ?: throw NotFoundException("No ticket $id")

		recordScalarChanges(actor, before = current, after = updated)
		notifyStatusMoved(actor, id, before = current, after = updated)
		patch.assigneeIds?.let { wanted ->
			// Read before the write, not after: the log's whole value is the difference.
			val before = tickets.assigneeIds(id)
			tickets.setAssignees(id, wanted)
			recordAssigneeChanges(actor, id, before, wanted)
		}
		patch.docIds?.let { tickets.setDocs(id, it) }

		syncJobs.enqueue(
			SyncEntityType.TICKET,
			id,
			if (updated.archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
		)
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
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.ARCHIVE)
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
		syncJobs.enqueue(
			SyncEntityType.TICKET,
			id,
			if (ticket.archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
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
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.ARCHIVE)
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
		syncJobs.enqueue(
			SyncEntityType.TICKET,
			id,
			SyncOperation.DELETE,
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
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.ticket(ChangeKind.UPDATED, id, ticket.teamId, ticket.projectId))
		return decorate(listOf(ticket)).single()
	}

	@Transactional
	fun setDocs(actor: User, id: UUID, docIds: List<UUID>): TicketDetail {
		val ticket = requireLive(tickets.findById(id) ?: throw NotFoundException("No ticket $id"))
		access.require(actor, ticket)
		requireDocs(docIds)
		tickets.setDocs(id, docIds)
		syncJobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
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
	private fun recordScalarChanges(actor: User, before: Ticket, after: Ticket) {
		fun log(kind: ActivityKind, payload: Map<String, Any?>) =
			activity.record(ActivityEntity.TICKET, after.id, actor.id, kind, payload)

		if (after.title != before.title) {
			log(ActivityKind.RENAMED, mapOf("from" to before.title, "to" to after.title))
		}
		if (after.status != before.status) {
			log(ActivityKind.STATUS_CHANGED, mapOf("from" to before.status.wire, "to" to after.status.wire))
		}
		if (after.priority != before.priority) {
			log(ActivityKind.PRIORITY_CHANGED, mapOf("from" to before.priority.wire, "to" to after.priority.wire))
		}
		if (after.archived != before.archived) {
			// Both directions: coming back out of the archive is a decision too.
			log(ActivityKind.ARCHIVED, mapOf("from" to before.archived, "to" to after.archived))
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
	 * Only the status, of the six [recordScalarChanges] logs. A rename, a priority and a
	 * date are all worth a line in the feed under the ticket; none of them is worth an
	 * unread count on somebody's sidebar, and an inbox that filled up with them is one
	 * nobody would open to find the assignment underneath.
	 *
	 * Called before the patch's own assignee change is applied, so the recipients are the
	 * assignees as they were when the status moved. Somebody added in the same request is
	 * already being told they have the ticket; a second row about a status they never held
	 * is noise.
	 */
	private fun notifyStatusMoved(actor: User, id: UUID, before: Ticket, after: Ticket) {
		if (after.status == before.status) return
		notifications.record(
			recipients = tickets.assigneeIds(id),
			kind = NotificationKind.STATUS_MOVED,
			entityType = "ticket",
			entityId = id,
			actorId = actor.id,
			payload = mapOf("from" to before.status.wire, "to" to after.status.wire),
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

	/** One extra query per relation for the whole page, instead of two per row. */
	private fun decorate(found: List<Ticket>): List<TicketDetail> {
		if (found.isEmpty()) return emptyList()
		val ids = found.map { it.id }
		val keys = teams.findAllById(found.map { it.teamId }.toSet()).associate { it.id to it.key }
		val assignees = tickets.assigneeIdsFor(ids)
		val docsByTicket = tickets.docIdsFor(ids)
		return found.map {
			TicketDetail(
				ticket = it,
				teamKey = keys[it.teamId] ?: "?",
				assigneeIds = assignees[it.id].orEmpty(),
				docIds = docsByTicket[it.id].orEmpty(),
			)
		}
	}

	private fun validateDates(start: KansoInstant?, due: KansoInstant?) {
		if (start != null && due != null && due.at.isBefore(start.at)) {
			throw BadRequestException("due ${due.at} is before start ${start.at}")
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

package dev.kanso.service

import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionContents
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TeamMember
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.deletePayload
import dev.kanso.sync.SyncOperation
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TeamService(
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val users: UserRepository,
	private val syncJobs: SyncJobRepository,
	private val events: EventPublisher,
) {

	@Transactional(readOnly = true)
	fun list(includeArchived: Boolean): List<Team> = teams.findAll(includeArchived)

	@Transactional(readOnly = true)
	fun get(id: UUID): Team = teams.findById(id) ?: throw NotFoundException("No team $id")

	@Transactional(readOnly = true)
	fun descendants(id: UUID): List<Team> {
		get(id)
		return teams.descendants(id)
	}

	// --- disposition ---------------------------------------------------------

	@Transactional(readOnly = true)
	fun contents(id: UUID): DispositionContents {
		get(id)
		return DispositionContents(
			direct = countsOf(id, DispositionChoice.KEEP),
			subtree = countsOf(id, DispositionChoice.TAKE),
		)
	}

	/**
	 * What the operation will actually reach, for the given `subTeams` choice.
	 *
	 * With [DispositionChoice.KEEP] the sub-teams leave first, with their own projects
	 * and tickets untouched, so counting those would describe a decision nobody was
	 * offered: only what the team holds directly is on the table.
	 *
	 * With [DispositionChoice.TAKE] the whole subtree goes, so every project and ticket
	 * under it is what is being archived, destroyed or renumbered — and the sub-team
	 * count is the whole subtree, not just the children one level down. Counting the
	 * direct rows there would understate what the confirmation is asking consent for,
	 * which is exactly what the retyped name exists to prevent.
	 */
	private fun countsOf(id: UUID, subTeams: DispositionChoice): DispositionCounts {
		val doomed = if (subTeams == DispositionChoice.TAKE) teams.descendantIds(id) else listOf(id)
		return DispositionCounts(
			// `descendantIds` includes the root; the sub-teams are what is left of it.
			subTeams = if (subTeams == DispositionChoice.TAKE) doomed.size - 1 else teams.directChildIds(id).size,
			projects = projects.countByTeams(doomed),
			tickets = tickets.countByTeams(doomed),
		)
	}

	/**
	 * Removes the team from view after deciding what happens to what it holds.
	 *
	 * The plan's `projects` and `tickets` choices apply to every team going away — this
	 * one, plus its whole subtree when its sub-teams are taken. Kept sub-teams leave
	 * first, with their own contents untouched.
	 *
	 * A team already archived is returned untouched, and a second plan is not applied.
	 * This method names an end state the team is already in; what a second run would do
	 * is not archive anything twice but *re-disperse* — re-homing rows the first plan
	 * already placed, and in the `take` then `keep` order lifting a sub-team out of the
	 * subtree it was archived with, so `unarchive` on the leaf stops bringing its parent
	 * back. [unarchive] has always been idempotent for the same reason: it filters to the
	 * rows that are actually archived and pushes nothing when nothing moved. Refusing with
	 * a 409 instead was the other candidate and is worse on the path this guard exists
	 * for — a retried request — where nothing is wrong and there is nothing to report.
	 */
	@Transactional
	fun archive(actor: User, id: UUID, plan: DispositionPlan): Team {
		requireConfigurator(actor)
		val team = get(id)
		if (team.archived) return team
		val ticketsTarget = requireTicketDestination(team, plan)

		val doomed = disperse(team, plan, ticketsTarget, destructive = false)
		teams.setArchived(doomed, true)
		doomed.forEach {
			syncJobs.enqueue(SyncEntityType.TEAM, it, SyncOperation.ARCHIVE)
			events.publish(KansoEvent.team(ChangeKind.UPDATED, it))
		}
		return get(id)
	}

	/**
	 * Brings the team back, and its ancestors with it: a subtree archived together has
	 * to be restorable from any point in it rather than walked by hand, and a live team
	 * under an archived one is invisible anyway.
	 */
	@Transactional
	fun unarchive(actor: User, id: UUID): Team {
		requireConfigurator(actor)
		get(id)
		val chain = listOf(id) + teams.ancestorIds(id)
		val restored = teams.findAllById(chain).filter { it.archived }.map { it.id }
		teams.setArchived(restored, false)
		restored.forEach {
			syncJobs.enqueue(SyncEntityType.TEAM, it, SyncOperation.UPSERT)
			events.publish(KansoEvent.team(ChangeKind.UPDATED, it))
		}
		return get(id)
	}

	/**
	 * Validated before a single row is written. A plan refused half-way would leave the
	 * team stripped of whatever had already been re-homed, with nothing to undo it.
	 *
	 * Returns the destination when there is one to check, null when there is nothing to
	 * move.
	 */
	private fun requireTicketDestination(team: Team, plan: DispositionPlan): UUID? {
		if (plan.tickets != DispositionChoice.KEEP) return null
		val doomed =
			if (plan.subTeams == DispositionChoice.TAKE) teams.descendantIds(team.id) else listOf(team.id)
		if (tickets.countByTeams(doomed) == 0) return null

		val target = plan.ticketsTargetTeamId ?: throw BadRequestException(
			"Keeping the tickets of team ${team.id} needs a destination team: a ticket has no team-less state"
		)
		if (target in doomed) {
			throw BadRequestException("Team $target is being removed and cannot receive the tickets")
		}
		teams.findById(target) ?: throw BadRequestException("No team $target")
		return target
	}

	/**
	 * Empties the team of everything the plan keeps and returns the teams that are
	 * actually going away — this one alone when its sub-teams were kept, the whole
	 * subtree when they were taken.
	 *
	 * [destructive] is the only difference between archiving and deleting: `take` means
	 * archived-alongside in one case and deleted-with in the other.
	 */
	private fun disperse(
		team: Team,
		plan: DispositionPlan,
		ticketsTarget: UUID?,
		destructive: Boolean,
	): List<UUID> {
		val doomed = if (plan.subTeams == DispositionChoice.TAKE) {
			teams.descendantIds(team.id)
		} else {
			// Explicitly, rather than leaving it to ON DELETE SET NULL: the cascade
			// promotes them to the root, which is the wrong answer exactly when a
			// grandparent exists.
			teams.directChildIds(team.id).forEach { child ->
				teams.setParent(child, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.TEAM, child, SyncOperation.UPSERT)
				events.publish(KansoEvent.team(ChangeKind.UPDATED, child))
			}
			listOf(team.id)
		}

		// Projects first: a ticket whose project has just been deleted must be read with
		// its project_id already cleared, not carry a stale one into its mirror push.
		disperseProjects(team, plan, doomed, destructive)
		disperseTickets(plan, doomed, ticketsTarget, destructive)
		return doomed
	}

	private fun disperseProjects(
		team: Team,
		plan: DispositionPlan,
		doomed: List<UUID>,
		destructive: Boolean,
	) {
		val held = projects.findAllById(doomed.flatMap { projects.idsByTeam(it) })
		if (held.isEmpty()) return

		when {
			plan.projects == DispositionChoice.KEEP -> held.forEach {
				projects.setTeam(it.id, team.parentTeamId)
				syncJobs.enqueue(SyncEntityType.PROJECT, it.id, SyncOperation.UPSERT)
				events.publish(KansoEvent.project(ChangeKind.UPDATED, it.id, team.parentTeamId))
			}

			destructive -> {
				// Read before the delete: the job carries the Notion page id, and by the
				// time the worker runs there is no row left to look it up from.
				projects.deleteByTeams(doomed)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.PROJECT,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.project(ChangeKind.DELETED, it.id, it.teamId))
				}
			}

			else -> {
				val teamOf = held.associate { it.id to it.teamId }
				projects.setArchivedByTeams(doomed, true).forEach {
					syncJobs.enqueue(SyncEntityType.PROJECT, it, SyncOperation.ARCHIVE)
					events.publish(KansoEvent.project(ChangeKind.UPDATED, it, teamOf[it]))
				}
			}
		}
	}

	private fun disperseTickets(
		plan: DispositionPlan,
		doomed: List<UUID>,
		ticketsTarget: UUID?,
		destructive: Boolean,
	) {
		val held = tickets.search(teamIds = doomed, includeArchived = true, limit = Int.MAX_VALUE)
			.sortedWith(compareBy<Ticket>({ it.teamId }, { it.number }))
		if (held.isEmpty()) return

		when {
			plan.tickets == DispositionChoice.KEEP -> {
				val target = checkNotNull(ticketsTarget) { "the destination is validated before dispersal" }
				val stranded = strandedTickets(held) { target }
				tickets.clearProjectFor(stranded)
				// One statement for the whole block: the row lock on the destination team
				// is held for the same span either way, so allocating one number at a time
				// would only add a round trip per ticket inside it.
				val numbers = teams.nextTicketNumbers(target, held.size)
				held.forEachIndexed { index, ticket ->
					tickets.moveToTeam(ticket.id, target, numbers[index])
					syncJobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
					events.publish(
						KansoEvent.ticket(
							ChangeKind.UPDATED,
							ticket.id,
							target,
							ticket.projectId.takeUnless { ticket.id in stranded },
						)
					)
				}
			}

			destructive -> {
				tickets.deleteByTeams(doomed)
				held.forEach {
					syncJobs.enqueue(
						SyncEntityType.TICKET,
						it.id,
						SyncOperation.DELETE,
						payload = deletePayload(it.mirror.notionPageId),
					)
					events.publish(KansoEvent.ticket(ChangeKind.DELETED, it.id, it.teamId, it.projectId))
				}
			}

			else -> {
				// A taken ticket keeps its team, but the projects may have just left for
				// the parent, so the same check applies against the team it stays in.
				val stranded = strandedTickets(held) { it.teamId }
				tickets.clearProjectFor(stranded)
				val byId = held.associateBy { it.id }
				val touched = tickets.setArchivedByTeams(doomed, true).toMutableSet()
				// A ticket that was already archived changes nothing by being archived
				// again, but losing its project is a change the mirror has to hear about.
				touched += stranded
				touched.forEach { ticketId ->
					syncJobs.enqueue(SyncEntityType.TICKET, ticketId, SyncOperation.ARCHIVE)
					events.publish(
						KansoEvent.ticket(
							ChangeKind.UPDATED,
							ticketId,
							byId[ticketId]?.teamId,
							if (ticketId in stranded) null else byId[ticketId]?.projectId,
						)
					)
				}
			}
		}
	}

	/**
	 * The tickets whose project is not coming with them.
	 *
	 * A ticket's project belongs to its team, and a disposition is the one operation
	 * that can break that on purpose: projects go to the parent team while tickets go
	 * to whichever team the plan names, which is a different team by design. The
	 * survivors of that split lose their grouping here, at the action that causes it,
	 * rather than silently on the next ordinary keystroke — the same answer, and the
	 * same reasoning, as `ProjectService.disperseTickets`.
	 *
	 * A project that is absent from the read is one the plan has just deleted, whose
	 * `project_id` the foreign key already set to NULL; a project with no team is the
	 * transverse case and belongs everywhere. Neither is stranded.
	 */
	private fun strandedTickets(held: List<Ticket>, teamOf: (Ticket) -> UUID): Set<UUID> {
		val projectIds = held.mapNotNull { it.projectId }.toSet()
		if (projectIds.isEmpty()) return emptySet()
		val teamOfProject = projects.findAllById(projectIds).associate { it.id to it.teamId }
		return held.filter { ticket ->
			val projectTeamId = ticket.projectId?.let { teamOfProject[it] } ?: return@filter false
			projectTeamId != teamOf(ticket)
		}.map { it.id }.toSet()
	}

	@Transactional
	fun create(actor: User, name: String, key: String?, parentTeamId: UUID?): Team {
		requireConfigurator(actor)
		if (parentTeamId != null && teams.findById(parentTeamId) == null) {
			throw BadRequestException("Parent team $parentTeamId does not exist")
		}
		val team = teams.insert(name, resolveKey(name, key), parentTeamId)
		syncJobs.enqueue(SyncEntityType.TEAM, team.id, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.CREATED, team.id))
		return team
	}

	@Transactional
	fun update(actor: User, id: UUID, name: String, key: String, parentTeamId: UUID?): Team {
		requireConfigurator(actor)
		val existing = get(id)
		if (parentTeamId != existing.parentTeamId) {
			if (parentTeamId == id) throw ConflictException("A team cannot be its own parent")
			val parent = parentTeamId?.let {
				teams.findById(it) ?: throw BadRequestException("Parent team $it does not exist")
			}
			if (teams.wouldCreateCycle(id, parentTeamId)) {
				throw ConflictException("Moving team $id under $parentTeamId would create a cycle")
			}
			// An unarchived team never has an archived ancestor: it would be invisible
			// in every view while still counting as live work.
			if (parent != null && parent.archived && !existing.archived) {
				throw ConflictException("Team ${parent.id} is archived; unarchive it before moving a team under it")
			}
		}
		if (key != existing.key) validateKey(key)

		val updated = teams.update(id, name, key, parentTeamId, existing.archived)
			?: throw NotFoundException("No team $id")
		syncJobs.enqueue(SyncEntityType.TEAM, id, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, id))
		return updated
	}

	/**
	 * Removes the team for good, after checking that the person is still agreeing to
	 * what they were shown.
	 *
	 * Recounting inside the transaction keeps the *operation* coherent but cannot keep
	 * *consent* honest: whoever agreed to destroy 47 tickets did not agree to destroy
	 * 50. Everywhere else in Kanso a write that turns out wrong snaps back; this one
	 * does not come back at all, which is what buys the extra round trip. Archiving
	 * snaps back, so it ignores [DispositionPlan.counts] entirely.
	 *
	 * Deleting locally still archives in Notion: Notion has no hard delete worth
	 * relying on, and a page that silently disappears from the mirror is worse than one
	 * marked archived.
	 */
	@Transactional
	fun delete(actor: User, id: UUID, plan: DispositionPlan) {
		requireConfigurator(actor)
		val team = get(id)

		val declared = plan.counts
			?: throw BadRequestException("Deleting a team requires the counts the confirmation showed")
		// Recounted at the plan's own reach: comparing direct rows against a plan that
		// takes the subtree would let a ticket created in a sub-team since the preview be
		// destroyed without a word, which is the one thing this check exists to prevent.
		val fresh = countsOf(id, plan.subTeams)
		if (declared != fresh) throw CountsChangedException(fresh)

		val ticketsTarget = requireTicketDestination(team, plan)
		val doomed = disperse(team, plan, ticketsTarget, destructive = true)

		val rows = teams.findAllById(doomed)
		rows.forEach {
			syncJobs.enqueue(
				SyncEntityType.TEAM,
				it.id,
				SyncOperation.DELETE,
				payload = deletePayload(it.mirror.notionPageId),
			)
		}
		// Order is irrelevant: parent_team_id is ON DELETE SET NULL, so no delete can
		// fail on a child that is still present.
		doomed.forEach { teams.delete(it) }
		rows.forEach { events.publish(KansoEvent.team(ChangeKind.DELETED, it.id)) }
	}

	// --- members -------------------------------------------------------------

	@Transactional(readOnly = true)
	fun members(teamId: UUID): List<TeamMember> {
		get(teamId)
		return teams.members(teamId)
	}

	@Transactional
	fun addMember(actor: User, teamId: UUID, userId: UUID, role: MemberRole): List<TeamMember> {
		requireConfigurator(actor)
		get(teamId)
		users.findById(userId) ?: throw BadRequestException("No user $userId")
		teams.addMember(teamId, userId, role)
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
		return teams.members(teamId)
	}

	@Transactional
	fun removeMember(actor: User, teamId: UUID, userId: UUID) {
		requireConfigurator(actor)
		get(teamId)
		if (!teams.removeMember(teamId, userId)) {
			throw NotFoundException("User $userId is not a member of team $teamId")
		}
		syncJobs.enqueue(SyncEntityType.TEAM, teamId, SyncOperation.UPSERT)
		events.publish(KansoEvent.team(ChangeKind.UPDATED, teamId))
	}

	// --- permissions ---------------------------------------------------------

	/**
	 * Teams are instance configuration, not daily work: who reports to whom decides
	 * what everyone else sees. The client hides what it may not do from `/api/me`;
	 * this is the backstop, not the mechanism.
	 */
	private fun requireConfigurator(actor: User) {
		if (!actor.instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can change teams")
		}
	}

	// --- keys ----------------------------------------------------------------

	private fun validateKey(key: String) {
		if (!KEY_PATTERN.matches(key)) {
			throw BadRequestException("Team key '$key' must be 2-8 characters, A-Z or 0-9")
		}
		if (teams.findByKey(key) != null) throw ConflictException("Team key '$key' is already taken")
	}

	/**
	 * The key prefixes every ticket identifier, so it has to be short, stable and
	 * unique. When the caller doesn't pick one we derive it from the name and add
	 * digits until it's free.
	 */
	private fun resolveKey(name: String, requested: String?): String {
		if (requested != null) {
			val key = requested.uppercase()
			validateKey(key)
			return key
		}
		val base = name.uppercase().filter { it.isLetterOrDigit() }.take(3).ifBlank { "TEAM" }
		if (teams.findByKey(base) == null && KEY_PATTERN.matches(base)) return base
		for (suffix in 2..99) {
			val candidate = "${base.take(6)}$suffix"
			if (teams.findByKey(candidate) == null) return candidate
		}
		throw ConflictException("Could not derive a free team key from '$name'; pass one explicitly")
	}

	private companion object {
		val KEY_PATTERN = Regex("^[A-Z0-9]{2,8}$")
	}
}

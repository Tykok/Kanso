package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.SyncOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TeamArchiveTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var teamRows: TeamRepository

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "arch-${UUID.randomUUID()}@kanso.test",
		displayName = "Archive ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "A${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newTeam(name: String, parentId: UUID? = null) = teams.create(admin, name, key(), parentId)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, title: String = "Ticket") = newTicketIn(teamId, null, title)

	private fun newTicketIn(teamId: UUID, projectId: UUID?, title: String = "Ticket") = tickets.create(
		actor = admin,
		teamId = teamId,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a member cannot archive a team`() {
		val team = newTeam("Core")
		assertFailsWith<AccessDeniedException> {
			teams.archive(user(InstanceRole.MEMBER), team.id, DispositionPlan())
		}
	}

	@Test
	fun `taking the sub-teams archives the whole subtree`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)

		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))

		assertTrue(teams.get(core.id).archived)
		assertTrue(teams.get(mobile.id).archived, "a taken sub-team goes with its parent")
		assertTrue(teams.get(ios.id).archived, "and so does everything under it")
	}

	@Test
	fun `keeping the sub-teams re-homes them to the grandparent, not to the root`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)

		teams.archive(admin, mobile.id, DispositionPlan(subTeams = DispositionChoice.KEEP))

		assertTrue(teams.get(mobile.id).archived)
		assertFalse(teams.get(ios.id).archived, "a kept sub-team stays active")
		assertEquals(core.id, teams.get(ios.id).parentTeamId, "ON DELETE SET NULL would have said root")
	}

	@Test
	fun `keeping the projects sends them to the parent team, or to no team at the root`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val nested = newProject(mobile.id)
		val rooted = newProject(core.id)

		teams.archive(admin, mobile.id, DispositionPlan(projects = DispositionChoice.KEEP))
		assertEquals(core.id, projects.get(nested.id).project.teamId)

		teams.archive(admin, core.id, DispositionPlan(projects = DispositionChoice.KEEP))
		assertNull(projects.get(rooted.id).project.teamId, "a root team has no parent to hand them to")
	}

	@Test
	fun `taking the projects archives them alongside the team`() {
		val core = newTeam("Core")
		val project = newProject(core.id)

		teams.archive(admin, core.id, DispositionPlan(projects = DispositionChoice.TAKE))

		assertTrue(projects.get(project.id).project.archived)
	}

	@Test
	fun `keeping the tickets renumbers them from the destination team's counter`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(growth.id, "already there")
		val first = newTicket(core.id, "first")
		val second = newTicket(core.id, "second")

		teams.archive(
			admin,
			core.id,
			DispositionPlan(tickets = DispositionChoice.KEEP, ticketsTargetTeamId = growth.id),
		)

		val moved = listOf(first, second).map { tickets.get(it.ticket.id) }
		assertEquals(listOf(growth.id, growth.id), moved.map { it.ticket.teamId })
		assertEquals(
			listOf("${growth.key}-2", "${growth.key}-3"),
			moved.map { it.identifier },
			"the block continues the destination counter, leaving no gap and no collision",
		)
	}

	@Test
	fun `tickets of a kept sub-team keep their identifier`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val growth = newTeam("Growth")
		val untouched = newTicket(mobile.id)

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.KEEP,
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
			),
		)

		val after = tickets.get(untouched.ticket.id)
		assertEquals(mobile.id, after.ticket.teamId)
		assertEquals(untouched.identifier, after.identifier, "nothing happens to them at all")
	}

	/**
	 * The archive path strands tickets exactly as the delete path does, and by a route
	 * the review did not name: the tickets are *taken* — they never move — while the
	 * projects are *kept*, which sends them to the parent team. The ticket ends up
	 * archived in a team whose projects have left, still pointing at one of them.
	 *
	 * Every other fixture in this file creates tickets with `projectId = null`, so
	 * nothing here had ever archived a ticket that had a project to lose.
	 */
	@Test
	fun `a taken ticket loses a project that was kept and sent to the parent`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(mobile.id)
		val ticket = newTicketIn(mobile.id, project.id)
		jobs.claimBatch(200, "drain")

		teams.archive(
			admin,
			mobile.id,
			DispositionPlan(projects = DispositionChoice.KEEP, tickets = DispositionChoice.TAKE),
		)

		assertEquals(core.id, projects.get(project.id).project.teamId, "the project went to the parent")
		val after = tickets.get(ticket.ticket.id).ticket
		assertTrue(after.archived, "taken means archived alongside the team")
		assertEquals(mobile.id, after.teamId, "and it never leaves its own team")
		assertNull(
			after.projectId,
			"the project belongs to Core now, and this ticket does not",
		)
		assertEquals(
			SyncOperation.ARCHIVE,
			jobs.claimBatch(200, "test").single { it.entityId == ticket.ticket.id }.operation,
		)
	}

	/**
	 * The same split, over a ticket that was already archived. `setArchivedByTeams`
	 * returns only the rows it actually changed, so this one is not among them — but
	 * it still lost its project, and the mirror has to hear about that or the Notion
	 * page keeps a relation Postgres no longer has.
	 */
	@Test
	fun `an already archived ticket still gets a push when the split takes its project`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(mobile.id)
		val ticket = newTicketIn(mobile.id, project.id)
		tickets.patch(admin, ticket.ticket.id, TicketPatch(archived = true))
		assertEquals(
			project.id,
			tickets.get(ticket.ticket.id).ticket.projectId,
			"the ordinary archive keypress leaves a matching project alone",
		)
		jobs.claimBatch(200, "drain")

		teams.archive(
			admin,
			mobile.id,
			DispositionPlan(projects = DispositionChoice.KEEP, tickets = DispositionChoice.TAKE),
		)

		assertNull(tickets.get(ticket.ticket.id).ticket.projectId)
		assertEquals(
			SyncOperation.ARCHIVE,
			jobs.claimBatch(200, "test").single { it.entityId == ticket.ticket.id }.operation,
			"nothing about its archived flag changed, but its project did",
		)
	}

	@Test
	fun `keeping tickets without a destination is refused and changes nothing`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		newTicket(core.id)

		assertFailsWith<BadRequestException> {
			teams.archive(admin, core.id, DispositionPlan(tickets = DispositionChoice.KEEP))
		}

		assertFalse(teams.get(core.id).archived, "a refused plan writes nothing at all")
		assertEquals(core.id, teams.get(mobile.id).parentTeamId, "not even the easy half of it")
	}

	@Test
	fun `the destination cannot be a team that is going away`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		newTicket(core.id)

		assertFailsWith<BadRequestException> {
			teams.archive(
				admin,
				core.id,
				DispositionPlan(
					subTeams = DispositionChoice.TAKE,
					tickets = DispositionChoice.KEEP,
					ticketsTargetTeamId = mobile.id,
				),
			)
		}
	}

	@Test
	fun `unarchiving a nested team unarchives its ancestors`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))

		teams.unarchive(admin, ios.id)

		assertFalse(teams.get(ios.id).archived)
		assertFalse(teams.get(mobile.id).archived, "an unarchived team may not have an archived ancestor")
		assertFalse(teams.get(core.id).archived)
	}

	@Test
	fun `a live team cannot be moved under an archived one`() {
		val archived = newTeam("Archived")
		val live = newTeam("Live")
		teams.archive(admin, archived.id, DispositionPlan())

		assertFailsWith<ConflictException> {
			teams.update(admin, live.id, live.name, live.key, archived.id)
		}
	}

	@Test
	fun `one mirror push per entity touched, whichever plan ran`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(core.id)
		val ticket = newTicket(core.id)
		jobs.claimBatch(200, "drain")

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.TAKE,
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
			),
		)

		val queued = jobs.claimBatch(200, "test")
		assertEquals(1, queued.count { it.entityId == core.id })
		assertEquals(1, queued.count { it.entityId == mobile.id })
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == project.id }.operation)
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == ticket.ticket.id }.operation)
		// The team rows too: nothing else in the suite says which operation they carry,
		// so ARCHIVE could be written UPSERT here and nothing would notice.
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == core.id }.operation)
		assertEquals(SyncOperation.ARCHIVE, queued.single { it.entityId == mobile.id }.operation)
	}

	@Test
	fun `unarchiving pushes the team and its ancestors back as live pages`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))
		jobs.claimBatch(200, "drain")

		teams.unarchive(admin, mobile.id)

		val queued = jobs.claimBatch(200, "test").associateBy { it.entityId }
		assertEquals(SyncOperation.UPSERT, queued[mobile.id]?.operation)
		assertEquals(SyncOperation.UPSERT, queued[core.id]?.operation, "the ancestor comes back too")
	}

	@Test
	fun `everything the plan re-homes is pushed as an update, not archived with the team`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		val growth = newTeam("Growth")
		val project = newProject(mobile.id)
		val ticket = newTicket(mobile.id)
		jobs.claimBatch(200, "drain")

		teams.archive(
			admin,
			mobile.id,
			DispositionPlan(
				subTeams = DispositionChoice.KEEP,
				projects = DispositionChoice.KEEP,
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
			),
		)

		val queued = jobs.claimBatch(200, "test").associateBy { it.entityId }
		assertEquals(SyncOperation.ARCHIVE, queued[mobile.id]?.operation)
		assertEquals(SyncOperation.UPSERT, queued[ios.id]?.operation, "reparented to the grandparent")
		assertEquals(SyncOperation.UPSERT, queued[project.id]?.operation, "re-homed to the parent team")
		assertEquals(SyncOperation.UPSERT, queued[ticket.ticket.id]?.operation, "moved and renumbered")
		assertNull(queued[core.id], "the grandparent changed in no way and needs no push")
	}

	/**
	 * The invariant the spec states and nothing tested: **an unarchived team never has
	 * an archived ancestor.** Every path that can move a team's archived flag or its
	 * parent is walked in sequence, and the whole tree is re-checked after each one —
	 * a single scenario would only ever show that one path happens to be safe.
	 */
	@Test
	fun `no sequence of archive and unarchive leaves a live team under an archived one`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		val tree = listOf(core.id, mobile.id, ios.id)

		fun check(step: String) {
			for (id in tree) {
				val team = teams.get(id)
				if (team.archived) continue
				val archivedAbove = teamRows.ancestorIds(id).map { teams.get(it) }.filter { it.archived }
				assertTrue(
					archivedAbove.isEmpty(),
					"after $step, ${team.name} is live under ${archivedAbove.map { it.name }}",
				)
			}
		}

		check("nothing at all")

		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))
		check("archiving the root, taking the subtree")

		teams.unarchive(admin, ios.id)
		check("unarchiving the leaf, which pulls its ancestors back")

		teams.archive(admin, mobile.id, DispositionPlan(subTeams = DispositionChoice.KEEP))
		check("archiving the middle, keeping the sub-teams")

		teams.unarchive(admin, mobile.id)
		check("unarchiving the middle again")

		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.KEEP))
		check("archiving the root, keeping the sub-teams")

		teams.unarchive(admin, core.id)
		check("unarchiving the root")

		// The last way in: reparenting. Refused outright rather than repaired after.
		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.KEEP))
		assertFailsWith<ConflictException> {
			teams.update(admin, mobile.id, mobile.name, mobile.key, core.id)
		}
		check("trying to move a live team under an archived one")
	}
}

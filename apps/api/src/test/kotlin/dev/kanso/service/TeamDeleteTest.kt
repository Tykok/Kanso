package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TeamDeleteTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "del-${UUID.randomUUID()}@kanso.test",
		displayName = "Delete ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "D${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newTeam(name: String, parentId: UUID? = null) = teams.create(admin, name, key(), parentId)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a member cannot delete a team`() {
		val team = newTeam("Core")
		assertFailsWith<AccessDeniedException> {
			teams.delete(user(InstanceRole.MEMBER), team.id, DispositionPlan(counts = teams.contents(team.id)))
		}
	}

	@Test
	fun `deleting without the counts the modal showed is refused`() {
		val team = newTeam("Core")
		val failure = assertFailsWith<BadRequestException> {
			teams.delete(admin, team.id, DispositionPlan())
		}
		assertTrue(failure.message!!.contains("counts"), failure.message!!)
	}

	@Test
	fun `keeping everything re-homes it and only the team disappears`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val ios = newTeam("iOS", mobile.id)
		val growth = newTeam("Growth")
		val project = newProject(mobile.id)
		val ticket = newTicket(mobile.id)

		teams.delete(
			admin,
			mobile.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = teams.contents(mobile.id),
			),
		)

		assertFailsWith<NotFoundException> { teams.get(mobile.id) }
		assertEquals(core.id, teams.get(ios.id).parentTeamId, "the sub-team goes to the grandparent")
		assertEquals(core.id, projects.get(project.id).project.teamId, "the project goes to the parent")
		assertEquals("${growth.key}-1", tickets.get(ticket.ticket.id).identifier, "the ticket is renamed for good")
	}

	@Test
	fun `taking everything destroys the whole subtree and what it held`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		val project = newProject(mobile.id)
		val ticket = newTicket(mobile.id)

		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				subTeams = DispositionChoice.TAKE,
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
				counts = teams.contents(core.id),
			),
		)

		assertFailsWith<NotFoundException> { teams.get(core.id) }
		assertFailsWith<NotFoundException> { teams.get(mobile.id) }
		assertFailsWith<NotFoundException> { projects.get(project.id) }
		assertFailsWith<NotFoundException> { tickets.get(ticket.ticket.id) }
	}

	@Test
	fun `a root team hands its kept projects to no team at all`() {
		val core = newTeam("Core")
		val project = newProject(core.id)

		teams.delete(admin, core.id, DispositionPlan(counts = teams.contents(core.id)))

		assertNull(projects.get(project.id).project.teamId)
	}

	@Test
	fun `a ticket created after the counts were read makes the delete fail with the fresh ones`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(core.id)
		val stale = teams.contents(core.id)
		newTicket(core.id)

		val failure = assertFailsWith<CountsChangedException> {
			teams.delete(
				admin,
				core.id,
				DispositionPlan(
					tickets = DispositionChoice.KEEP,
					ticketsTargetTeamId = growth.id,
					counts = stale,
				),
			)
		}

		assertEquals(DispositionCounts(subTeams = 0, projects = 0, tickets = 2), failure.counts)
		assertEquals("Core", teams.get(core.id).name, "nothing happens; the modal reopens on the truth")

		// Replaying with the numbers the person can now actually see goes through.
		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = failure.counts,
			),
		)
		assertFailsWith<NotFoundException> { teams.get(core.id) }
	}

	@Test
	fun `the same drift lets an archive through`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		newTicket(core.id)
		val stale = teams.contents(core.id)
		newTicket(core.id)

		teams.archive(
			admin,
			core.id,
			DispositionPlan(
				tickets = DispositionChoice.KEEP,
				ticketsTargetTeamId = growth.id,
				counts = stale,
			),
		)

		assertTrue(teams.get(core.id).archived, "archiving is reversible, so it does not pay for consent")
		assertEquals(2, tickets.search(growth.id, false, null, emptyList(), null, true, 50, 0).size)
	}

	@Test
	fun `each destroyed entity gets its own delete job, not a silent cascade`() {
		val core = newTeam("Core")
		val project = newProject(core.id)
		val ticket = newTicket(core.id)
		jobs.claimBatch(200, "drain")

		teams.delete(
			admin,
			core.id,
			DispositionPlan(
				projects = DispositionChoice.TAKE,
				tickets = DispositionChoice.TAKE,
				counts = teams.contents(core.id),
			),
		)

		val queued = jobs.claimBatch(200, "test")
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == core.id }.operation)
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == project.id }.operation)
		assertEquals(
			SyncOperation.DELETE,
			queued.single { it.entityId == ticket.ticket.id }.operation,
			"ON DELETE CASCADE would have destroyed it in Postgres and left the Notion page behind",
		)
	}
}

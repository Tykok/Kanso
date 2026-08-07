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
class ProjectDispositionTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "proj-${UUID.randomUUID()}@kanso.test",
			displayName = "Project admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam() =
		teams.create(admin, "Team ${UUID.randomUUID().toString().take(4)}", "P${UUID.randomUUID().toString().take(5).uppercase()}", null)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, projectId: UUID?) = tickets.create(
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		startDate = null,
		dueDate = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `archiving with keep only detaches the tickets`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.archive(project.id, DispositionPlan(tickets = DispositionChoice.KEEP))

		assertTrue(projects.get(project.id).project.archived)
		val after = tickets.get(ticket.ticket.id)
		assertNull(after.ticket.projectId, "the ticket loses its project, not its life")
		assertFalse(after.ticket.archived)
		assertEquals(team.id, after.ticket.teamId, "and never its team")
	}

	@Test
	fun `archiving with take archives the tickets alongside it`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.archive(project.id, DispositionPlan(tickets = DispositionChoice.TAKE))

		val after = tickets.get(ticket.ticket.id)
		assertTrue(after.ticket.archived)
		assertEquals(project.id, after.ticket.projectId, "taken means it goes along, not that it is cut loose")
	}

	@Test
	fun `unarchiving brings the project back`() {
		val team = newTeam()
		val project = newProject(team.id)
		projects.archive(project.id, DispositionPlan())

		assertFalse(projects.unarchive(project.id).project.archived)
	}

	@Test
	fun `deleting with keep leaves the tickets behind, without their project`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)

		projects.delete(project.id, DispositionPlan(counts = projects.contents(project.id)))

		assertFailsWith<NotFoundException> { projects.get(project.id) }
		assertNull(tickets.get(ticket.ticket.id).ticket.projectId)
	}

	@Test
	fun `deleting with take removes them, each with its own delete job`() {
		val team = newTeam()
		val project = newProject(team.id)
		val ticket = newTicket(team.id, project.id)
		jobs.claimBatch(200, "drain")

		projects.delete(
			project.id,
			DispositionPlan(tickets = DispositionChoice.TAKE, counts = projects.contents(project.id)),
		)

		assertFailsWith<NotFoundException> { tickets.get(ticket.ticket.id) }
		val queued = jobs.claimBatch(200, "test")
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == project.id }.operation)
		assertEquals(SyncOperation.DELETE, queued.single { it.entityId == ticket.ticket.id }.operation)
	}

	@Test
	fun `deleting a project on stale counts is refused, archiving it is not`() {
		val team = newTeam()
		val project = newProject(team.id)
		newTicket(team.id, project.id)
		val stale = projects.contents(project.id)
		newTicket(team.id, project.id)

		val failure = assertFailsWith<CountsChangedException> {
			projects.delete(project.id, DispositionPlan(counts = stale))
		}
		assertEquals(DispositionCounts(subTeams = 0, projects = 0, tickets = 2), failure.counts)

		projects.archive(project.id, DispositionPlan(counts = stale))
		assertTrue(projects.get(project.id).project.archived)
	}

	@Test
	fun `deleting without the counts the modal showed is refused`() {
		val project = newProject(newTeam().id)
		val failure = assertFailsWith<BadRequestException> { projects.delete(project.id, DispositionPlan()) }
		assertTrue(failure.message!!.contains("counts"), failure.message!!)
	}
}

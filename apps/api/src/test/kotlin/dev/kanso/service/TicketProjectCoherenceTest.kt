package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class TicketProjectCoherenceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "coh-${UUID.randomUUID()}@kanso.test",
			displayName = "Coherence admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam(name: String) =
		teams.create(admin, name, "X${UUID.randomUUID().toString().take(5).uppercase()}", null)

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
	fun `moving a ticket to another team drops a project that belonged to the old one`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val moved = tickets.patch(ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(growth.id, moved.ticket.teamId)
		assertNull(moved.ticket.projectId, "no view of Growth would ever have shown that project")
	}

	@Test
	fun `a team-less project survives the move`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val transverse = newProject(null)
		val ticket = newTicket(core.id, transverse.id)

		val moved = tickets.patch(ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(transverse.id, moved.ticket.projectId, "a transverse project belongs to no team to leave")
	}

	@Test
	fun `moving a ticket inside its own team leaves its project alone`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val renamed = tickets.patch(ticket.ticket.id, TicketPatch(title = "Still grouped"))

		assertEquals(coreProject.id, renamed.ticket.projectId)
	}

	@Test
	fun `setting a team and a matching project in one patch keeps the link`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val ticket = newTicket(core.id, null)

		val moved = tickets.patch(
			ticket.ticket.id,
			TicketPatch(teamId = growth.id, projectId = growthProject.id),
		)

		assertEquals(growth.id, moved.ticket.teamId)
		assertEquals(growthProject.id, moved.ticket.projectId)
	}
}

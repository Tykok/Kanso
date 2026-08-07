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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
	fun `an explicit projectId from another team is rejected, and the ticket is left untouched`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val failure = assertFailsWith<BadRequestException> {
			tickets.patch(ticket.ticket.id, TicketPatch(projectId = growthProject.id))
		}
		assertTrue(failure.message!!.contains(growth.id.toString()), failure.message!!)
		assertTrue(failure.message!!.contains(core.id.toString()), failure.message!!)

		val unchanged = tickets.get(ticket.ticket.id)
		assertEquals(core.id, unchanged.ticket.teamId)
		assertEquals(coreProject.id, unchanged.ticket.projectId, "a rejected patch must not partially apply")
	}

	@Test
	fun `an explicit projectId from another team is accepted alongside a teamId change to that team`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val ticket = newTicket(core.id, null)

		val moved = tickets.patch(
			ticket.ticket.id,
			TicketPatch(teamId = growth.id, projectId = growthProject.id),
		)

		assertEquals(growth.id, moved.ticket.teamId)
		assertEquals(growthProject.id, moved.ticket.projectId, "the effective team now matches the project's")
	}

	@Test
	fun `an explicit projectId matching the ticket's current team is accepted`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, null)

		val patched = tickets.patch(ticket.ticket.id, TicketPatch(projectId = coreProject.id))

		assertEquals(core.id, patched.ticket.teamId)
		assertEquals(coreProject.id, patched.ticket.projectId)
	}

	@Test
	fun `unsetting projectId clears it without throwing`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val patched = tickets.patch(ticket.ticket.id, TicketPatch(unset = setOf("projectId")))

		assertEquals(core.id, patched.ticket.teamId)
		assertNull(patched.ticket.projectId)
	}
}

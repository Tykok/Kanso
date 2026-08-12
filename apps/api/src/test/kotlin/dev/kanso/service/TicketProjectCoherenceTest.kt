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
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	/** The one field under test; everything else is handed back unchanged. */
	private fun moveProject(id: UUID, teamId: UUID?) = projects.get(id).let { current ->
		projects.update(
			id = id,
			name = current.project.name,
			status = current.project.status,
			start = current.project.start,
			end = current.project.end,
			leadUserId = current.project.leadUserId,
			teamId = teamId,
			docIds = null,
		)
	}

	private fun newTicket(teamId: UUID, projectId: UUID?) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = "Ticket ${UUID.randomUUID().toString().take(4)}",
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
	fun `moving a ticket to another team drops a project that belonged to the old one`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val moved = tickets.patch(admin, ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(growth.id, moved.ticket.teamId)
		assertNull(moved.ticket.projectId, "no view of Growth would ever have shown that project")
	}

	@Test
	fun `a team-less project survives the move`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val transverse = newProject(null)
		val ticket = newTicket(core.id, transverse.id)

		val moved = tickets.patch(admin, ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals(transverse.id, moved.ticket.projectId, "a transverse project belongs to no team to leave")
	}

	@Test
	fun `an ordinary patch that touches neither teamId nor projectId leaves a matching inherited project alone`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val renamed = tickets.patch(admin, ticket.ticket.id, TicketPatch(title = "Still grouped"))

		assertEquals(core.id, renamed.ticket.teamId)
		assertEquals(
			coreProject.id,
			renamed.ticket.projectId,
			"an inherited project whose team already matches must survive an unrelated field edit",
		)
	}

	@Test
	fun `an explicit projectId from another team is rejected, and the ticket is left untouched`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val failure = assertFailsWith<BadRequestException> {
			tickets.patch(admin, ticket.ticket.id, TicketPatch(projectId = growthProject.id))
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
			admin,
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

		val patched = tickets.patch(admin, ticket.ticket.id, TicketPatch(projectId = coreProject.id))

		assertEquals(core.id, patched.ticket.teamId)
		assertEquals(coreProject.id, patched.ticket.projectId)
	}

	@Test
	fun `creating a ticket against another team's project is refused`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)

		val failure = assertFailsWith<BadRequestException> { newTicket(core.id, growthProject.id) }

		assertTrue(failure.message!!.contains(growth.id.toString()), failure.message!!)
		assertTrue(failure.message!!.contains(core.id.toString()), failure.message!!)
	}

	@Test
	fun `creating a ticket against a team-less project is allowed from any team`() {
		val core = newTeam("Core")
		val transverse = newProject(null)

		assertEquals(transverse.id, newTicket(core.id, transverse.id).ticket.projectId)
	}

	@Test
	fun `moving a project into another team is refused while its tickets are elsewhere`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val project = newProject(core.id)
		val ticket = newTicket(core.id, project.id)

		val failure = assertFailsWith<ConflictException> { moveProject(project.id, growth.id) }

		assertTrue(failure.message!!.contains(growth.id.toString()), failure.message!!)
		assertEquals(core.id, projects.get(project.id).project.teamId, "a refused move writes nothing")
		assertEquals(project.id, tickets.get(ticket.ticket.id).ticket.projectId, "and orphans nothing")
	}

	@Test
	fun `the same move goes through once nothing points at it from outside`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val project = newProject(core.id)
		newTicket(core.id, null)

		assertEquals(growth.id, moveProject(project.id, growth.id).project.teamId)
	}

	@Test
	fun `clearing a project's team is always allowed, since transverse belongs everywhere`() {
		val core = newTeam("Core")
		val project = newProject(core.id)
		val ticket = newTicket(core.id, project.id)

		assertNull(moveProject(project.id, null).project.teamId)
		assertEquals(project.id, tickets.get(ticket.ticket.id).ticket.projectId)
	}

	@Test
	fun `moving a ticket to another team takes that team's next number`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		// The destination is not empty, which is what a move into a fresh team hides:
		// keeping the source's number here either collides on `UNIQUE (team_id, number)`
		// or squats a number the destination's counter will hand out again.
		val squatter = newTicket(growth.id, null)
		val ticket = newTicket(core.id, null)
		assertEquals("${core.key}-1", ticket.identifier)

		val moved = tickets.patch(admin, ticket.ticket.id, TicketPatch(teamId = growth.id))

		assertEquals("${growth.key}-2", moved.identifier, "renumbered from the destination's counter")
		assertEquals("${growth.key}-1", tickets.get(squatter.ticket.id).identifier, "and nothing else moved")
		// The counter really moved, so the next creation there does not collide either.
		assertEquals("${growth.key}-3", newTicket(growth.id, null).identifier)
	}

	@Test
	fun `unsetting projectId clears it without throwing`() {
		val core = newTeam("Core")
		val coreProject = newProject(core.id)
		val ticket = newTicket(core.id, coreProject.id)

		val patched = tickets.patch(admin, ticket.ticket.id, TicketPatch(unset = setOf("projectId")))

		assertEquals(core.id, patched.ticket.teamId)
		assertNull(patched.ticket.projectId)
	}
}

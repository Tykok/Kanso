package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionCounts
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

@Transactional
class DispositionCountsTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "counts-${UUID.randomUUID()}@kanso.test",
			displayName = "Counts admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun key() = "C${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		startDate = null,
		endDate = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	)

	private fun newTicket(teamId: UUID, projectId: UUID? = null) = tickets.create(
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
	fun `a team counts what it holds directly, not what its sub-teams hold`() {
		val core = teams.create(admin, "Core", key(), null)
		val mobile = teams.create(admin, "Mobile", key(), core.id)
		teams.create(admin, "iOS", key(), mobile.id)
		newProject(core.id)
		newProject(mobile.id)
		newTicket(core.id)
		newTicket(core.id)
		newTicket(mobile.id)

		assertEquals(
			DispositionCounts(subTeams = 1, projects = 1, tickets = 2),
			teams.contents(core.id),
			"a kept sub-team leaves with its own contents, so they are not Core's to dispose of",
		)
	}

	@Test
	fun `an archived ticket still has to be disposed of, so it still counts`() {
		val team = teams.create(admin, "Core", key(), null)
		val ticket = newTicket(team.id)
		tickets.patch(ticket.ticket.id, TicketPatch(archived = true))

		assertEquals(1, teams.contents(team.id).tickets)
	}

	@Test
	fun `a project counts only its tickets`() {
		val team = teams.create(admin, "Core", key(), null)
		val project = newProject(team.id)
		newTicket(team.id, project.project.id)
		newTicket(team.id, project.project.id)
		newTicket(team.id)

		assertEquals(
			DispositionCounts(subTeams = 0, projects = 0, tickets = 2),
			projects.contents(project.project.id),
			"a project holds no teams and no projects; only its tickets need a decision",
		)
	}

	@Test
	fun `counting something that does not exist is a 404, not a zero`() {
		assertFailsWith<NotFoundException> { teams.contents(UUID.randomUUID()) }
		assertFailsWith<NotFoundException> { projects.contents(UUID.randomUUID()) }
	}

	@Test
	fun `a disposition choice parses from the wire and refuses anything else`() {
		assertEquals(DispositionChoice.TAKE, DispositionChoice.from("take"))
		assertEquals(DispositionChoice.KEEP, DispositionChoice.from("keep"))
		assertFailsWith<IllegalArgumentException> { DispositionChoice.from("destroy") }
	}
}

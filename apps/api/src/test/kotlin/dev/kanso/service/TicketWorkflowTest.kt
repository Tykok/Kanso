package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundOperation
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class TicketWorkflowTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	/** Team writes are admin-only, so every team this file builds needs one. */
	private val admin: User by lazy {
		users.createLocalUser(
			email = "workflow-${UUID.randomUUID()}@kanso.test",
			displayName = "Workflow admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam(name: String = "Team ${UUID.randomUUID().toString().take(4)}") =
		teams.create(admin, name, "K${UUID.randomUUID().toString().take(4).uppercase()}", null)

	@Test
	fun `a new ticket gets a short identifier scoped to its team`() {
		val team = newTeam()

		val first = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "First",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		val second = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Second",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		assertEquals("${team.key}-1", first.identifier)
		assertEquals("${team.key}-2", second.identifier)
	}

	@Test
	fun `every write queues exactly one mirror push for the row`() {
		val team = newTeam()
		val ticket = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Queued",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		// Three rapid keystrokes, as a status change would produce.
		tickets.patch(admin, ticket.ticket.id, TicketPatch(status = TicketStatus.IN_PROGRESS))
		tickets.patch(admin, ticket.ticket.id, TicketPatch(priority = TicketPriority.HIGH))
		tickets.patch(admin, ticket.ticket.id, TicketPatch(title = "Renamed"))

		val queued = jobs.claimBatch(Destination.NOTION, 50, "test").filter { it.entityId == ticket.ticket.id }
		assertEquals(1, queued.size, "the mirror needs one push carrying the final state, not four")
	}

	@Test
	fun `a patch leaves untouched fields alone and clears only what is named`() {
		val team = newTeam()
		val created = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Keep me",
			description = "Some context",
			status = TicketStatus.TODO,
			priority = TicketPriority.HIGH,
			start = null,
			due = KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		val afterStatus = tickets.patch(admin, created.ticket.id, TicketPatch(status = TicketStatus.DONE))
		assertEquals("Keep me", afterStatus.ticket.title)
		assertEquals("Some context", afterStatus.ticket.description)
		assertEquals(TicketPriority.HIGH, afterStatus.ticket.priority)
		assertEquals(
			KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false),
			afterStatus.ticket.due,
		)

		val cleared = tickets.patch(admin, created.ticket.id, TicketPatch(unset = setOf("due")))
		assertNull(cleared.ticket.due, "naming a field in unset must actually clear it")
		assertEquals("Some context", cleared.ticket.description, "unset must not touch anything else")
	}

	@Test
	fun `a due date before the start date is refused`() {
		val team = newTeam()
		val failure = assertFailsWith<BadRequestException> {
			tickets.create(
				actor = admin,
				teamId = team.id,
				title = "Backwards",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = KansoInstant(LocalDate.of(2026, 9, 10).atStartOfDay().atOffset(ZoneOffset.UTC), false),
				due = KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false),
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}
		assertTrue(failure.message!!.contains("before start"), failure.message!!)
	}

	@Test
	fun `an unknown assignee is rejected rather than silently dropped`() {
		val team = newTeam()
		assertFailsWith<BadRequestException> {
			tickets.create(
				actor = admin,
				teamId = team.id,
				title = "Ghost",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = listOf(UUID.randomUUID()),
				docIds = emptyList(),
			)
		}
	}

	@Test
	fun `tickets can be listed across a team subtree`() {
		val parent = newTeam("Parent")
		val child = teams.create(admin, "Child", "CH${UUID.randomUUID().toString().take(3).uppercase()}", parent.id)

		for (team in listOf(parent, child)) {
			tickets.create(
				actor = admin,
				teamId = team.id,
				title = "In ${team.name}",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}

		val shallow = tickets.search(parent.id, false, null, emptyList(), null, false, 50, 0)
		val deep = tickets.search(parent.id, true, null, emptyList(), null, false, 50, 0)

		assertEquals(1, shallow.size, "without includeDescendants only the team's own tickets show")
		assertEquals(2, deep.size, "with it, nested teams are included")
	}

	@Test
	fun `a project groups tickets across the team that owns them`() {
		val team = newTeam()
		val project = projects.create(
			actor = admin,
			name = "Ship it",
			status = ProjectStatus.IN_PROGRESS,
			start = KansoInstant(LocalDate.of(2026, 8, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false),
			end = KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false),
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		)

		val ticket = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Part of the project",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = project.project.id,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		val inProject = tickets.search(null, false, project.project.id, emptyList(), null, false, 50, 0)
		assertEquals(listOf(ticket.ticket.id), inProject.map { it.ticket.id })
	}

	@Test
	fun `archiving a ticket queues an archive rather than an update`() {
		val team = newTeam()
		val ticket = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "To archive",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		jobs.claimBatch(Destination.NOTION, 50, "drain")

		tickets.patch(admin, ticket.ticket.id, TicketPatch(archived = true))

		val queued = jobs.claimBatch(Destination.NOTION, 50, "test").single { it.entityId == ticket.ticket.id }
		assertEquals(
			dev.kanso.outbox.OutboundOperation.ARCHIVE,
			queued.operation,
			"Notion archives rather than deletes, so archiving is its own operation",
		)
	}

	@Test
	fun `completedAt is stamped on the way into done and cleared on the way out`() {
		val team = newTeam()
		val created = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Finish me",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		assertNull(created.ticket.completedAt, "a new ticket has not been completed")

		val done = tickets.patch(admin, created.ticket.id, TicketPatch(status = TicketStatus.DONE))
		assertNotNull(done.ticket.completedAt, "entering done records when it happened")

		val renamed = tickets.patch(admin, created.ticket.id, TicketPatch(title = "Still done"))
		assertEquals(
			done.ticket.completedAt,
			renamed.ticket.completedAt,
			"an unrelated edit must not move the completion date — that is why updatedAt cannot serve",
		)

		val reopened = tickets.patch(admin, created.ticket.id, TicketPatch(status = TicketStatus.IN_PROGRESS))
		assertNull(reopened.ticket.completedAt, "leaving done clears it")
	}

	@Test
	fun `a team cannot be moved under its own descendant`() {
		val root = newTeam("Root")
		val child = teams.create(admin, "Child", "CD${UUID.randomUUID().toString().take(3).uppercase()}", root.id)

		assertFailsWith<ConflictException> {
			teams.update(admin, root.id, root.name, root.key, child.id)
		}
	}
	@Test
	fun `a ticket created already done is stamped at creation`() {
		val team = newTeam()

		val created = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "Logged after the fact",
			description = null,
			status = TicketStatus.DONE,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		assertNotNull(
			created.ticket.completedAt,
			"logging finished work is normal, and a project's bounds fall back on completion dates",
		)
	}
}

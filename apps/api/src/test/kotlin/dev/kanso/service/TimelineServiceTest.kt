package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Transactional
class TimelineServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var timeline: TimelineService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "tl-${UUID.randomUUID()}@kanso.test",
			displayName = "Timeline admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "TL", "T${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, projectId: UUID?, start: Int?, due: Int?, status: TicketStatus = TicketStatus.TODO) =
		tickets.create(
			teamId = team.id,
			title = title,
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = start?.let(::day),
			due = due?.let(::day),
			projectId = projectId,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

	@Test
	fun `a project with no explicit bounds derives them from its tickets`() {
		val project = projects.create(
			name = "Derived",
			status = ProjectStatus.IN_PROGRESS,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		ticket("Early", project.id, 3, 6)
		ticket("Late", project.id, 10, 20)

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(3).at, row.start!!.at)
		assertEquals(day(20).at, row.end!!.at)
		assertTrue(row.startDerived && row.endDerived, "nothing was posed, so both bounds are consequences")
	}

	@Test
	fun `an explicit bound wins over the derivation, one bound at a time`() {
		val project = projects.create(
			name = "Half posed",
			status = ProjectStatus.IN_PROGRESS,
			start = day(1),
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		ticket("Only", project.id, 5, 9)

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertEquals(day(1).at, row.start!!.at)
		assertEquals(false, row.startDerived)
		assertEquals(day(9).at, row.end!!.at)
		assertEquals(true, row.endDerived)
	}

	@Test
	fun `a project whose tickets are only done falls back to when they were done`() {
		val project = projects.create(
			name = "Retrospective",
			status = ProjectStatus.COMPLETED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		val id = ticket("Undated but finished", project.id, null, null)
		tickets.patch(admin, id, TicketPatch(status = TicketStatus.DONE))

		val row = timeline.load(teamId = team.id, projectId = null).projects.single { it.id == project.id }

		assertTrue(row.start != null, "a completion date is a worse answer than a plan, and a better one than a blank row")
	}

	@Test
	fun `undated tickets are listed separately rather than dropped`() {
		val id = ticket("No dates", null, null, null)

		val view = timeline.load(teamId = team.id, projectId = null)

		assertTrue(view.unscheduled.any { it.id == id })
		assertTrue(view.tickets.none { it.id == id })
	}

	@Test
	fun `criticality is computed over the whole component, not the visible scope`() {
		val other = teams.create(admin, "Other", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val here = ticket("Here", null, 1, 10)
		val elsewhere = tickets.create(
			teamId = other.id,
			title = "Elsewhere",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = day(10),
			due = day(30),
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		deps.insert(here, elsewhere)

		val view = timeline.load(teamId = team.id, projectId = null)
		val row = view.tickets.single { it.id == here }

		assertEquals(true, row.critical, "the chain ends outside the scope, and that is what anchors it")
		assertTrue(
			view.dependencies.single().outOfScope,
			"the other end is not in the response, so the view draws a stub",
		)
	}

	@Test
	fun `a successor that starts before its predecessor ends is an overlap, not a violation`() {
		val first = ticket("First", null, 1, 10)
		val second = ticket("Second", null, 20, 25)
		deps.insert(first, second)
		// Dragged backwards, under its own predecessor. `Cascade` never examines this
		// edge: its descent only considers a node one of whose predecessors moved.
		tickets.patch(admin, second, TicketPatch(start = day(5), due = day(9)))

		val edge = timeline.load(teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.overlap, "the constraint is broken right now")
		assertEquals(false, edge.violated, "but the successor is not done, so the cascade could still repair it")
	}

	@Test
	fun `a done successor is violated and not merely overlapping`() {
		val first = ticket("Predecessor", null, 10, 20)
		val second = ticket("Finished early", null, 1, 5, status = TicketStatus.DONE)
		deps.insert(first, second)

		val edge = timeline.load(teamId = team.id, projectId = null)
			.dependencies.single { it.predecessorId == first && it.successorId == second }

		assertEquals(true, edge.violated)
		assertEquals(false, edge.overlap, "the two are exclusive: one names what can be repaired, the other what cannot")
	}
}

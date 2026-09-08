package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class ScheduleServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var repo: TicketRepository
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "sched-${UUID.randomUUID()}@kanso.test",
			displayName = "Sched admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Sched", "S${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, start: Int?, due: Int?): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = "todo",
		priority = TicketPriority.NONE,
		start = start?.let(::day),
		due = due?.let(::day),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	@Test
	fun `lengthening a predecessor slides its successor and persists the move`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		deps.insert(a, b)

		tickets.patch(admin, a, TicketPatch(due = day(12)))

		val moved = repo.findById(b)!!
		assertEquals(day(12).at, moved.start!!.at, "B now starts when A ends")
		assertEquals(day(17).at, moved.due!!.at, "and keeps the five days it had")
	}

	@Test
	fun `a cascade queues one mirror push per ticket it actually moved`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		val untouched = ticket("Elsewhere", 1, 2)
		deps.insert(a, b)
		jobs.claimBatch(Destination.NOTION, 100, "drain")

		tickets.patch(admin, a, TicketPatch(due = day(12)))

		val queued = jobs.claimBatch(Destination.NOTION, 100, "test").map { it.entityId }.toSet()
		assertEquals(setOf(a, b), queued, "the moved successor needs its own push; nothing else does")
		assertEquals(false, untouched in queued)
	}

	@Test
	fun `slack means an adjustment writes nothing downstream`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 20, 25)
		deps.insert(a, b)

		tickets.patch(admin, a, TicketPatch(due = day(12)))

		val untouched = repo.findById(b)!!
		assertEquals(day(20).at, untouched.start!!.at, "eight days of slack absorbed two days of delay")
	}
	@Test
	fun `a milestone stays a milestone when the cascade moves it`() {
		val a = ticket("A", 1, 10)
		// A deadline with no start: one bound on purpose, and the shape `Node` calls a
		// milestone. Writing both bounds would silently turn it into a dated span.
		val milestone = ticket("Deadline", null, 12)
		deps.insert(a, milestone)

		tickets.patch(admin, a, TicketPatch(due = day(14)))

		val moved = repo.findById(milestone)!!
		assertEquals(day(14).at, moved.due!!.at, "the milestone follows its predecessor")
		assertNull(moved.start, "and does not sprout a start it never had")
	}

	@Test
	fun `a date arriving from Notion goes through the cascade like any other write`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		deps.insert(a, b)

		// What the poller does: write the scalar, then let the engine settle the graph.
		repo.reschedule(a, day(1).at, day(12).at)
		schedule.cascadeFrom(a)

		assertEquals(
			day(12).at,
			repo.findById(b)!!.start!!.at,
			"a date edited in Notion must not bypass the engine and break the plan in silence",
		)
	}
}

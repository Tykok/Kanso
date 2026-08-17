package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log is asserted through the service, never through an event: the suite is
 * `@Transactional` and rolls back, so `EventPublisher`'s `afterCommit` never fires —
 * which is the whole reason the log is a table rather than a listener on `pg_notify`.
 */
@Transactional
class ActivityServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(name: String) = users.createLocalUser(
		email = "act-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.ADMIN,
	)

	private val actor: User by lazy { user("Log Keeper") }

	private val team by lazy {
		teams.create(actor, "Logged", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String = "Echo") = tickets.create(
		actor = actor,
		teamId = team.id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	private fun log(entityId: UUID) = activity.forEntity(ActivityEntity.TICKET, entityId)

	@Test
	fun `a status change records one activity row carrying before and after`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = TicketStatus.IN_PROGRESS))

		val rows = log(ticket.id)

		assertEquals(listOf(ActivityKind.STATUS_CHANGED, ActivityKind.CREATED), rows.map { it.kind })
		assertEquals("todo", rows.first().payload["from"])
		assertEquals("in_progress", rows.first().payload["to"])
		assertEquals(actor.id, rows.first().actor?.id, "a feed names the person, not a uuid")
	}

	@Test
	fun `a patch that changed nothing records nothing`() {
		val ticket = ticket()
		tickets.patch(actor, ticket.id, TicketPatch(status = TicketStatus.TODO, title = "Echo"))

		assertEquals(
			listOf(ActivityKind.CREATED),
			log(ticket.id).map { it.kind },
			"one row per scalar that actually changed — a no-op patch changed none",
		)
	}

	@Test
	fun `one patch touching three scalars records three rows, not one`() {
		val ticket = ticket()
		tickets.patch(
			actor,
			ticket.id,
			TicketPatch(title = "Renamed", status = TicketStatus.DONE, priority = TicketPriority.HIGH),
		)

		val kinds = log(ticket.id).map { it.kind }

		assertEquals(4, kinds.size, "three changes and the creation")
		assertTrue(ActivityKind.RENAMED in kinds)
		assertTrue(ActivityKind.STATUS_CHANGED in kinds)
		assertTrue(ActivityKind.PRIORITY_CHANGED in kinds)
	}

	@Test
	fun `a date moving is recorded per bound, so a feed can say which one moved`() {
		val ticket = ticket()
		val due = KansoInstant(LocalDate.of(2026, 9, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false)

		tickets.patch(actor, ticket.id, TicketPatch(due = due))

		val scheduled = log(ticket.id).single { it.kind == ActivityKind.SCHEDULED }
		assertEquals("due", scheduled.payload["field"])
		assertEquals(due.at.toString(), scheduled.payload["to"])
	}

	@Test
	fun `assigning and unassigning are one row each, naming who`() {
		val ticket = ticket()
		val lea = user("Lea")

		tickets.setAssignees(actor, ticket.id, listOf(lea.id))
		tickets.setAssignees(actor, ticket.id, emptyList())

		val rows = log(ticket.id).filter { it.kind != ActivityKind.CREATED }
		assertEquals(listOf(ActivityKind.UNASSIGNED, ActivityKind.ASSIGNED), rows.map { it.kind })
		assertTrue(rows.all { it.payload["userId"] == lea.id.toString() })
	}

	@Test
	fun `archiving and unarchiving both land, with the direction in the payload`() {
		val ticket = ticket()

		tickets.patch(actor, ticket.id, TicketPatch(archived = true))
		tickets.patch(actor, ticket.id, TicketPatch(archived = false))

		val archived = log(ticket.id).filter { it.kind == ActivityKind.ARCHIVED }
		assertEquals(2, archived.size, "coming back out of the archive is a decision too")
		assertEquals(false, archived.first().payload["to"])
	}

	@Test
	fun `the feed is newest first, and stops at the limit it was given`() {
		val ticket = ticket()
		for (title in listOf("One", "Two", "Three")) {
			tickets.patch(actor, ticket.id, TicketPatch(title = title))
		}

		val rows = activity.forEntity(ActivityEntity.TICKET, ticket.id, limit = 2)

		assertEquals(2, rows.size)
		assertTrue(
			rows.first().createdAt >= rows.last().createdAt,
			"every reader of this list draws a feed, and a feed starts at the top",
		)
		assertEquals("Three", rows.first().payload["to"])
	}

	@Test
	fun `another ticket's log is not this ticket's log`() {
		val mine = ticket("Mine")
		val theirs = ticket("Theirs")
		tickets.patch(actor, theirs.id, TicketPatch(status = TicketStatus.DONE))

		assertEquals(listOf(ActivityKind.CREATED), log(mine.id).map { it.kind })
	}
}

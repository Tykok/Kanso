package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class DependencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var repo: TicketRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "deps-${UUID.randomUUID()}@kanso.test",
			displayName = "Deps admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Deps", "D${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, start: Int? = null, due: Int? = null): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = start?.let(::day),
		due = due?.let(::day),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	@Test
	fun `a chain that would close on itself is refused`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a, b)
		deps.insert(b, c)

		// Asked the way `link` asks it: is the would-be predecessor already reachable
		// forward from the would-be successor?
		assertNotNull(deps.pathBetween(a, c), "C -> A would close A -> B -> C")
		assertNull(deps.pathBetween(c, a), "A -> C is a shortcut, not a cycle")
	}

	@Test
	fun `the offending chain is named, not merely detected`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a, b)
		deps.insert(b, c)

		assertEquals(
			listOf(a, b, c),
			deps.pathBetween(a, c),
			"a bare 'cycle detected' on a forty-ticket graph is unusable",
		)
	}

	@Test
	fun `the component closure crosses the direction of the arrows`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		val loner = ticket("Loner")
		deps.insert(a, b)
		deps.insert(c, b)

		assertEquals(
			setOf(a, b, c),
			deps.componentIds(listOf(a)).toSet(),
			"reaching C from A means walking one arrow backwards",
		)
		assertEquals(setOf(loner), deps.componentIds(listOf(loner)).toSet())
	}

	@Test
	fun `linking two tickets refuses a cycle and names the chain`() {
		val a = ticket("A")
		val b = ticket("B")
		schedule.link(admin, a, b)

		val failure = assertFailsWith<ConflictException> { schedule.link(admin, b, a) }
		assertTrue(failure.message!!.contains(a.toString()), failure.message!!)
		assertTrue(failure.message!!.contains(b.toString()), failure.message!!)
	}

	@Test
	fun `linking applies the cascade immediately`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 5, due = 8)

		val moved = schedule.link(admin, a, b)

		assertEquals(listOf(b), moved, "the new constraint is violated the moment it exists")
	}

	@Test
	fun `unlinking frees slack without dragging anything backwards`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 10, due = 15)
		schedule.link(admin, a, b)

		schedule.unlink(admin, a, b)

		assertEquals(day(10).at, repo.findById(b)!!.start!!.at, "B stays where it is")
		assertFalse(deps.exists(a, b))
	}
}

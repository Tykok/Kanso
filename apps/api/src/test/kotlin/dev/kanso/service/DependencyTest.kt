package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class DependencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var deps: DependencyRepository
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

	private fun ticket(title: String): UUID = tickets.create(
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
	).ticket.id

	@Test
	fun `a chain that would close on itself is refused`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a, b)
		deps.insert(b, c)

		assertTrue(deps.wouldCreateCycle(c, a), "C -> A closes A -> B -> C")
		assertFalse(deps.wouldCreateCycle(a, c), "A -> C is a shortcut, not a cycle")
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
}

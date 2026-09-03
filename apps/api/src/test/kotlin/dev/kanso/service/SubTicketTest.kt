package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One level of parenthood, the refusals that keep it one level, and the number a parent
 * derives from its children.
 *
 * The refusals are the interesting half. Two of them exist to cap the nest, and the
 * third thing they do — for free, and this is what the file is really pinning — is make
 * a cycle unbuildable rather than merely refused: every loop needs a second half that one
 * of the two rules already declines.
 */
@Transactional
class SubTicketTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var subTickets: SubTicketService
	@Autowired lateinit var repo: TicketRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "sub-${UUID.randomUUID()}@kanso.test",
			displayName = "Sub admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Subs", "S${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(
		title: String,
		status: TicketStatus = TicketStatus.TODO,
		estimate: Int? = null,
	): UUID {
		val id = tickets.create(
			actor = admin,
			teamId = team.id,
			title = title,
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
		if (estimate != null) tickets.patch(admin, id, TicketPatch(estimate = estimate))
		return id
	}

	// --- the refusals --------------------------------------------------------

	@Test
	fun `a ticket cannot be its own parent`() {
		val a = ticket("A")

		assertEquals("A ticket cannot be its own parent", subTickets.parentRefusal(a, a))
		val failure = assertFailsWith<ConflictException> { subTickets.setParent(admin, a, a) }
		assertEquals("A ticket cannot be its own parent", failure.message)
		assertNull(repo.findById(a)!!.parentId, "and nothing was written")
	}

	@Test
	fun `a ticket cannot become its own ancestor`() {
		val parent = ticket("Parent")
		val child = ticket("Child")
		subTickets.setParent(admin, child, parent)

		// The loop `parent -> child -> parent`. Both halves of it are refused, by two
		// different rules, which is why there is no ancestor walk in the service: the
		// child already has a parent, and the parent already has a child.
		assertNotNull(subTickets.parentRefusal(parent, child))
		assertFailsWith<ConflictException> { subTickets.setParent(admin, parent, child) }
		assertNull(repo.findById(parent)!!.parentId, "the loop did not close")
		assertEquals(parent, repo.findById(child)!!.parentId, "and the real edge survived")
	}

	@Test
	fun `a sub-ticket cannot take sub-tickets of its own`() {
		val parent = ticket("Parent")
		val child = ticket("Child")
		val third = ticket("Third")
		subTickets.setParent(admin, child, parent)

		val why = subTickets.parentRefusal(third, child)
		assertNotNull(why)
		assertTrue(why.contains("do not nest"), why)
		assertNull(repo.findById(third)!!.parentId)
	}

	@Test
	fun `a parent cannot be hung under somebody else`() {
		val parent = ticket("Parent")
		val child = ticket("Child")
		val other = ticket("Other")
		subTickets.setParent(admin, child, parent)

		// The mirror of the rule above, from the other end. Without both, one of them
		// would let a third level in by approaching it from the other side.
		val why = subTickets.parentRefusal(parent, other)
		assertNotNull(why)
		assertTrue(why.contains("sub-tickets of its own"), why)
	}

	@Test
	fun `hanging a ticket under a parent that does not exist is refused`() {
		val a = ticket("A")
		val ghost = UUID.randomUUID()

		assertEquals("No ticket $ghost", subTickets.parentRefusal(a, ghost))
	}

	@Test
	fun `a legitimate parenting is allowed, and can be undone`() {
		val parent = ticket("Parent")
		val child = ticket("Child")

		assertNull(subTickets.parentRefusal(child, parent))
		subTickets.setParent(admin, child, parent)
		assertEquals(parent, repo.findById(child)!!.parentId)

		subTickets.setParent(admin, child, null)
		assertNull(repo.findById(child)!!.parentId, "promotion to top level is the same gesture")
	}

	@Test
	fun `deleting a parent promotes its children instead of destroying them`() {
		val parent = ticket("Parent")
		val first = ticket("First")
		val second = ticket("Second")
		subTickets.setParent(admin, first, parent)
		subTickets.setParent(admin, second, parent)

		repo.delete(parent)

		// `ON DELETE SET NULL`, spelled out: the rows are still there, they are simply
		// top-level again. `CASCADE` would have taken two people's work with the heading.
		assertNotNull(repo.findById(first), "the child outlived its parent")
		assertNull(repo.findById(first)!!.parentId)
		assertNotNull(repo.findById(second))
		assertNull(repo.findById(second)!!.parentId)
	}

	// --- the derived number --------------------------------------------------

	@Test
	fun `a ticket with no children has no progress at all`() {
		val lonely = ticket("Lonely")

		assertEquals(
			emptyMap(),
			subTickets.progress(listOf(lonely)),
			"absent, not zeroed: '0 of 0 done' is a number about nothing",
		)
	}

	@Test
	fun `progress counts the children that are done`() {
		val parent = ticket("Parent")
		subTickets.setParent(admin, ticket("One", TicketStatus.DONE), parent)
		subTickets.setParent(admin, ticket("Two", TicketStatus.DONE), parent)
		subTickets.setParent(admin, ticket("Three", TicketStatus.IN_PROGRESS), parent)

		val progress = subTickets.progress(listOf(parent))[parent]
		assertNotNull(progress)
		assertEquals(3, progress.total)
		assertEquals(2, progress.done)
	}

	@Test
	fun `a cancelled child is in neither half of the fraction`() {
		val parent = ticket("Parent")
		subTickets.setParent(admin, ticket("Done", TicketStatus.DONE), parent)
		subTickets.setParent(admin, ticket("Dropped", TicketStatus.CANCELED), parent)

		val progress = subTickets.progress(listOf(parent))[parent]
		assertNotNull(progress)
		// "1 of 1", not "1 of 2" and not "2 of 2". Counting it as outstanding would make
		// this parent unfinishable forever; counting it as done would claim work nobody did.
		assertEquals(1, progress.total)
		assertEquals(1, progress.done)
	}

	@Test
	fun `a parent whose children were all cancelled has no progress either`() {
		val parent = ticket("Parent")
		subTickets.setParent(admin, ticket("Dropped", TicketStatus.CANCELED), parent)

		assertEquals(
			emptyMap(),
			subTickets.progress(listOf(parent)),
			"every child discounted leaves nothing to draw a fraction from",
		)
	}

	@Test
	fun `points are counted only when every child is estimated`() {
		val parent = ticket("Parent")
		subTickets.setParent(admin, ticket("Sized", TicketStatus.DONE, estimate = 3), parent)
		subTickets.setParent(admin, ticket("Unsized", TicketStatus.TODO), parent)

		val mixed = subTickets.progress(listOf(parent))[parent]
		assertNotNull(mixed)
		assertEquals(2, mixed.total, "the count still works")
		// An unestimated child weighs zero in a sum, so points here would read "3 of 3" —
		// a finished parent — while half of it had never been sized.
		assertNull(mixed.donePoints)
		assertNull(mixed.totalPoints)
	}

	@Test
	fun `points are counted when every child is estimated`() {
		val parent = ticket("Parent")
		subTickets.setParent(admin, ticket("One", TicketStatus.DONE, estimate = 3), parent)
		subTickets.setParent(admin, ticket("Two", TicketStatus.TODO, estimate = 5), parent)

		val progress = subTickets.progress(listOf(parent))[parent]
		assertNotNull(progress)
		assertEquals(3, progress.donePoints)
		assertEquals(8, progress.totalPoints)
	}

	@Test
	fun `progress for many parents is one answer per parent`() {
		val first = ticket("First")
		val second = ticket("Second")
		val childless = ticket("Childless")
		subTickets.setParent(admin, ticket("A", TicketStatus.DONE), first)
		subTickets.setParent(admin, ticket("B", TicketStatus.TODO), second)

		val progress = subTickets.progress(listOf(first, second, childless))

		assertEquals(setOf(first, second), progress.keys, "the childless one is absent")
		assertEquals(1, progress.getValue(first).done)
		assertEquals(0, progress.getValue(second).done)
	}
}

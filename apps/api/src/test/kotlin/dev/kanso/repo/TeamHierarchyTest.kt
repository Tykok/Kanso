package dev.kanso.repo

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class TeamHierarchyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository

	private var keyCounter = 0
	private fun uniqueKey() = "T${keyCounter++}${UUID.randomUUID().toString().take(3).uppercase()}"

	@Test
	fun `descendants walks the whole tree, not just the first level`() {
		val root = teams.insert("Root", uniqueKey(), null)
		val child = teams.insert("Child", uniqueKey(), root.id)
		val grandchild = teams.insert("Grandchild", uniqueKey(), child.id)
		val sibling = teams.insert("Sibling", uniqueKey(), root.id)
		val unrelated = teams.insert("Unrelated", uniqueKey(), null)

		val found = teams.descendantIds(root.id).toSet()

		assertEquals(setOf(root.id, child.id, grandchild.id, sibling.id), found)
		assertFalse(unrelated.id in found, "a team outside the subtree must not appear")
	}

	@Test
	fun `a team with no children resolves to itself`() {
		val leaf = teams.insert("Leaf", uniqueKey(), null)
		assertEquals(listOf(leaf.id), teams.descendantIds(leaf.id))
	}

	@Test
	fun `re-parenting a team under its own descendant is refused`() {
		val root = teams.insert("Root", uniqueKey(), null)
		val child = teams.insert("Child", uniqueKey(), root.id)
		val grandchild = teams.insert("Grandchild", uniqueKey(), child.id)

		assertTrue(teams.wouldCreateCycle(root.id, grandchild.id), "root under its grandchild is a cycle")
		assertTrue(teams.wouldCreateCycle(root.id, root.id), "a team cannot be its own parent")
		assertFalse(teams.wouldCreateCycle(grandchild.id, root.id), "moving a leaf upwards is legitimate")
	}

	@Test
	fun `ticket numbers are dense and scoped to their team`() {
		val first = teams.insert("First", uniqueKey(), null)
		val second = teams.insert("Second", uniqueKey(), null)

		assertEquals(listOf(1, 2, 3), (1..3).map { teams.nextTicketNumber(first.id) })
		assertEquals(1, teams.nextTicketNumber(second.id), "each team numbers from one")
	}
}

/**
 * Not `@Transactional`: the allocation is only serialised by a real row lock, so
 * the threads need real committed transactions to contend for it.
 */
class TicketNumberConcurrencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var tx: TransactionTemplate

	@Test
	fun `concurrent allocations produce no gaps and no duplicates`() {
		val team = tx.execute { teams.insert("Concurrent", "CON${UUID.randomUUID().toString().take(4).uppercase()}", null) }!!
		val threads = 8
		val perThread = 10

		val pool = Executors.newFixedThreadPool(threads)
		try {
			val tasks = List(threads) {
				Callable { List(perThread) { tx.execute { teams.nextTicketNumber(team.id) }!! } }
			}
			val allocated = pool.invokeAll(tasks).flatMap { it.get(30, TimeUnit.SECONDS) }

			assertEquals(threads * perThread, allocated.size)
			assertEquals(
				(1..threads * perThread).toSet(),
				allocated.toSet(),
				"every number from 1..N should be handed out exactly once",
			)
		} finally {
			pool.shutdownNow()
			tx.executeWithoutResult { teams.delete(team.id) }
		}
	}
}

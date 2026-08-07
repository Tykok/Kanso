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
import kotlin.test.assertFailsWith

@Transactional
class TicketNumberBlockTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository

	private fun uniqueKey() = "B${UUID.randomUUID().toString().take(5).uppercase()}"

	@Test
	fun `a block is consecutive and continues from the counter`() {
		val team = teams.insert("Block", uniqueKey(), null)

		assertEquals(listOf(1, 2, 3), teams.nextTicketNumbers(team.id, 3))
		assertEquals(4, teams.nextTicketNumber(team.id), "a block leaves the counter where it stopped")
		assertEquals(listOf(5, 6), teams.nextTicketNumbers(team.id, 2))
		assertEquals(6, requireNotNull(teams.findById(team.id)).ticketCounter)
	}

	@Test
	fun `a block of zero reserves nothing`() {
		val team = teams.insert("Empty block", uniqueKey(), null)

		assertEquals(emptyList<Int>(), teams.nextTicketNumbers(team.id, 0))
		assertEquals(0, requireNotNull(teams.findById(team.id)).ticketCounter)
	}

	@Test
	fun `a negative block is a programming error, not a silent no-op`() {
		val team = teams.insert("Negative block", uniqueKey(), null)
		assertFailsWith<IllegalArgumentException> { teams.nextTicketNumbers(team.id, -1) }
	}
}

/**
 * Not `@Transactional`: the allocation is only serialised by a real row lock, so the
 * threads need real committed transactions to contend for it.
 */
class TicketNumberBlockConcurrencyTest : PostgresTest() {

	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var tx: TransactionTemplate

	@Test
	fun `blocks stay contiguous while other threads allocate`() {
		val team = tx.execute {
			teams.insert("Blocks", "BLK${UUID.randomUUID().toString().take(4).uppercase()}", null)
		}!!
		val threads = 8
		val blocksPerThread = 5
		val blockSize = 4
		val expected = threads * blocksPerThread * blockSize

		val pool = Executors.newFixedThreadPool(threads)
		try {
			// Half the threads take blocks, half take one number at a time — the two
			// paths share the counter and must not tread on each other.
			val tasks = List(threads) { index ->
				Callable {
					if (index % 2 == 0) {
						List(blocksPerThread) { tx.execute { teams.nextTicketNumbers(team.id, blockSize) }!! }
					} else {
						List(blocksPerThread * blockSize) { listOf(tx.execute { teams.nextTicketNumber(team.id) }!!) }
					}
				}
			}
			val blocks = pool.invokeAll(tasks).flatMap { it.get(30, TimeUnit.SECONDS) }
			val allocated = blocks.flatten()

			assertEquals(expected, allocated.size)
			assertEquals(
				(1..expected).toSet(),
				allocated.toSet(),
				"every number from 1..N should be handed out exactly once",
			)
			blocks.forEach { block ->
				assertEquals(
					(block.first() until block.first() + block.size).toList(),
					block,
					"a block must be contiguous: no other thread may take a number out of its middle",
				)
			}
		} finally {
			pool.shutdownNow()
			tx.executeWithoutResult { teams.delete(team.id) }
		}
	}
}

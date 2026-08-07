package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class SyncJobQueueTest : PostgresTest() {

	@Autowired lateinit var jobs: SyncJobRepository

	@Test
	fun `repeated edits to one row collapse into a single queued job`() {
		val id = UUID.randomUUID()
		repeat(5) { jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT) }

		val claimed = jobs.claimBatch(10, "test-worker")
		assertEquals(1, claimed.size, "a push writes the whole row, so five queued pushes are one")
	}

	@Test
	fun `the latest operation wins when a job coalesces`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.ARCHIVE)

		val claimed = jobs.claimBatch(10, "test-worker").single()
		assertEquals(SyncOperation.ARCHIVE, claimed.operation, "archiving after editing must not be undone")
	}

	@Test
	fun `claiming frees the slot so an edit during a push is not lost`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		jobs.claimBatch(10, "test-worker")

		// Someone edits the ticket while the push is in flight.
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)

		assertEquals(1, jobs.claimBatch(10, "test-worker").size, "the later edit must still be queued")
	}

	@Test
	fun `dependency order puts teams before projects before tickets`() {
		jobs.enqueue(SyncEntityType.TICKET, UUID.randomUUID(), SyncOperation.UPSERT)
		jobs.enqueue(SyncEntityType.PROJECT, UUID.randomUUID(), SyncOperation.UPSERT)
		jobs.enqueue(SyncEntityType.TEAM, UUID.randomUUID(), SyncOperation.UPSERT)

		val order = jobs.claimBatch(10, "test-worker").map { it.entityType }
		assertEquals(
			listOf(SyncEntityType.TEAM, SyncEntityType.PROJECT, SyncEntityType.TICKET),
			order,
			"a relation target has to reach Notion before whatever points at it",
		)
	}

	@Test
	fun `a claim increments attempts, and a retry counts against the budget`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)

		val first = jobs.claimBatch(1, "test-worker").single()
		assertEquals(1, first.attempts)

		jobs.scheduleRetry(first, "boom", Duration.ZERO)
		val second = jobs.claimBatch(1, "test-worker").single()
		assertEquals(2, second.attempts, "a genuine failure should move towards giving up")
	}

	@Test
	fun `deferring does not spend an attempt`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)

		val first = jobs.claimBatch(1, "test-worker").single()
		jobs.defer(first, "dependency not ready", Duration.ZERO)

		val second = jobs.claimBatch(1, "test-worker").single()
		assertEquals(
			1,
			second.attempts,
			"waiting on a queued dependency is not a failure and must not exhaust max-attempts",
		)
	}

	@Test
	fun `a retry is dropped when a newer job already carries the row's state`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		val running = jobs.claimBatch(1, "test-worker").single()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT) // edited mid-push

		jobs.scheduleRetry(running, "boom", Duration.ZERO)

		val remaining = jobs.claimBatch(10, "test-worker")
		assertEquals(
			1,
			remaining.size,
			"the stale retry is redundant: the newer job already pushes the current state",
		)
	}

	@Test
	fun `a failed job is reportable and can be requeued`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		val claimed = jobs.claimBatch(1, "test-worker").single()
		jobs.markFailed(claimed.id, "Notion said no")

		val failed = jobs.findFailed().singleOrNull { it.id == claimed.id }
		assertNotNull(failed, "a failure has to be visible without reading logs")
		assertEquals("Notion said no", failed.lastError)

		assertEquals(1, jobs.retryAllFailed())
		assertTrue(jobs.claimBatch(10, "test-worker").any { it.id == claimed.id })
	}

	@Test
	fun `a job abandoned by a dead worker returns to the queue`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.UPSERT)
		jobs.claimBatch(1, "dead-worker")

		assertTrue(jobs.claimBatch(10, "other").isEmpty(), "a running job is not up for grabs yet")

		// Zero timeout: anything currently running counts as abandoned.
		assertEquals(1, jobs.reclaimStuck(Duration.ZERO))
		assertEquals(1, jobs.claimBatch(10, "other").size)
	}

	@Test
	fun `a delete carries the page id because the row will be gone`() {
		val id = UUID.randomUUID()
		jobs.enqueue(SyncEntityType.TICKET, id, SyncOperation.DELETE, payload = """{"notionPageId":"abc-123"}""")

		val claimed = jobs.claimBatch(1, "test-worker").single()
		assertEquals(SyncOperation.DELETE, claimed.operation)
		assertTrue(claimed.payload?.contains("abc-123") == true, "payload was ${claimed.payload}")
	}

	@Test
	fun `an empty queue claims nothing`() {
		assertEquals(emptyList(), jobs.claimBatch(10, "test-worker"))
		assertNull(jobs.countsByStatus()["nonsense"])
	}
}

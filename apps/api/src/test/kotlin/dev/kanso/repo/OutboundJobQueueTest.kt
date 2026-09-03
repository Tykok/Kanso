package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class OutboundJobQueueTest : PostgresTest() {

	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var jdbc: JdbcClient

	@Test
	fun `repeated edits to one row collapse into a single queued job`() {
		val id = UUID.randomUUID()
		repeat(5) { jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT) }

		val claimed = jobs.claimBatch(Destination.NOTION, 10, "test-worker")
		assertEquals(1, claimed.size, "a push writes the whole row, so five queued pushes are one")
	}

	@Test
	fun `the latest operation wins when a job coalesces`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.ARCHIVE)

		val claimed = jobs.claimBatch(Destination.NOTION, 10, "test-worker").single()
		assertEquals(OutboundOperation.ARCHIVE, claimed.operation, "archiving after editing must not be undone")
	}

	@Test
	fun `claiming frees the slot so an edit during a push is not lost`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		jobs.claimBatch(Destination.NOTION, 10, "test-worker")

		// Someone edits the ticket while the push is in flight.
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		assertEquals(1, jobs.claimBatch(Destination.NOTION, 10, "test-worker").size, "the later edit must still be queued")
	}

	/**
	 * What is queued for these entities, in the order the ready queue is walked in.
	 *
	 * Read, never claimed — the shape `NotionInboundTeamTest` arrived at for the same
	 * family of flake, one screen along. `claimBatch` is a batch-limited
	 * `FOR UPDATE SKIP LOCKED` across a whole destination, so what it hands back is a fact
	 * about the entire table: three rows fall outside a batch of ten the moment somebody
	 * else has left eight queued, and `outbound_jobs` is shared with every class in the
	 * run — `ImportCommitTest` is not `@Transactional` and commits into it. An assertion
	 * built on a claim therefore answers "what is in the queue", which depends on
	 * execution order, when the question it means to ask is "which of *these three* comes
	 * first". Filtering on the entities under test asks that one, and a filter has no
	 * business taking a lock or spending an attempt to get an answer.
	 *
	 * The `ORDER BY` is `claimBatch`'s, spelled out rather than borrowed, and reading the
	 * ordering key straight off the rows is the point: what encodes the dependency order
	 * is `OutboundEntityType.priority`, `enqueue` is what writes it, and this is where a
	 * change to either becomes visible.
	 */
	private fun queuedOrder(entityIds: List<UUID>): List<OutboundEntityType> = jdbc.sql(
		"""
		SELECT entity_type FROM outbound_jobs
		 WHERE destination = :destination AND status = 'pending' AND next_attempt_at <= now()
		   AND entity_id IN (:ids)
		 ORDER BY priority, next_attempt_at, id
		""".trimIndent()
	)
		.param("destination", Destination.NOTION.wire)
		.param("ids", entityIds)
		.query { rs, _ -> OutboundEntityType.from(rs.getString("entity_type")) }
		.list()

	@Test
	fun `dependency order puts teams before projects before tickets`() {
		val ticket = UUID.randomUUID()
		val project = UUID.randomUUID()
		val team = UUID.randomUUID()
		val mine = listOf(ticket, project, team)

		// `enqueue` coalesces onto an existing pending row, so a leftover job for one of
		// these ids would be edited rather than added and the miss would be silent. Fresh
		// UUIDs mean this deletes nothing in practice; it is here so that "these three
		// rows and no others" holds by construction rather than by luck.
		jdbc.sql("DELETE FROM outbound_jobs WHERE entity_id IN (:ids)").param("ids", mine).update()

		// Queued in the reverse of the expected order, so passing cannot be insertion
		// order wearing a priority's clothes: `id` is the last tiebreaker, and on it alone
		// these three come back ticket-first.
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, ticket, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.PROJECT, project, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TEAM, team, OutboundOperation.UPSERT)

		assertEquals(
			listOf(OutboundEntityType.TEAM, OutboundEntityType.PROJECT, OutboundEntityType.TICKET),
			queuedOrder(mine),
			"a relation target has to reach Notion before whatever points at it",
		)
	}

	@Test
	fun `a claim increments attempts, and a retry counts against the budget`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		val first = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		assertEquals(1, first.attempts)

		jobs.scheduleRetry(first, "boom", Duration.ZERO)
		val second = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		assertEquals(2, second.attempts, "a genuine failure should move towards giving up")
	}

	@Test
	fun `deferring does not spend an attempt`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		val first = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		jobs.defer(first, "dependency not ready", Duration.ZERO)

		val second = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		assertEquals(
			1,
			second.attempts,
			"waiting on a queued dependency is not a failure and must not exhaust max-attempts",
		)
	}

	@Test
	fun `a retry is dropped when a newer job already carries the row's state`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT) // edited mid-push

		jobs.scheduleRetry(running, "boom", Duration.ZERO)

		val remaining = jobs.claimBatch(Destination.NOTION, 10, "test-worker")
		assertEquals(
			1,
			remaining.size,
			"the stale retry is redundant: the newer job already pushes the current state",
		)
	}

	@Test
	fun `a failed job is reportable and can be requeued`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val claimed = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		jobs.markFailed(claimed.id, "Notion said no")

		val failed = jobs.findFailed(Destination.NOTION).singleOrNull { it.id == claimed.id }
		assertNotNull(failed, "a failure has to be visible without reading logs")
		assertEquals("Notion said no", failed.lastError)

		assertEquals(1, jobs.retryAllFailed(Destination.NOTION))
		assertTrue(jobs.claimBatch(Destination.NOTION, 10, "test-worker").any { it.id == claimed.id })
	}

	@Test
	fun `a job abandoned by a dead worker returns to the queue`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		jobs.claimBatch(Destination.NOTION, 1, "dead-worker")

		assertTrue(jobs.claimBatch(Destination.NOTION, 10, "other").isEmpty(), "a running job is not up for grabs yet")

		// Zero timeout: anything currently running counts as abandoned.
		assertEquals(1, jobs.reclaimStuck(Duration.ZERO))
		assertEquals(1, jobs.claimBatch(Destination.NOTION, 10, "other").size)
	}

	// --- a slow worker is not a dead one --------------------------------------
	//
	// The sweep used to reclaim on the age of the lock, and a push that is merely slow
	// answers "how long have you held this" exactly like a process that died holding it.
	// These two pin the distinction from both sides: same sweep, same timeout, and the
	// only difference between them is whether the holder said anything.

	/**
	 * Ages a running job's two timestamps independently, because telling them apart is
	 * the entire subject: [lockHeld] is how long the push has been running, and
	 * [unheardFrom] is how long since whoever holds it last proved it was alive.
	 */
	private fun backdate(jobId: Long, lockHeld: Duration, unheardFrom: Duration) {
		jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET locked_at    = clock_timestamp() - make_interval(secs => :held),
			       heartbeat_at = clock_timestamp() - make_interval(secs => :silent)
			 WHERE id = :id
			""".trimIndent()
		)
			.param("held", lockHeld.seconds.toDouble())
			.param("silent", unheardFrom.seconds.toDouble())
			.param("id", jobId)
			.update()
	}

	@Test
	fun `a push whose worker is still beating keeps its lock however long it runs`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = jobs.claimBatch(Destination.NOTION, 1, "slow-worker").single()

		// An hour into the push, twelve times over the timeout the sweep is about to apply.
		backdate(running.id, lockHeld = Duration.ofHours(1), unheardFrom = Duration.ofHours(1))
		// And the worker holding it is alive, and says so.
		jobs.heartbeat(listOf(running.id))

		jobs.reclaimStuck(Duration.ofMinutes(5))

		// The row, not the count `reclaimStuck` returns. That count is global — the sweep
		// takes no destination, by design — so asserting a number on it would be the same
		// mistake `queuedOrder` exists to avoid, one method along.
		assertEquals(
			"running",
			statusOf(running.id),
			"reclaiming a push still in flight sends the same operation a second time",
		)
	}

	@Test
	fun `a job whose heartbeat stopped is reclaimed however recently it was claimed`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val abandoned = jobs.claimBatch(Destination.NOTION, 1, "dead-worker").single()

		// The mirror image: the lock was taken moments ago, and nothing has been heard
		// since. This is the half that proves the sweep reads the heartbeat rather than
		// the lock — on the age of the lock, this job is not stuck at all.
		backdate(abandoned.id, lockHeld = Duration.ZERO, unheardFrom = Duration.ofMinutes(10))

		jobs.reclaimStuck(Duration.ofMinutes(5))

		assertEquals("pending", statusOf(abandoned.id), "a dead worker's work still has to come back")
	}

	@Test
	fun `a delete carries the page id because the row will be gone`() {
		val id = UUID.randomUUID()
		jobs.enqueue(
			Destination.NOTION,
			OutboundEntityType.TICKET,
			id,
			OutboundOperation.DELETE,
			payload = """{"notionPageId":"abc-123"}""",
		)

		val claimed = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		assertEquals(OutboundOperation.DELETE, claimed.operation)
		assertTrue(claimed.payload?.contains("abc-123") == true, "payload was ${claimed.payload}")
	}

	@Test
	fun `an empty queue claims nothing`() {
		assertEquals(emptyList(), jobs.claimBatch(Destination.NOTION, 10, "test-worker"))
		assertNull(jobs.countsByStatus(Destination.NOTION)["nonsense"])
	}

	// --- the second destination -----------------------------------------------
	//
	// Every rule above collapses, drops or supersedes a job on the strength of another
	// job for "the same thing". Once the outbox has two consumers, each of those has to
	// mean "the same thing, going to the same place" — and the day it does not, one of
	// the two changes silently never leaves. That has to be pinned before the second
	// consumer exists, or it gets written wrong and nobody finds out until they collide.

	/**
	 * A job for a destination this build has no handler for.
	 *
	 * The second consumer is simulated at the row level rather than invented in
	 * [Destination]: a fake value in a production vocabulary would be a worse lie than
	 * this one. The `CHECK` is dropped only for the length of this transaction —
	 * Postgres rolls DDL back like anything else — and the row still goes in through
	 * the real unique index, so what is being tested is the index and the repository's
	 * own SQL, not a mock of them.
	 */
	private fun enqueueElsewhere(entityId: UUID, status: String = "pending"): Long {
		jdbc.sql("ALTER TABLE outbound_jobs DROP CONSTRAINT IF EXISTS outbound_jobs_destination_chk").update()
		return jdbc.sql(
			"""
			INSERT INTO outbound_jobs (destination, entity_type, entity_id, operation, status, priority, next_attempt_at)
			VALUES ('elsewhere', 'ticket', :id, 'upsert', :status, 100, now())
			RETURNING id
			""".trimIndent()
		).param("id", entityId).param("status", status).query { rs, _ -> rs.getLong("id") }.single()
	}

	private fun statusOf(jobId: Long): String = jdbc.sql(
		"SELECT status FROM outbound_jobs WHERE id = :id"
	).param("id", jobId).query { rs, _ -> rs.getString("status") }.single()

	@Test
	fun `two destinations each keep their own pending job for the same row`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val elsewhere = enqueueElsewhere(id)

		val claimed = jobs.claimBatch(Destination.NOTION, 10, "test-worker")
		assertEquals(1, claimed.size, "one destination's claim is not the other's")
		assertEquals(Destination.NOTION, claimed.single().destination)
		assertEquals(
			"pending",
			statusOf(elsewhere),
			"collapsing across destinations would mean one of the two pushes never happens",
		)
	}

	@Test
	fun `a retry survives another destination holding the same row`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		enqueueElsewhere(id)

		jobs.scheduleRetry(running, "boom", Duration.ZERO)

		assertTrue(
			jobs.claimBatch(Destination.NOTION, 10, "test-worker").any { it.id == running.id },
			"the other destination's job carries none of this one's state and supersedes nothing",
		)
	}

	@Test
	fun `a dead worker's job survives another destination holding the same row`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val abandoned = jobs.claimBatch(Destination.NOTION, 1, "dead-worker").single()
		enqueueElsewhere(id)

		assertEquals(1, jobs.reclaimStuck(Duration.ZERO))
		assertEquals("pending", statusOf(abandoned.id), "the sweep must not read it as superseded")
	}

	@Test
	fun `retrying failures is one destination's own business`() {
		val id = UUID.randomUUID()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val mine = jobs.claimBatch(Destination.NOTION, 1, "test-worker").single()
		jobs.markFailed(mine.id, "Notion said no")
		val theirs = enqueueElsewhere(UUID.randomUUID(), status = "failed")

		assertEquals(1, jobs.retryAllFailed(Destination.NOTION))
		assertEquals("failed", statusOf(theirs), "a button on the mirror's screen retries the mirror")
		assertTrue(jobs.findFailed(Destination.NOTION).none { it.id == theirs })
		assertNull(jobs.countsByStatus(Destination.NOTION)["failed"], "and does not count somebody else's")
	}

	/**
	 * The rule lives in an index, so the index is what is asserted.
	 *
	 * Column order and all: `destination` has to lead, and the predicate has to stay
	 * `status = 'pending'`. Dropping either would still pass every behavioural test
	 * above the day somebody rewrites this index without the first column.
	 */
	@Test
	fun `the collapse rule is keyed on the destination`() {
		val definition = jdbc.sql(
			"SELECT indexdef FROM pg_indexes WHERE indexname = 'outbound_jobs_pending_uniq'"
		).query { rs, _ -> rs.getString("indexdef") }.single()

		assertTrue(
			definition.contains("(destination, entity_type, entity_id)"),
			"one pending job per (destination, thing), not per thing — was: $definition",
		)
		assertTrue(definition.startsWith("CREATE UNIQUE INDEX"), definition)
		assertTrue(definition.contains("WHERE (status = 'pending'::text)"), definition)
	}
}

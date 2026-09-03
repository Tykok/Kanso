package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundJob
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

	/**
	 * An entity id with nothing queued for it.
	 *
	 * The delete is not housekeeping. `enqueue` coalesces `ON CONFLICT DO UPDATE`, so a
	 * pending row already sitting on this id would be *edited* rather than added: a job
	 * queued wrongly would be indistinguishable from the one that was meant to be there,
	 * and every count below would still read one. A random UUID means this deletes nothing
	 * in practice — it is here so that "these rows and no others" holds by construction
	 * rather than by luck.
	 */
	private fun freshEntity(): UUID {
		val id = UUID.randomUUID()
		jdbc.sql("DELETE FROM outbound_jobs WHERE entity_id = :id").param("id", id).update()
		return id
	}

	/** What is queued for one entity — read, never claimed; see [claimedFor]. */
	private fun queuedFor(entityId: UUID): List<OutboundOperation> = jdbc.sql(
		"""
		SELECT operation FROM outbound_jobs
		 WHERE destination = :destination AND entity_id = :id AND status = 'pending'
		""".trimIndent()
	)
		.param("destination", Destination.NOTION.wire)
		.param("id", entityId)
		.query { rs, _ -> OutboundOperation.from(rs.getString("operation")) }
		.list()

	/**
	 * Claims for real, and hands back only what was claimed for [entityId].
	 *
	 * `claimBatch` answers "what is pending for this destination", under a batch limit,
	 * across the whole table. Every assertion in this file means "what happened to the row
	 * I queued", and the two questions have the same answer only while nothing else has a
	 * job queued — a neighbouring test, an unclosed transaction, a committed row.
	 * `outbound_jobs` is shared with every class in the run, so the day the answers
	 * diverge these tests go false without the code under test having moved. Filtering the
	 * batch is what makes them say what they mean; [freshEntity] is the other half.
	 *
	 * Reader-side hardening, and deliberately not a diagnosis: the 33-minute suite behind
	 * KAN-60 is still unexplained, and nothing here explains it. What this buys is only
	 * that these assertions stop depending on what the rest of the run left behind.
	 *
	 * The limit is no longer part of what any test says — a claim of 1 used to mean "the
	 * row I queued" and a claim of 10 "all of them", each true only of an empty table — so
	 * all it has left to do is not truncate this row away behind somebody else's backlog.
	 * Not unbounded either: a claim takes `FOR UPDATE SKIP LOCKED` on every row it returns,
	 * and locking the table to ask about one row would be the same mistake from the other
	 * side.
	 */
	private fun claimedFor(entityId: UUID, worker: String = "test-worker"): List<OutboundJob> =
		jobs.claimBatch(Destination.NOTION, 200, worker).filter { it.entityId == entityId }

	@Test
	fun `repeated edits to one row collapse into a single queued job`() {
		val id = freshEntity()
		repeat(5) { jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT) }

		assertEquals(1, queuedFor(id).size, "a push writes the whole row, so five queued pushes are one")
	}

	@Test
	fun `the latest operation wins when a job coalesces`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.ARCHIVE)

		assertEquals(
			listOf(OutboundOperation.ARCHIVE),
			queuedFor(id),
			"archiving after editing must not be undone",
		)
	}

	@Test
	fun `claiming frees the slot so an edit during a push is not lost`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		assertEquals(1, claimedFor(id).size, "the push this test is about has to actually be in flight")

		// Someone edits the ticket while the push is in flight.
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		assertEquals(listOf(OutboundOperation.UPSERT), queuedFor(id), "the later edit must still be queued")
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
		val ticket = freshEntity()
		val project = freshEntity()
		val team = freshEntity()

		// Queued in the reverse of the expected order, so passing cannot be insertion
		// order wearing a priority's clothes: `id` is the last tiebreaker, and on it alone
		// these three come back ticket-first.
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, ticket, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.PROJECT, project, OutboundOperation.UPSERT)
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TEAM, team, OutboundOperation.UPSERT)

		assertEquals(
			listOf(OutboundEntityType.TEAM, OutboundEntityType.PROJECT, OutboundEntityType.TICKET),
			queuedOrder(listOf(ticket, project, team)),
			"a relation target has to reach Notion before whatever points at it",
		)
	}

	@Test
	fun `a claim increments attempts, and a retry counts against the budget`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		val first = claimedFor(id).single()
		assertEquals(1, first.attempts)

		jobs.scheduleRetry(first, "boom", Duration.ZERO)
		val second = claimedFor(id).single()
		assertEquals(2, second.attempts, "a genuine failure should move towards giving up")
	}

	@Test
	fun `deferring does not spend an attempt`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		val first = claimedFor(id).single()
		jobs.defer(first, "dependency not ready", Duration.ZERO)

		val second = claimedFor(id).single()
		assertEquals(
			1,
			second.attempts,
			"waiting on a queued dependency is not a failure and must not exhaust max-attempts",
		)
	}

	@Test
	fun `a retry is dropped when a newer job already carries the row's state`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = claimedFor(id).single()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT) // edited mid-push

		jobs.scheduleRetry(running, "boom", Duration.ZERO)

		// The row it dropped, rather than how many rows were left behind in the table.
		assertNull(
			statusOf(running.id),
			"the stale retry is redundant: the newer job already pushes the current state",
		)
		assertEquals(listOf(OutboundOperation.UPSERT), queuedFor(id), "and the newer job is what stays queued")
	}

	@Test
	fun `a failed job is reportable and can be requeued`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val claimed = claimedFor(id).single()
		jobs.markFailed(claimed.id, "Notion said no")

		val failed = jobs.findFailed(Destination.NOTION).singleOrNull { it.id == claimed.id }
		assertNotNull(failed, "a failure has to be visible without reading logs")
		assertEquals("Notion said no", failed.lastError)

		jobs.retryAllFailed(Destination.NOTION)

		// The row, not the count the call returns: that count is every failure the
		// destination had, which is a number about the run rather than about this job.
		assertEquals("pending", statusOf(claimed.id), "the admin screen's retry has to move it")
		assertTrue(
			claimedFor(id).any { it.id == claimed.id },
			"and requeued means claimable again, not merely relabelled",
		)
	}

	@Test
	fun `a job abandoned by a dead worker returns to the queue`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val abandoned = claimedFor(id, worker = "dead-worker").single()

		assertTrue(claimedFor(id, worker = "other").isEmpty(), "a running job is not up for grabs yet")

		// Aged past a real timeout rather than swept with `Duration.ZERO`. Zero declares
		// every 'running' row in the table abandoned, so it makes the sweep a write onto
		// everybody else's jobs and its return value a count of them. Backdating this one
		// row keeps both halves local, and the count stays unasserted for the reason
		// spelled out below `reclaimStuck`.
		backdate(abandoned.id, lockHeld = Duration.ofMinutes(10), unheardFrom = Duration.ofMinutes(10))
		jobs.reclaimStuck(Duration.ofMinutes(5))

		assertEquals("pending", statusOf(abandoned.id), "a dead worker's work still has to come back")
		assertEquals(1, claimedFor(id, worker = "other").size)
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
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = claimedFor(id, worker = "slow-worker").single()

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
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val abandoned = claimedFor(id, worker = "dead-worker").single()

		// The mirror image: the lock was taken moments ago, and nothing has been heard
		// since. This is the half that proves the sweep reads the heartbeat rather than
		// the lock — on the age of the lock, this job is not stuck at all.
		backdate(abandoned.id, lockHeld = Duration.ZERO, unheardFrom = Duration.ofMinutes(10))

		jobs.reclaimStuck(Duration.ofMinutes(5))

		assertEquals("pending", statusOf(abandoned.id), "a dead worker's work still has to come back")
	}

	@Test
	fun `a delete carries the page id because the row will be gone`() {
		val id = freshEntity()
		jobs.enqueue(
			Destination.NOTION,
			OutboundEntityType.TICKET,
			id,
			OutboundOperation.DELETE,
			payload = """{"notionPageId":"abc-123"}""",
		)

		val claimed = claimedFor(id).single()
		assertEquals(OutboundOperation.DELETE, claimed.operation)
		assertTrue(claimed.payload?.contains("abc-123") == true, "payload was ${claimed.payload}")
	}

	@Test
	fun `a claimed job is not handed out a second time`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

		assertEquals(1, claimedFor(id).size)
		// This used to assert that a batch came back *empty*, which is a sentence about the
		// shared table and not about this row: vacuous on a run where somebody else has
		// work queued, and it was never the rule worth pinning anyway. The rule is that
		// claiming flips the row out of 'pending', which is what stops two workers pushing
		// the same state at once.
		assertTrue(claimedFor(id).isEmpty(), "a second claim would send the same push twice")
		assertNull(jobs.countsByStatus(Destination.NOTION)["nonsense"], "no row is in a status with no name")
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

	/** Null when the row is gone, which is a verdict of its own — see `scheduleRetry`. */
	private fun statusOf(jobId: Long): String? = jdbc.sql(
		"SELECT status FROM outbound_jobs WHERE id = :id"
	).param("id", jobId).query { rs, _ -> rs.getString("status") }.optional().orElse(null)

	/** Failed rows for one destination, counted off the table rather than through the code. */
	private fun failedRows(destination: String): Long = jdbc.sql(
		"SELECT count(*) FROM outbound_jobs WHERE destination = :destination AND status = 'failed'"
	).param("destination", destination).query(Long::class.java).single()

	@Test
	fun `two destinations each keep their own pending job for the same row`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val elsewhere = enqueueElsewhere(id)

		val claimed = claimedFor(id)
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
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val running = claimedFor(id).single()
		enqueueElsewhere(id)

		jobs.scheduleRetry(running, "boom", Duration.ZERO)

		assertTrue(
			claimedFor(id).any { it.id == running.id },
			"the other destination's job carries none of this one's state and supersedes nothing",
		)
	}

	@Test
	fun `a dead worker's job survives another destination holding the same row`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val abandoned = claimedFor(id, worker = "dead-worker").single()
		enqueueElsewhere(id)

		backdate(abandoned.id, lockHeld = Duration.ofMinutes(10), unheardFrom = Duration.ofMinutes(10))
		jobs.reclaimStuck(Duration.ofMinutes(5))

		assertEquals("pending", statusOf(abandoned.id), "the sweep must not read it as superseded")
	}

	@Test
	fun `retrying failures is one destination's own business`() {
		val id = freshEntity()
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)
		val mine = claimedFor(id).single()
		jobs.markFailed(mine.id, "Notion said no")
		val theirs = enqueueElsewhere(freshEntity(), status = "failed")

		jobs.retryAllFailed(Destination.NOTION)

		assertEquals("pending", statusOf(mine.id), "the mirror's own failure is what the button retries")
		assertEquals("failed", statusOf(theirs), "a button on the mirror's screen retries the mirror")
		assertTrue(jobs.findFailed(Destination.NOTION).none { it.id == theirs })
		// Against the table's own count for this destination rather than against zero: what
		// is being pinned is that `countsByStatus` filters, and "there are no failed Notion
		// jobs anywhere" is a claim about the run that nothing here is entitled to make.
		assertEquals(
			failedRows(Destination.NOTION.wire),
			jobs.countsByStatus(Destination.NOTION)["failed"] ?: 0L,
			"and does not count somebody else's: 'elsewhere' has a failed row this must not see",
		)
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

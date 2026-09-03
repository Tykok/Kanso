package dev.kanso.outbox

import dev.kanso.PostgresTest
import dev.kanso.config.KansoProperties
import dev.kanso.repo.OutboundJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worker, with nothing on the other end of it.
 *
 * This is the test the split exists for: draining, spending an attempt, backing off,
 * refunding a deferral and giving up are the queue's policy, not Notion's, and they
 * have to be provable without a Notion. The handler here is three lines of fake, and
 * the fact that it is enough is the whole claim of the refactor.
 *
 * Built by hand rather than autowired: the real Notion handler already claims
 * [Destination.NOTION] in the application context, and two handlers for one
 * destination is exactly the wiring the worker refuses.
 */
@Transactional
class OutboundWorkerTest : PostgresTest() {

	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var tx: TransactionTemplate

	/**
	 * Handed over but never used: `start` is what registers a clock on it, and a worker
	 * built by hand never has `@PostConstruct` called. Every drain below is this test's
	 * own call, on this test's thread, which is what makes the assertions worth making.
	 */
	@Autowired lateinit var scheduler: TaskScheduler

	/** Records what it was asked to do, and fails on command. */
	private class FakeHandler(
		private val failWith: Exception? = null,
		private val verdict: (Exception) -> Failure = { Failure.Retry(it.message ?: "boom") },
	) : OutboundJobHandler {
		override val destination = Destination.NOTION
		val handled = mutableListOf<OutboundJob>()
		val recorded = mutableListOf<Long>()
		val givenUp = mutableListOf<Long>()

		/**
		 * Runs once, from inside the first push, and is forgotten before it runs.
		 *
		 * It is how "something else happened while this push was in flight" becomes a
		 * statement a test can make. Cleared first rather than after, because what the
		 * caller below puts in here starts a drain, and a drain that re-entered this hook
		 * would never come back out.
		 */
		var duringPush: (() -> Unit)? = null

		override suspend fun handle(job: OutboundJob): Completion {
			handled += job
			duringPush?.also { duringPush = null }?.invoke()
			failWith?.let { throw it }
			return Completion { recorded += job.id }
		}

		override fun classify(error: Exception) = verdict(error)

		override fun onGivenUp(job: OutboundJob, error: Exception) {
			givenUp += job.id
		}
	}

	private fun worker(handler: OutboundJobHandler, maxAttempts: Int = 8) = OutboundWorker(
		props = KansoProperties(
			sync = KansoProperties.Sync(
				outbound = KansoProperties.Outbound(maxAttempts = maxAttempts),
			),
		),
		jobs = jobs,
		handlers = listOf(handler),
		tx = tx,
		scheduler = scheduler,
	)

	private fun statusOf(entityId: UUID): String? = jdbc.sql(
		"SELECT status FROM outbound_jobs WHERE entity_id = :id"
	).param("id", entityId).query { rs, _ -> rs.getString("status") }.optional().orElse(null)

	private fun queue(id: UUID) =
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

	/**
	 * Makes the in-flight push look [age] old, lock and heartbeat alike.
	 *
	 * Ageing the row rather than waiting: what is under test is a push that outlasts the
	 * timeout, and the only alternative to moving the timestamps is a test that sleeps for
	 * longer than `stuck-job-timeout`.
	 */
	private fun backdate(entityId: UUID, age: Duration) {
		jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET locked_at    = clock_timestamp() - make_interval(secs => :seconds),
			       heartbeat_at = clock_timestamp() - make_interval(secs => :seconds)
			 WHERE entity_id = :id AND status = 'running'
			""".trimIndent()
		).param("seconds", age.seconds.toDouble()).param("id", entityId).update()
	}

	/**
	 * The replay KAN-61 is about, as an interleaving rather than a race.
	 *
	 * The sweep has its own clock, so in production it fires from another thread while a
	 * drain sits blocked inside `handle`. Here it is called *from* inside `handle`, which
	 * is that same interleaving with the timing taken out of it: one thread, one
	 * transaction, and the same verdict every run.
	 *
	 * What is asserted is the double *send*, not the state of the row. A replayed push is
	 * invisible from the queue's side — the second claim looks like any other claim, and
	 * the row ends up 'done' either way — and it is invisible against a client that
	 * no-ops, which is what has kept this bug quiet. `handled` counts the times the
	 * operation actually left the building, and nothing else here would notice.
	 */
	@Test
	fun `a push past the stuck-job timeout is not reclaimed and replayed under itself`() {
		val id = UUID.randomUUID()
		queue(id)

		val handler = FakeHandler()
		val worker = worker(handler)
		handler.duringPush = {
			// Forty minutes into a push, against a five-minute timeout. Any deadline worth
			// configuring is one that a slow enough remote can outlast, which is why
			// lengthening it was never the fix.
			backdate(id, Duration.ofMinutes(40))
			// The heartbeat's own clock would have done this; it runs by hand here for the
			// same reason the sweep does.
			worker.beat()
			worker.reclaimAbandoned()
			// And whatever the sweep put back, a peer drain claims on its next tick.
			worker.drain(handler)
		}

		worker.drain(handler)

		// Counted for this entity rather than over everything the drain touched: a drain
		// claims a whole batch, so a total would be a statement about the shared queue and
		// would go red on somebody else's leftover row instead of on a replay.
		assertEquals(
			1,
			handler.handled.count { it.entityId == id },
			"the same operation went out twice while the first send was still in the air",
		)
		assertEquals("done", statusOf(id))
	}

	@Test
	fun `a handled job is marked done in the same transaction that records it`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler()

		worker(handler).drain(handler)

		assertEquals(1, handler.handled.size)
		assertEquals(handler.handled.single().id, handler.recorded.single())
		assertEquals("done", statusOf(id))
	}

	@Test
	fun `the worker asks the handler what a failure was, and retries the ones worth retrying`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(failWith = IllegalStateException("Notion said 500"))

		worker(handler).drain(handler)

		assertEquals("pending", statusOf(id), "a retryable failure goes back in the queue")
		assertTrue(handler.givenUp.isEmpty(), "one bad attempt out of eight is not giving up")
	}

	@Test
	fun `a deferral refunds its attempt, because waiting is not failing`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(
			failWith = IllegalStateException("dependency not ready"),
			verdict = { Failure.Defer(it.message ?: "", Duration.ZERO) },
		)

		worker(handler).drain(handler)

		// The claim spent one; the deferral gave it back. Claiming again spends the same one.
		assertEquals(1, jobs.claimBatch(Destination.NOTION, 1, "test").single().attempts)
	}

	@Test
	fun `a fatal failure does not spend eight attempts proving it`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(
			failWith = IllegalStateException("that property does not exist"),
			verdict = { Failure.Fatal(it.message ?: "") },
		)

		worker(handler).drain(handler)

		assertEquals("failed", statusOf(id))
		assertEquals(1, handler.givenUp.size, "the handler gets its last rites before the row is written")
	}

	@Test
	fun `a retryable failure becomes a failure once the budget is gone`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(failWith = IllegalStateException("still 500"))

		// max-attempts of one: the claim spends the only attempt there was.
		worker(handler, maxAttempts = 1).drain(handler)

		assertEquals("failed", statusOf(id))
		assertEquals(1, handler.givenUp.size)
	}

	/**
	 * Counts the clocks `start` asks for, and runs none of them.
	 *
	 * Subclasses the scheduler the application actually gets, rather than stubbing the
	 * five members of the interface that this never reaches.
	 */
	private class RecordingScheduler : ThreadPoolTaskScheduler() {
		val delays = mutableListOf<Duration>()
		private val parked = ScheduledThreadPoolExecutor(1)

		override fun scheduleWithFixedDelay(task: Runnable, delay: Duration): ScheduledFuture<*> {
			delays += delay
			// A real future for a task due in a day: what was asked for is the whole
			// assertion, and a drain firing here would race it.
			return parked.schedule({}, 1, TimeUnit.DAYS)
		}
	}

	@Test
	fun `every handler gets its own clock, at the configured interval`() {
		val scheduler = RecordingScheduler()

		OutboundWorker(
			props = KansoProperties(
				sync = KansoProperties.Sync(outbound = KansoProperties.Outbound(pollIntervalMs = 250)),
			),
			jobs = jobs,
			handlers = listOf(FakeHandler()),
			tx = tx,
			scheduler = scheduler,
		).start()

		assertEquals(
			// One drain clock per handler, at the configured interval, plus the single
			// heartbeat that covers every push this process holds — the drains cannot beat
			// for themselves, because a blocked drain is exactly the interval to cover.
			listOf(Duration.ofMillis(250), Duration.ofSeconds(30)),
			scheduler.delays,
		)
	}

	/**
	 * The beat interval is derived from the timeout rather than configured beside it.
	 *
	 * A second knob could be set larger than the timeout, and then every in-flight job
	 * would be reclaimable in the gap between two beats — the replay this all exists to
	 * close, arriving through configuration instead of through code.
	 */
	@Test
	fun `the heartbeat fits ten beats inside the window that declares a worker dead`() {
		val scheduler = RecordingScheduler()

		OutboundWorker(
			props = KansoProperties(
				sync = KansoProperties.Sync(
					outbound = KansoProperties.Outbound(
						pollIntervalMs = 250,
						stuckJobTimeout = Duration.ofMinutes(20),
					),
				),
			),
			jobs = jobs,
			handlers = listOf(FakeHandler()),
			tx = tx,
			scheduler = scheduler,
		).start()

		assertEquals(Duration.ofMinutes(2), scheduler.delays.last())
	}

	@Test
	fun `an outbound worker that is switched off registers no clock at all`() {
		val scheduler = RecordingScheduler()

		OutboundWorker(
			props = KansoProperties(
				sync = KansoProperties.Sync(outbound = KansoProperties.Outbound(enabled = false)),
			),
			jobs = jobs,
			handlers = listOf(FakeHandler()),
			tx = tx,
			scheduler = scheduler,
		).start()

		assertTrue(
			scheduler.delays.isEmpty(),
			"a registered clock claims jobs from under every test in the suite, which is what `enabled: false` buys",
		)
	}

	@Test
	fun `two handlers for one destination is refused rather than half-obeyed`() {
		val clash = runCatching {
			OutboundWorker(KansoProperties(), jobs, listOf(FakeHandler(), FakeHandler()), tx, scheduler)
		}

		assertTrue(
			clash.exceptionOrNull() is IllegalArgumentException,
			"silently dropping one leaves half a destination's jobs handled by code nobody meant to run",
		)
	}
}

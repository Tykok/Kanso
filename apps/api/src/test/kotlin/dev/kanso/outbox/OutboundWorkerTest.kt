package dev.kanso.outbox

import dev.kanso.PostgresTest
import dev.kanso.config.KansoProperties
import dev.kanso.repo.OutboundJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
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

	/** Records what it was asked to do, and fails on command. */
	private class FakeHandler(
		private val failWith: Exception? = null,
		private val verdict: (Exception) -> Failure = { Failure.Retry(it.message ?: "boom") },
	) : OutboundJobHandler {
		override val destination = Destination.NOTION
		val handled = mutableListOf<OutboundJob>()
		val recorded = mutableListOf<Long>()
		val givenUp = mutableListOf<Long>()

		override suspend fun handle(job: OutboundJob): Completion {
			handled += job
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
	)

	private fun statusOf(entityId: UUID): String? = jdbc.sql(
		"SELECT status FROM outbound_jobs WHERE entity_id = :id"
	).param("id", entityId).query { rs, _ -> rs.getString("status") }.optional().orElse(null)

	private fun queue(id: UUID) =
		jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, id, OutboundOperation.UPSERT)

	@Test
	fun `a handled job is marked done in the same transaction that records it`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler()

		worker(handler).drain()

		assertEquals(1, handler.handled.size)
		assertEquals(handler.handled.single().id, handler.recorded.single())
		assertEquals("done", statusOf(id))
	}

	@Test
	fun `the worker asks the handler what a failure was, and retries the ones worth retrying`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(failWith = IllegalStateException("Notion said 500"))

		worker(handler).drain()

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

		worker(handler).drain()

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

		worker(handler).drain()

		assertEquals("failed", statusOf(id))
		assertEquals(1, handler.givenUp.size, "the handler gets its last rites before the row is written")
	}

	@Test
	fun `a retryable failure becomes a failure once the budget is gone`() {
		val id = UUID.randomUUID()
		queue(id)
		val handler = FakeHandler(failWith = IllegalStateException("still 500"))

		// max-attempts of one: the claim spends the only attempt there was.
		worker(handler, maxAttempts = 1).drain()

		assertEquals("failed", statusOf(id))
		assertEquals(1, handler.givenUp.size)
	}

	@Test
	fun `two handlers for one destination is refused rather than half-obeyed`() {
		val clash = runCatching {
			OutboundWorker(KansoProperties(), jobs, listOf(FakeHandler(), FakeHandler()), tx)
		}

		assertTrue(
			clash.exceptionOrNull() is IllegalArgumentException,
			"silently dropping one leaves half a destination's jobs handled by code nobody meant to run",
		)
	}
}

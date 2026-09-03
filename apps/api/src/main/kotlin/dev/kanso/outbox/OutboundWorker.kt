package dev.kanso.outbox

import dev.kanso.config.KansoProperties
import dev.kanso.repo.OutboundJobRepository
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * Drains the outbox. Knows nothing about where the jobs are going.
 *
 * Everything here is destination-agnostic on purpose: claiming a batch, spending an
 * attempt, backing off with jitter, giving up at `max-attempts`, and putting back
 * what a dead worker was holding. What a job means is an [OutboundJobHandler]'s
 * business, and the split is the whole point of the queue — a second consumer is a
 * handler, not another copy of this file.
 *
 * A job is claimed in its own committed transaction so peers immediately see it as
 * taken; the handler's remote call then happens with no transaction open; the result
 * is recorded in a third. Holding a database connection across a round trip of
 * hundreds of milliseconds would exhaust the pool long before the rate limit.
 */
@Component
class OutboundWorker(
	private val props: KansoProperties,
	private val jobs: OutboundJobRepository,
	handlers: List<OutboundJobHandler>,
	private val tx: TransactionTemplate,
	private val scheduler: TaskScheduler,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val byDestination: Map<Destination, OutboundJobHandler> = buildMap {
		for (handler in handlers) {
			// Two handlers for one destination is a wiring mistake, and `associateBy`
			// would answer it by silently dropping one of them — leaving half the jobs
			// for that destination handled by code nobody meant to run.
			val clash = put(handler.destination, handler)
			require(clash == null) {
				"Two handlers claim ${handler.destination.wire}: " +
					"${clash?.javaClass?.simpleName} and ${handler.javaClass.simpleName}"
			}
		}
	}

	/** Identifies this process in `outbound_jobs.locked_by`, for debugging a stuck queue. */
	private val workerId: String = runCatching { InetAddress.getLocalHost().hostName }
		.getOrDefault("unknown") + "/" + UUID.randomUUID().toString().take(8)

	/**
	 * The jobs this process has in flight right now, and the only thing [beat] stamps.
	 *
	 * In memory rather than read back out of the table, because the question is about
	 * *this* process and the table cannot answer it: a 'running' row says somebody claimed
	 * it, not that whoever did is still there — which is the entire distinction the
	 * heartbeat exists to draw. Reading `locked_by` would not help either. The id is
	 * shared by every drain in this process and, worse, would be an identity comparison
	 * back in the sweep's SQL, which is precisely what makes concurrent drains harmless
	 * today.
	 *
	 * Concurrent because there is one drain per destination, each on its own scheduler
	 * thread, and [beat] walks this from a third.
	 */
	private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

	/**
	 * How often to prove this process is alive, derived rather than configured.
	 *
	 * A tenth of the timeout the sweep applies, so declaring a worker dead takes ten
	 * consecutive missed beats. That ratio is the property worth holding — a second knob
	 * could be set to a value larger than the timeout, which would make every in-flight
	 * job reclaimable between beats and reintroduce exactly the replay this closes. The
	 * floor keeps a deliberately tiny timeout (the tests use one) from asking for a beat
	 * every few milliseconds.
	 */
	private val heartbeatInterval: Duration =
		props.sync.outbound.stuckJobTimeout.dividedBy(10).coerceAtLeast(Duration.ofSeconds(1))

	/**
	 * One clock per destination, rather than one clock walking all of them.
	 *
	 * The batch size is already a per-destination budget, so a destination that is down
	 * and filling the queue cannot spend another's share of it. The clock was not: a
	 * single `fixedDelay` counts from the end of the previous pass, so a GitHub batch
	 * spending a minute in retries would hold Notion's next tick for that minute,
	 * though the two share nothing but this loop. Each destination now waits only on
	 * itself — and still never overlaps itself, which is `fixedDelay`'s other guarantee
	 * and the reason this stays a scheduler rather than becoming an executor.
	 *
	 * Registered by hand because an annotation is one clock for the method, and the
	 * number of clocks is the number of handlers. They share the scheduler pool that
	 * `application.yml` sizes for them.
	 */
	@PostConstruct
	fun start() {
		if (!props.sync.outbound.enabled) return
		val interval = Duration.ofMillis(props.sync.outbound.pollIntervalMs)
		byDestination.values.forEach { handler ->
			scheduler.scheduleWithFixedDelay({ drain(handler) }, interval)
		}
		// One beat for the whole process, not one per destination: [inFlight] is already
		// every push this process is holding, and what is being reported is the process
		// being alive rather than any one destination's progress.
		scheduler.scheduleWithFixedDelay(::beat, heartbeatInterval)
	}

	fun drain(handler: OutboundJobHandler) {
		val destination = handler.destination
		val batch = tx.execute { jobs.claimBatch(destination, props.sync.outbound.batchSize, workerId) }.orEmpty()
		if (batch.isEmpty()) return

		// Sequential on purpose: the rate limiter is the bottleneck, so running these
		// in parallel would only queue them inside the limiter while losing the
		// priority order that keeps teams ahead of tickets.
		runBlocking {
			batch.forEach { job ->
				// Registered before the push and dropped in `finally`, so the window in
				// which the sweep could read this job as abandoned is never open while
				// the push is running — including when the push throws.
				inFlight += job.id
				try {
					val completion = handler.handle(job)
					tx.executeWithoutResult {
						completion.record()
						jobs.markDone(job.id)
					}
				} catch (e: Exception) {
					record(handler, job, e)
				} finally {
					inFlight -= job.id
				}
			}
		}
	}

	/**
	 * Tells the table this process is still here, for every push it is holding.
	 *
	 * This runs on its own clock because it cannot run on the drain's: a drain is blocked
	 * inside `handler.handle` for the whole duration of the remote call, which is exactly
	 * the interval that needs covering. `application.yml` sizes the scheduler pool for
	 * this thread by name.
	 *
	 * Nothing may escape. `scheduleWithFixedDelay` cancels a task that throws, so one
	 * failed beat would silently stop every future beat, and the next sweep would reclaim
	 * every in-flight job in the process and replay all of them — the failure this whole
	 * change exists to remove, arriving through the back door. A missed beat is survivable
	 * (it takes ten); a cancelled clock is not.
	 */
	fun beat() {
		val held = inFlight.toList()
		if (held.isEmpty()) return
		try {
			tx.executeWithoutResult { jobs.heartbeat(held) }
		} catch (e: Exception) {
			log.warn("Could not refresh the heartbeat on {} in-flight job(s)", held.size, e)
		}
	}

	/**
	 * Returns what a *dead* worker was holding — and, since `V29`, nothing that a live one
	 * is merely slow with. `stuckJobTimeout` is now read as a number of missed heartbeats
	 * rather than as a longest-acceptable push; [OutboundJobRepository.reclaimStuck]
	 * carries the argument.
	 */
	@Scheduled(fixedDelay = 60_000)
	fun reclaimAbandoned() {
		if (!props.sync.outbound.enabled) return
		val reclaimed = tx.execute { jobs.reclaimStuck(props.sync.outbound.stuckJobTimeout) } ?: 0
		if (reclaimed > 0) log.warn("Requeued {} job(s) abandoned by a dead worker", reclaimed)
	}

	private fun record(handler: OutboundJobHandler, job: OutboundJob, error: Exception) {
		when (val failure = handler.classify(error)) {
			is Failure.Defer -> {
				log.debug(
					"Deferring {} {} {} for {}s: {}",
					job.destination.wire, job.entityType.wire, job.entityId,
					failure.delay.toSeconds(), failure.reason,
				)
				tx.executeWithoutResult { jobs.defer(job, failure.reason, failure.delay) }
			}

			is Failure.Fatal -> giveUp(handler, job, error, failure.message)

			is Failure.Retry ->
				if (job.attempts >= props.sync.outbound.maxAttempts) {
					giveUp(handler, job, error, failure.message)
				} else {
					val delay = backoff(job.attempts)
					log.warn(
						"Push of {} {} to {} failed (attempt {}/{}), retrying in {}s: {}",
						job.entityType.wire, job.entityId, job.destination.wire,
						job.attempts, props.sync.outbound.maxAttempts, delay.toSeconds(), failure.message,
					)
					tx.executeWithoutResult { jobs.scheduleRetry(job, failure.message, delay) }
				}
		}
	}

	private fun giveUp(handler: OutboundJobHandler, job: OutboundJob, error: Exception, message: String) {
		log.error("Giving up on {} {} for {}: {}", job.entityType.wire, job.entityId, job.destination.wire, message)
		tx.executeWithoutResult {
			handler.onGivenUp(job, error)
			jobs.markFailed(job.id, message)
		}
	}

	/**
	 * Exponential with jitter. The jitter matters here: without it, an outage makes
	 * every queued job retry in the same instant and immediately trip the rate limit
	 * again.
	 */
	private fun backoff(attempts: Int): Duration {
		val exponential = min(BASE_DELAY_SECONDS shl attempts.coerceAtMost(10), MAX_DELAY_SECONDS)
		val jitter = Random.nextDouble(0.5, 1.5)
		return Duration.ofMillis((exponential * 1000 * jitter).toLong())
	}

	private companion object {
		const val BASE_DELAY_SECONDS = 2L
		const val MAX_DELAY_SECONDS = 300L
	}
}

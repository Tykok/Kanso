package dev.kanso.outbox

import dev.kanso.config.KansoProperties
import dev.kanso.repo.OutboundJobRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
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

	@Scheduled(fixedDelayString = "\${kanso.sync.outbound.poll-interval-ms:500}")
	fun drain() {
		if (!props.sync.outbound.enabled) return
		// One claim per destination rather than one across all of them. The batch size
		// is a budget and a rate limit belongs to a remote, so a destination that is
		// down and filling the queue must not spend another destination's share of
		// either — and a slow push must not sit in front of a fast one that has nothing
		// to do with it.
		byDestination.values.forEach(::drain)
	}

	private fun drain(handler: OutboundJobHandler) {
		val destination = handler.destination
		val batch = tx.execute { jobs.claimBatch(destination, props.sync.outbound.batchSize, workerId) }.orEmpty()
		if (batch.isEmpty()) return

		// Sequential on purpose: the rate limiter is the bottleneck, so running these
		// in parallel would only queue them inside the limiter while losing the
		// priority order that keeps teams ahead of tickets.
		runBlocking {
			batch.forEach { job ->
				try {
					val completion = handler.handle(job)
					tx.executeWithoutResult {
						completion.record()
						jobs.markDone(job.id)
					}
				} catch (e: Exception) {
					record(handler, job, e)
				}
			}
		}
	}

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

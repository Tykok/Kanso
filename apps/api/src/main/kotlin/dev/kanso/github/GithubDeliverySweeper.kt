package dev.kanso.github

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * What keeps `github_deliveries` from being a table that only grows.
 *
 * Named by `V36`, and it says the shape and the reasons: `TrashSweeper`'s, because "when
 * does this run" should be one annotation in one short file rather than a property of the
 * thing that does the work.
 *
 * **Thirty days is chosen at both ends.** Longer than GitHub retries — it gives up within
 * about three days — so pruning can never resurrect a duplicate by forgetting an id that
 * is still in flight; and short enough that a busy instance's table stays small, since
 * nothing reads these rows except the insert that collides with them.
 *
 * Daily rather than hourly, unlike the trash: nothing on any screen depends on a row here
 * being gone, so the sweep is bookkeeping and its only deadline is "before the table gets
 * big". The trash sweeps hourly because a countdown printed on a screen has to be true.
 *
 * Off in the test profile, the bean absent rather than guarded by an `if`, so there is no
 * thread to race with at all — `V36` asks for exactly this, so that no scheduler deletes
 * rows from under an assertion.
 */
@Component
@ConditionalOnProperty(name = ["kanso.github.deliveries.sweep.enabled"], matchIfMissing = true)
class GithubDeliverySweeper(private val github: GithubRepository) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${kanso.github.deliveries.sweep.interval-ms:86400000}", initialDelay = 300_000)
	@Transactional
	fun sweep() {
		val cutoff = OffsetDateTime.now().minusDays(DELIVERY_RETENTION_DAYS)
		val pruned = runCatching { github.pruneDeliveriesBefore(cutoff) }.getOrElse {
			// Logged and swallowed, `TrashSweeper`'s reason: a failing sweep must not kill the
			// scheduler's thread and take every later sweep with it. Nothing is lost — the rows
			// are still there and still old when the next one runs.
			log.warn("Pruning GitHub deliveries failed; retrying at the next sweep", it)
			return
		}
		if (pruned > 0) log.info("Pruned {} GitHub deliveries older than {} days", pruned, DELIVERY_RETENTION_DAYS)
	}

	private companion object {
		/** `V36`'s number, and it is argued there rather than restated here. */
		const val DELIVERY_RETENTION_DAYS = 30L
	}
}

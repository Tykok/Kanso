package dev.kanso.trash

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * What makes the countdown a fact rather than a decoration.
 *
 * "Emptied after 30 days" is printed on the screen, so something has to do it; a trash
 * that says `1 j` forever is a worse promise than one that says nothing. Hourly rather
 * than by the minute: the unit on screen is a whole day, so an entry can sit an hour past
 * its expiry without anybody being able to tell, and the sweep is a table scan of the
 * small set.
 *
 * Separate from [TrashService] so that the service stays callable — and testable — with an
 * explicit clock, and so that "when does this run" is one annotation in one short file
 * rather than a property of the thing that does the work.
 */
@Component
class TrashSweeper(private val trash: TrashService) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${kanso.trash.sweep-interval-ms:3600000}", initialDelay = 60_000)
	fun sweep() {
		val emptied = runCatching { trash.empty() }.getOrElse {
			// Logged and swallowed: a failing sweep must not kill the scheduler's thread and
			// take every later sweep with it. Nothing is lost — the entries are still there
			// and still expired when the next one runs.
			log.warn("Emptying the trash failed; retrying at the next sweep", it)
			return
		}
		if (emptied > 0) log.info("Trash emptied: {} entries past {} days", emptied, TRASH_RETENTION_DAYS)
	}
}

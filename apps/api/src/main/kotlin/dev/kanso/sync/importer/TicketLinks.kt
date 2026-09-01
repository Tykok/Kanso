package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.service.ScheduleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** What the dependency pass drew, and what it could not. */
data class Dependencies(val created: Int, val dropped: Int)

/**
 * Turns the relations [ImportLinks] resolved into dependencies, once for every base.
 *
 * A dependency is exactly what the mapping called a dependency — the column somebody
 * pointed at `Blocked by` — and not every relation the page happens to hold: a
 * `Related` column is a link between two pages, not an order to do them in, and turning
 * one into an arrow put work in a queue nobody asked for.
 *
 * Through [ScheduleService.link] rather than the repository, so an imported arrow is
 * settled by the same engine as a drawn one and a cycle is refused rather than stored.
 * A refusal drops that one arrow and counts it: a workspace whose relations happen to
 * form a loop is not a reason to fail an import of four hundred pages, and the count is
 * what tells the reader it happened.
 *
 * The refusal is *asked for* through [ScheduleService.linkRefusal] before the arrow is
 * drawn, never caught after: `link` is `@Transactional` and only participates in
 * [ImportWriter]'s transaction, so an exception leaving it marks the whole run
 * rollback-only before any `catch` here could run — see [ImportWriter] for the rule. A
 * `try`/`catch` around `link` is what this file used to hold, and it silently lost the
 * import it was written to protect.
 *
 * Its own file, next to [TicketImport] rather than inside it: a dependency is drawn once
 * per *base*, after every tickets base has been written, which is a different rhythm from
 * writing one ticket at a time — keeping the two apart is what let [TicketImport] gain an
 * assignee without outgrowing the shape these files hold.
 */
@Service
class TicketLinks(private val schedule: ScheduleService) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun settle(actor: User, links: ImportLinks.Resolved, rows: ImportedRows): Dependencies {
		var created = 0
		var dropped = 0
		for (edge in links.dependencies) {
			val predecessor = rows.ticket(edge.predecessorPageId)
			val successor = rows.ticket(edge.successorPageId)
			if (predecessor == null || successor == null) {
				dropped++
				continue
			}
			val refusal = schedule.linkRefusal(predecessor, successor)
			if (refusal != null) {
				log.info("Dropped an imported relation: {}", refusal)
				dropped++
				continue
			}
			schedule.link(actor, predecessor, successor)
			created++
		}
		return Dependencies(created, dropped)
	}
}

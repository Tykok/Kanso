package dev.kanso.sync.importer

import dev.kanso.domain.User
import dev.kanso.service.ConflictException
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
			try {
				schedule.link(actor, predecessor, successor)
				created++
			} catch (e: ConflictException) {
				log.info("Dropped an imported relation: {}", e.message)
				dropped++
			}
		}
		return Dependencies(created, dropped)
	}
}

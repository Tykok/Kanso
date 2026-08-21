package dev.kanso.sync.importer

import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.OriginKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * `@Transactional` for the same reason every sibling import test carries it: `admin` and
 * `team` are lazy repository calls, and Exposed needs a transaction in context the first
 * time either is touched. The suite rolls back, so nothing here ever reaches `pg_notify`.
 */
@Transactional
class ImportOriginTest : ImportTestBase() {

	@Autowired private lateinit var origins: ImportOriginRepository

	@Test
	fun `a second import of the same base writes nothing and says so`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it"), fakePage("Then rest")))
		val importer = importerFor(tasks)
		val steps = plan(tasks to ImportTarget.TICKETS)

		val first = importer.perform(admin, team.id, steps)
		assertEquals(2, first.tickets)
		assertEquals(0, first.alreadyImported)

		val before = rowCounts()
		val second = importer.perform(admin, team.id, steps)

		assertEquals(0, second.tickets, "nothing is written the second time")
		assertEquals(2, second.alreadyImported, "and it is counted, not silent")
		assertEquals(before, rowCounts())
	}

	@Test
	fun `the preview counts what a second import would skip`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))
		val importer = importerFor(tasks)
		val steps = plan(tasks to ImportTarget.TICKETS)
		importer.perform(admin, team.id, steps)

		assertEquals(1, importer.preview(steps).alreadyImported)
	}

	@Test
	fun `an origin names the row it created`() {
		val page = fakePage("Ship it")
		val tasks = FakeDatabase("Tasks", listOf(page))
		importerFor(tasks).perform(admin, team.id, plan(tasks to ImportTarget.TICKETS))

		val origin = tx.execute { origins.byPageIds(listOf(page.id)) }!!.getValue(page.id)
		assertEquals(OriginKind.TICKET, origin.kind)
		assertEquals(tasks.dataSourceId, origin.dataSourceId)
	}
}

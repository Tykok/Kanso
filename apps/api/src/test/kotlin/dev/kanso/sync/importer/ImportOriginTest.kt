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

		val before = rowCounts()
		val preview = importer.preview(steps)

		assertEquals(1, preview.alreadyImported)
		assertEquals(before, rowCounts(), "a preview counts what it saw; it never records it")
	}

	@Test
	fun `a page already imported keeps its bucket no matter what Notion says about it now`() {
		// The base as it was on the first run: one page, imported clean.
		val dataSourceId = "ds-tasks"
		val first = FakeDatabase("Tasks", listOf(fakePage("Ship it", id = "page-imported")), dataSourceId = dataSourceId)
		importerFor(first).perform(admin, team.id, plan(first to ImportTarget.TICKETS))

		// The base as it is now: the imported page has since lost its title in Notion — a
		// non-archived page discovery still returns — a brand new page has appeared, and a
		// page nobody ever imported has no title either. Only the last of those is
		// "unadoptable": the first is already in Kanso, and what Notion currently says about
		// it (untitled, archived, whatever) is beside the point.
		val second = FakeDatabase(
			"Tasks",
			listOf(
				untitledPage("page-imported"),
				fakePage("Add tests", id = "page-new"),
				untitledPage("page-never-imported"),
			),
			dataSourceId = dataSourceId,
		)
		val preview = importerFor(second).preview(plan(second to ImportTarget.TICKETS))

		assertEquals(1, preview.alreadyImported, "the now-untitled page is already imported; that does not change")
		assertEquals(1, preview.skipped, "only the untitled page nobody imported is unadoptable")
		assertEquals(listOf("Tasks" to 1), preview.projects.map { it.name to it.pages }, "the new page is neither")
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

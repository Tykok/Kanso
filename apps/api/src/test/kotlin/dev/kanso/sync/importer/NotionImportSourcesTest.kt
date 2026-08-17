package dev.kanso.sync.importer

import dev.kanso.config.KansoProperties
import dev.kanso.sync.notion.NoopNotionClient
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step 1 of screen 24: what is in this workspace.
 *
 * The step the dialog was already wired to and the server could not answer, which is why
 * the whole flow never began. The drawing's own four bases and its own page counts are
 * used throughout, so the numbers here and the numbers `import-map.test.ts` asserts on
 * come from the same place.
 */
@Transactional
class NotionImportSourcesTest : ImportTestBase() {

	private fun drawnWorkspace() = listOf(
		FakeDatabase("Engineering tasks", pages = List(248) { fakePage("Task $it") }),
		FakeDatabase("Design docs", pages = List(36) { fakePage("Doc $it") }),
		FakeDatabase("Meeting notes", pages = List(112) { fakePage("Note $it") }),
		FakeDatabase("Archive 2023", pages = List(891) { fakePage("Old $it") }),
	)

	@Test
	fun `lists every base the token can see, with the count screen 24 puts on it`() {
		val sources = importerFor(FakeNotionWorkspace(drawnWorkspace())).sources()

		assertTrue(sources.available, "a token that can search is an available workspace")
		assertEquals(
			listOf("Engineering tasks" to 248, "Design docs" to 36, "Meeting notes" to 112, "Archive 2023" to 891),
			sources.sources.map { it.name to it.pages },
		)
		assertEquals(1287, sources.sources.sumOf { it.pages }, "the drawing's own total")
		assertTrue(sources.sources.all { it.pagesExact }, "and every count is exact, not a cap")
	}

	@Test
	fun `walks past the first page of results rather than stopping at the cursor`() {
		// Both cursors are real in the fake: a search that ignored `hasMore` would find
		// two bases, and a count that ignored it would find a hundred pages of 248.
		val workspace = FakeNotionWorkspace(drawnWorkspace())
		val sources = importerFor(workspace, KansoProperties.Notion.Import(pageSize = 2)).sources()

		assertEquals(4, sources.sources.size)
		assertEquals(248, sources.sources.first().pages)
	}

	@Test
	fun `counts a long base in pages of a hundred, not one request per row`() {
		val workspace = FakeNotionWorkspace(listOf(FakeDatabase("Archive 2023", List(891) { fakePage("Old $it") })))
		importerFor(workspace).sources()

		// One search, then nine queries for 891 rows. The ceiling is ~2.5 requests a
		// second for the whole instance, so the cost of this step is the thing to pin.
		assertEquals(10, workspace.requests, "one search plus ceil(891/100) queries")
	}

	@Test
	fun `says a count is not exact rather than reporting the cap as the truth`() {
		val workspace = FakeNotionWorkspace(listOf(FakeDatabase("Archive 2023", List(891) { fakePage("Old $it") })))
		val sources = importerFor(workspace, KansoProperties.Notion.Import(maxPagesPerDatabase = 300)).sources()

		val archive = sources.sources.single()
		assertEquals(300, archive.pages)
		assertFalse(archive.pagesExact, "300 of 891 is not 300 pages, and the dialog has to be able to say so")
	}

	@Test
	fun `leaves Kanso's own mirrored databases out of what it offers to import`() {
		// The import reads the same workspace the mirror writes to. Offering
		// `Kanso · Tickets` back would duplicate every ticket in the instance.
		val mine = FakeDatabase("Kanso · Tickets", pages = List(3) { fakePage("KAN-$it") })
		meta.save("tickets", mine.databaseId, mine.dataSourceId, null)

		val sources = importerFor(FakeNotionWorkspace(listOf(mine, FakeDatabase("Engineering tasks")))).sources()

		assertEquals(listOf("Engineering tasks"), sources.sources.map { it.name })
	}

	@Test
	fun `says why there is nothing to import when no token is configured`() {
		val sources = importerFor(NoopNotionClient()).sources()

		assertFalse(sources.available)
		val reason = assertNotNull(sources.reason, "an unavailable workspace owes the reader a sentence")
		assertTrue(reason.contains("token"), "and the sentence names the thing to go and do: $reason")
		assertEquals(emptyList(), sources.sources, "no empty table pretending the workspace is empty")
	}
}

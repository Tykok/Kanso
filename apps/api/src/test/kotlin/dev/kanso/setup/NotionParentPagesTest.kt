package dev.kanso.setup

import dev.kanso.sync.importer.FakeDatabase
import dev.kanso.sync.importer.FakeNotionWorkspace
import dev.kanso.sync.importer.fakePage
import dev.kanso.sync.importer.sharedPage
import dev.kanso.sync.notion.NoopNotionClient
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionPageSearch
import dev.kanso.sync.notion.NotionRateLimited
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list behind the parent-page picker.
 *
 * The field this replaces asked for "32 hex characters from the page URL", and a
 * plausible-looking id for a page the integration cannot see failed at bootstrap rather
 * than at save. Everything asserted here is about that: what the picker can offer, what
 * it must refuse to offer, and what it says when it can offer nothing — because an empty
 * list *is* the diagnosis of the old flow's silent failure.
 */
class NotionParentPagesTest {

	@Test
	fun `lists the pages shared with the integration, with what it takes to recognise one`() {
		val workspace = FakeNotionWorkspace(
			databases = emptyList(),
			shared = listOf(
				sharedPage("Kanso", id = "page-root", url = "https://notion.so/page-root"),
				sharedPage("Engineering", id = "page-eng", parentType = "page_id"),
			),
		)

		val answer = NotionParentPages.list(workspace)

		assertTrue(answer.available, "a token that can search is a workspace the picker can draw")
		assertNull(answer.reason, "nothing to explain when there is something to pick")
		assertEquals(listOf("Kanso", "Engineering"), answer.pages.map { it.title })
		assertEquals(listOf("page-root", "page-eng"), answer.pages.map { it.id })
		assertEquals("https://notion.so/page-root", answer.pages.first().url, "the link, so a choice is checkable")
	}

	@Test
	fun `leaves out the rows inside databases, which is every page Kanso itself writes`() {
		// Notion's `/search` filtered to pages answers rows too, and Kanso's own four
		// mirrored databases are full of them: every ticket, project, team and doc index
		// row is a page this token can see. None can hold a database, so none is a
		// candidate — and excluding them by shape needs no list of Kanso's own ids.
		val workspace = FakeNotionWorkspace(
			databases = listOf(FakeDatabase("Kanso · Tickets", pages = List(3) { fakePage("KAN-$it") })),
			shared = listOf(sharedPage("Kanso")),
		)

		val answer = NotionParentPages.list(workspace)

		assertEquals(listOf("Kanso"), answer.pages.map { it.title })
	}

	@Test
	fun `offers a page with no title, because an unpickable page is worse than an unnamed one`() {
		// Notion allows it, so the picker meets it. Dropping it would leave the page
		// somebody just shared missing from the list with no explanation.
		val workspace = FakeNotionWorkspace(shared = listOf(sharedPage(title = null, id = "page-2f1a4c99")))

		val answer = NotionParentPages.list(workspace)

		val page = assertNotNull(answer.pages.singleOrNull(), "the untitled page is still listable")
		assertEquals("page-2f1a4c99", page.id)
		assertNull(page.title, "and the wire says there is no title rather than inventing one")
	}

	@Test
	fun `leaves out a page in Notion's trash`() {
		// Creating a database under a trashed page fails at bootstrap — the exact
		// late failure the picker exists to remove.
		val workspace = FakeNotionWorkspace(
			shared = listOf(sharedPage("Kanso"), sharedPage("Old plans", archived = true)),
		)

		assertEquals(listOf("Kanso"), NotionParentPages.list(workspace).pages.map { it.title })
	}

	@Test
	fun `walks past the first page of results rather than stopping at the cursor`() {
		val workspace = FakeNotionWorkspace(shared = List(5) { sharedPage("Page $it") })

		val answer = NotionParentPages.list(workspace, pageSize = 2)

		assertEquals(5, answer.pages.size, "a picker that shows two of five pages cannot offer the third")
	}

	@Test
	fun `stops at its own ceiling instead of walking a whole workspace`() {
		val workspace = FakeNotionWorkspace(shared = List(50) { sharedPage("Page $it") })

		val answer = NotionParentPages.list(workspace, pageSize = 2, max = 3)

		assertEquals(3, answer.pages.size)
		assertTrue(answer.available)
	}

	@Test
	fun `says why there is nothing to pick when no token is configured`() {
		val answer = NotionParentPages.list(NoopNotionClient())

		assertFalse(answer.available)
		val reason = assertNotNull(answer.reason, "an unavailable workspace owes the reader a sentence")
		assertContains(reason, "token", message = "and the sentence names the thing to go and do: $reason")
		assertEquals(emptyList(), answer.pages, "no empty picker pretending the workspace holds no pages")
	}

	@Test
	fun `an integration with nothing shared is available and empty, not unavailable`() {
		// The two states have to stay apart on the wire: "no token" is a setup step, while
		// "a token and no shared page" is the silent failure of the old flow — the screen
		// has a different, actionable sentence for it and cannot pick one without this.
		val answer = NotionParentPages.list(FakeNotionWorkspace())

		assertTrue(answer.available)
		assertNull(answer.reason)
		assertEquals(emptyList(), answer.pages)
	}

	@Test
	fun `reports rate limiting as a sentence with a delay in it, not as a failure`() {
		val answer = NotionParentPages.list(RateLimitedWorkspace)

		assertFalse(answer.available)
		assertContains(assertNotNull(answer.reason), "12", message = "the wait is the only useful part")
	}

	/** A workspace that answers 429 to a search. Delegation, so only the one call differs. */
	private object RateLimitedWorkspace : NotionClient by FakeNotionWorkspace() {
		override suspend fun searchPages(startCursor: String?, pageSize: Int): NotionPageSearch =
			throw NotionRateLimited(Duration.ofSeconds(12))
	}
}

package dev.kanso.sync.importer

import dev.kanso.config.KansoProperties
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionPage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Everything the import reads, and nothing it writes.
 *
 * The mirror is told which four data sources are its own and never asks; the import has
 * to ask the workspace what it holds. That is the only direction that is new, and it is
 * kept in its own class because it is also the only part that talks to Notion — the
 * service above it works from what this returned, which is what lets a preview be
 * proved to write nothing.
 */
@Component
class NotionDiscovery(private val props: KansoProperties, private val client: NotionClient) {

	private val log = LoggerFactory.getLogger(javaClass)

	private val limits get() = props.notion.import

	/** True when there is a token; false means the dialog gets a sentence, not a spinner. */
	val enabled: Boolean get() = client.enabled

	/**
	 * Every base the token can see, minus [excluded].
	 *
	 * [excluded] is how the import stays out of the mirror's way: Kanso's own four
	 * databases are visible to the same token, and offering to import `Kanso · Tickets`
	 * back into Kanso would duplicate every ticket in the instance.
	 */
	suspend fun search(excluded: Set<String>): SearchResult {
		var cursor: String? = null
		val found = mutableListOf<WorkspaceBase>()

		do {
			val page = client.searchDatabases(cursor, limits.pageSize)
			page.unavailable?.let { return SearchResult(emptyList(), unavailable = it) }
			for (database in page.databases) {
				val dataSourceId = database.dataSourceIds.firstOrNull() ?: continue
				if (database.id in excluded || dataSourceId in excluded) continue
				found += WorkspaceBase(dataSourceId, database.id, database.title?.trim().orEmpty().ifEmpty { "Untitled" })
				if (found.size >= limits.maxDatabases) {
					log.info("Workspace search stopped at {} databases", limits.maxDatabases)
					return SearchResult(found)
				}
			}
			cursor = page.nextCursor
		} while (page.hasMore && cursor != null)

		return SearchResult(found)
	}

	/**
	 * Counts a base's pages without holding on to them.
	 *
	 * Screen 24 shows a count before anything is mapped, and the pages themselves are
	 * megabytes of json the first step has no use for. Bounded by
	 * [KansoProperties.Notion.Import.maxPagesPerDatabase]: past that the count is
	 * reported as not exact rather than the request running for a minute.
	 */
	suspend fun count(dataSourceId: String): PageCount {
		var counted = 0
		val exact = walk(dataSourceId) { counted++ }
		return PageCount(counted, exact)
	}

	/** The pages themselves, for the two steps that need to look inside them. */
	suspend fun pages(dataSourceId: String): List<NotionPage> {
		val collected = mutableListOf<NotionPage>()
		walk(dataSourceId) { collected += it }
		return collected
	}

	/**
	 * Walks one data source, ascending, and returns whether it reached the end.
	 *
	 * Archived pages are left out on both counts: Notion's trash is not content, and a
	 * count that included it would put a number on screen the import would not match.
	 */
	private suspend inline fun walk(dataSourceId: String, onPage: (NotionPage) -> Unit): Boolean {
		var cursor: String? = null
		var seen = 0

		do {
			val page = client.queryDataSource(
				dataSourceId = dataSourceId,
				editedOnOrAfter = null,
				startCursor = cursor,
				pageSize = limits.pageSize,
				includeArchived = false,
			)
			for (notionPage in page.pages) {
				if (notionPage.archived) continue
				onPage(notionPage)
				seen++
				if (seen >= limits.maxPagesPerDatabase) return false
			}
			cursor = page.nextCursor
		} while (page.hasMore && cursor != null)

		return true
	}
}

/** A base as discovery found it: the id that can be queried, and the one the mirror knows. */
data class WorkspaceBase(val dataSourceId: String, val databaseId: String, val name: String)

/** [unavailable] non-null means no search happened; the sentence says why. */
data class SearchResult(val bases: List<WorkspaceBase>, val unavailable: String? = null)

data class PageCount(val pages: Int, val exact: Boolean)

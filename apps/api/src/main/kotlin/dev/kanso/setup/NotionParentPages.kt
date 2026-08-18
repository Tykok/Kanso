package dev.kanso.setup

import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionPageRef
import dev.kanso.sync.notion.NotionRateLimited
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** One page the parent-page picker can offer. [title] is null when the page has none. */
data class ParentPageOption(val id: String, val title: String?, val url: String?)

/**
 * What the picker gets back.
 *
 * Three states in one shape, and the screen says something different about each:
 * [available] false with a [reason] is "there is no workspace to search yet"; available
 * with an empty [pages] is "the integration exists and no page is shared with it", which
 * is the silent failure the pasted id used to hide; available with pages is the list.
 */
data class ParentPageOptions(
	val available: Boolean,
	val reason: String?,
	val pages: List<ParentPageOption>,
)

/**
 * The pages Kanso may create its four databases under.
 *
 * Replaces "32 hex characters from the page URL". The id was never the hard part — the
 * hard part was that an id for a page the integration cannot see is indistinguishable
 * from a good one until bootstrap fails, three screens later. A search answers only pages
 * the integration *can* see, so a choice made from this list is a choice that works.
 *
 * An object with no state and no bean: it holds a [NotionClient] for the length of one
 * call, because the caller's client is built from the token that request carried.
 */
object NotionParentPages {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Walks the search until the pages run out or [max] is reached.
	 *
	 * [max] is a ceiling rather than configuration: a picker holding hundreds of entries
	 * is a different interface from this one, and stopping is the honest end of a list
	 * somebody scrolls — nothing here is a count anyone relies on.
	 */
	fun list(client: NotionClient, pageSize: Int = PAGE_SIZE, max: Int = MAX_PAGES): ParentPageOptions {
		val found = mutableListOf<ParentPageOption>()
		return try {
			runBlocking {
				var cursor: String? = null
				do {
					val answer = client.searchPages(cursor, pageSize)
					answer.unavailable?.let { return@runBlocking unavailable(it) }
					for (page in answer.pages) {
						if (!eligible(page)) continue
						found += ParentPageOption(page.id, page.title, page.url)
						if (found.size >= max) {
							log.info("Parent page search stopped at {} pages", max)
							return@runBlocking ParentPageOptions(true, null, found)
						}
					}
					cursor = answer.nextCursor
				} while (answer.hasMore && cursor != null)

				ParentPageOptions(available = true, reason = null, pages = found)
			}
		} catch (e: NotionRateLimited) {
			// Not a 500: the workspace is readable, just not this second, and "try again in
			// twelve seconds" is a sentence somebody can act on.
			unavailable("Notion is rate-limiting this integration. Try again in ${e.retryAfter.toSeconds()}s.")
		} catch (e: NotionApiException) {
			unavailable("Notion refused the request: ${e.message}")
		}
	}

	/**
	 * Whether a page can hold Kanso's four databases.
	 *
	 * A search filtered to pages answers database *rows* as well — and Kanso's own
	 * mirrored ticket, project, team and doc-index pages are all rows, so left alone this
	 * list would be mostly Kanso's own output. They are excluded by shape rather than by
	 * id: nothing can be created under a row, every page Kanso writes is one, and a
	 * structural rule needs no list of Kanso's own ids to stay correct. The rule excludes
	 * the two container parents rather than allowing a set, so a page nested in a column
	 * or a toggle — parent `block_id` — is still offered; being listable is the property
	 * worth erring towards, since the whole point is to offer the page somebody shared.
	 */
	private fun eligible(page: NotionPageRef): Boolean =
		!page.archived && page.parentType !in ROW_PARENTS

	private fun unavailable(reason: String): ParentPageOptions {
		log.info("Notion parent page search unavailable: {}", reason)
		return ParentPageOptions(available = false, reason = reason, pages = emptyList())
	}

	/** A page with one of these parents is a row of a database. */
	private val ROW_PARENTS = setOf("database_id", "data_source_id")

	private const val PAGE_SIZE = 100
	private const val MAX_PAGES = 200
}

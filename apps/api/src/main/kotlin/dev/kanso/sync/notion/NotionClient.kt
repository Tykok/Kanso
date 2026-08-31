package dev.kanso.sync.notion

import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.OffsetDateTime

data class NotionDatabase(
	val id: String,
	/** 2025-09-03 nests data sources under a database; queries target these. */
	val dataSourceIds: List<String>,
	val title: String?,
)

data class NotionPage(
	val id: String,
	val lastEditedTime: OffsetDateTime?,
	/** Who touched it last. Equal to our own bot id when the edit was our push. */
	val lastEditedById: String?,
	val archived: Boolean,
	val properties: JsonNode?,
	val url: String?,
)

data class NotionQueryPage(
	val pages: List<NotionPage>,
	val nextCursor: String?,
	val hasMore: Boolean,
)

/**
 * A data source's own schema, as opposed to any row in it.
 *
 * The import needs this and the pages [NotionClient.queryDataSource] returns are not
 * enough: a column left empty on every page is invisible in the pages and present here,
 * a select's options have to be listed even when no page uses one, and a relation's
 * target data source exists nowhere else at all.
 */
data class NotionDataSource(val id: String, val name: String, val properties: JsonNode?)

/**
 * One page of a workspace search.
 *
 * [unavailable] is a first-class answer rather than an exception: an instance with no
 * token has no workspace to search, and screen 24's first step is drawn to print that
 * sentence instead of an empty table. A client that *could* search and failed throws;
 * a client that never could says so here.
 */
data class NotionWorkspaceSearch(
	val databases: List<NotionDatabase>,
	val nextCursor: String?,
	val hasMore: Boolean,
	val unavailable: String? = null,
)

/**
 * A page the integration can see, as a chooser needs it.
 *
 * Not [NotionPage]: that one is a row of a mirrored database and carries what the poller
 * reconciles with — the properties blob, the last editor, the archive flags. What names a
 * page in a list is a title and a link, and what decides whether it can *hold* Kanso's
 * four databases is [parentType].
 */
data class NotionPageRef(
	val id: String,
	/** Null when the page has no title. Notion allows it; naming it is the caller's problem. */
	val title: String?,
	val url: String?,
	/**
	 * Where the page sits: `workspace`, `page_id`, `block_id`, `database_id` or
	 * `data_source_id`. A search filtered to pages answers database *rows* too — every
	 * mirrored ticket Kanso ever pushed is one — and only this tells them apart.
	 */
	val parentType: String?,
	val archived: Boolean = false,
)

/** One page of a page search. [unavailable] means the same thing it does above. */
data class NotionPageSearch(
	val pages: List<NotionPageRef>,
	val nextCursor: String?,
	val hasMore: Boolean,
	val unavailable: String? = null,
)

/**
 * A workspace member, as [NotionPeople] needs one to match it against a Kanso account.
 *
 * Not every member is a person — a bot integration shows up in the same list — which
 * is why [NotionClient.listUsers] keeps only `type == "person"` entries; a bot has no
 * [email] and nothing in this file would ever match one to an account by name either.
 */
data class NotionMember(val id: String, val name: String?, val email: String?)

/** 429. [retryAfter] comes from the header when Notion sends one. */
class NotionRateLimited(val retryAfter: Duration) :
	RuntimeException("Notion rate limit hit, retry after ${retryAfter.toSeconds()}s")

class NotionApiException(
	val status: Int,
	val code: String?,
	message: String,
) : RuntimeException("Notion API $status${code?.let { " ($it)" } ?: ""}: $message") {

	/**
	 * 4xx means the request itself is wrong and will stay wrong — retrying only
	 * burns rate limit. 5xx and transport failures are worth another go.
	 */
	val retryable: Boolean get() = status >= 500 || status == 409 || status == 0
}

/**
 * Everything Kanso needs from Notion, and nothing else.
 *
 * Kept narrow on purpose: the mirror only ever creates and overwrites pages from
 * the Postgres state, so there is no partial-update or block-content surface to
 * maintain here.
 */
interface NotionClient {

	/** False when no token is configured; callers skip the network entirely. */
	val enabled: Boolean

	/**
	 * The integration's own user id. The inbound poller compares it against a
	 * page's `last_edited_by` to recognise the echo of its own writes.
	 */
	suspend fun botUserId(): String?

	/**
	 * Every database the token can see, one page of results at a time.
	 *
	 * The only call in here that does not already know what it is looking for: the
	 * mirror is told its four data sources by [dev.kanso.repo.NotionMetaRepository],
	 * while the import has to ask the workspace what it holds. Paginated because a
	 * workspace can hold hundreds, and cursor-driven rather than "give me everything"
	 * so the caller decides how much of one it is willing to wait for.
	 */
	suspend fun searchDatabases(startCursor: String? = null, pageSize: Int = 100): NotionWorkspaceSearch

	/**
	 * Every page the token can see, one page of results at a time.
	 *
	 * The same `POST /search`, filtered to pages instead of containers, and the answer is
	 * exactly the set a person picked on Notion's own consent screen — which is what lets
	 * the parent page be chosen from a list rather than typed as 32 hex characters. No
	 * spelling fallback here, unlike [searchDatabases]: `data_source` is a 2025-09-03
	 * rename of `database`, while a page has always been `page`.
	 */
	suspend fun searchPages(startCursor: String? = null, pageSize: Int = 100): NotionPageSearch

	/**
	 * Every member of the workspace, walked to the end rather than one page at a time
	 * like [searchDatabases] and [searchPages]: a member list is invited people, not
	 * database rows, so it stays small enough that the caller never needs to see a
	 * cursor of its own.
	 *
	 * `GET /users` needs a capability ("read user information") that is off by
	 * default on a Notion integration, so a 403 here is an expected answer rather
	 * than a bug — it arrives as [NotionApiException], unswallowed, for [NotionPeople]
	 * to turn into a sentence naming the box to tick.
	 */
	suspend fun listUsers(): List<NotionMember>

	suspend fun createDatabase(
		parentPageId: String,
		title: String,
		properties: Map<String, Any?>,
	): NotionDatabase

	suspend fun retrieveDatabase(databaseId: String): NotionDatabase?

	/**
	 * A data source's own schema.
	 *
	 * The import needs this and the pages are not enough: a column empty on every page read
	 * is invisible in the pages and present here, a select's options must be listed even
	 * when no page uses them, and a relation's target data source exists nowhere else.
	 */
	suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource?

	/** Adds or changes properties on an existing data source — used to wire relations after creation. */
	suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>)

	suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage

	suspend fun updatePage(
		pageId: String,
		properties: Map<String, Any?>? = null,
		archived: Boolean? = null,
	): NotionPage

	suspend fun retrievePage(pageId: String): NotionPage?

	suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	): NotionQueryPage
}

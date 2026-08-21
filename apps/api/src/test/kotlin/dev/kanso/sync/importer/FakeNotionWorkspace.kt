package dev.kanso.sync.importer

import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDatabase
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionPageRef
import dev.kanso.sync.notion.NotionPageSearch
import dev.kanso.sync.notion.NotionQueryPage
import dev.kanso.sync.notion.NotionWorkspaceSearch
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A Notion workspace with things in it, for tests that cannot reach Notion.
 *
 * The existing seams are the two ends of the range and neither is enough here:
 * [dev.kanso.sync.notion.NoopNotionClient] has no workspace at all, and the poller
 * tests' `OnePageClient` answers one page for whatever is asked. The import reads
 * *across* databases — which page belongs to which base decides whether a relation
 * becomes a dependency — so it needs a client that knows the difference, and one that
 * paginates, because the discovery walk is the part most likely to be wrong.
 *
 * Both cursors are real: databases come back in pages of [searchDatabases]' `pageSize`
 * and pages in `queryDataSource`'s, so a caller that ignores `hasMore` sees a short
 * workspace rather than a passing test. [requests] counts what was asked, which is how
 * a test pins that counting a base does not cost one request per page of one.
 */
class FakeNotionWorkspace(
	private val databases: List<FakeDatabase> = emptyList(),
	/**
	 * Pages that are not rows: what somebody shares with an integration from a page's
	 * `•••` menu, and what the parent-page picker chooses among. Given rather than derived
	 * because *where a page sits* is the thing under test there — a page under the
	 * workspace root can hold Kanso's four databases and a row inside one cannot.
	 */
	private val shared: List<NotionPageRef> = emptyList(),
) : NotionClient {

	var requests: Int = 0
		private set

	override val enabled = true

	override suspend fun botUserId(): String = "fake-bot"

	override suspend fun searchDatabases(startCursor: String?, pageSize: Int): NotionWorkspaceSearch {
		requests++
		val from = startCursor?.toInt() ?: 0
		val slice = databases.drop(from).take(pageSize)
		val next = from + slice.size
		return NotionWorkspaceSearch(
			databases = slice.map { NotionDatabase(it.databaseId, listOf(it.dataSourceId), it.name) },
			nextCursor = next.toString().takeIf { next < databases.size },
			hasMore = next < databases.size,
		)
	}

	/**
	 * What `POST /search` filtered to pages answers: the shared pages *and* every row of
	 * every database, because that is what Notion returns and a caller that forgot to tell
	 * them apart would offer somebody's ticket as the place to create Kanso's databases.
	 */
	override suspend fun searchPages(startCursor: String?, pageSize: Int): NotionPageSearch {
		requests++
		val all = shared + databases.flatMap { database -> database.pages.map(::row) }
		val from = startCursor?.toInt() ?: 0
		val slice = all.drop(from).take(pageSize)
		val next = from + slice.size
		return NotionPageSearch(
			pages = slice,
			nextCursor = next.toString().takeIf { next < all.size },
			hasMore = next < all.size,
		)
	}

	/** A database row as search answers it: parented by a data source, titled by its own property. */
	private fun row(page: NotionPage) = NotionPageRef(
		id = page.id,
		// An empty mapping still finds the title: it is the one thing found by type.
		title = MappedPageReader(ColumnMapping()).title(page),
		url = page.url,
		parentType = "data_source_id",
		archived = page.archived,
	)

	override suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	): NotionQueryPage {
		requests++
		val all = databases.firstOrNull { it.dataSourceId == dataSourceId }?.pages.orEmpty()
			.filter { includeArchived || !it.archived }
		val from = startCursor?.toInt() ?: 0
		val slice = all.drop(from).take(pageSize)
		val next = from + slice.size
		return NotionQueryPage(
			pages = slice,
			nextCursor = next.toString().takeIf { next < all.size },
			hasMore = next < all.size,
		)
	}

	// Nothing here writes to Notion: the import creates rows in Postgres and lets the
	// outbox mirror them, so a fake that answered these would be answering calls the
	// code under test must never make.
	override suspend fun createDatabase(parentPageId: String, title: String, properties: Map<String, Any?>): NotionDatabase =
		throw UnsupportedOperationException()
	override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = throw UnsupportedOperationException()
	override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
		throw UnsupportedOperationException()
	override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
		throw UnsupportedOperationException()
	override suspend fun updatePage(pageId: String, properties: Map<String, Any?>?, archived: Boolean?): NotionPage =
		throw UnsupportedOperationException()
	override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
}

/** One base in the fake workspace. Ids are given so a relation can name a page. */
class FakeDatabase(
	val name: String,
	val pages: List<NotionPage> = emptyList(),
	val databaseId: String = "db-${UUID.randomUUID()}",
	val dataSourceId: String = "ds-${UUID.randomUUID()}",
)

/**
 * A page shared with the integration — the shape the parent-page picker chooses among.
 *
 * `title = null` is a real Notion page: a page created and never named. It is a case with
 * its own test, so the helper has to be able to make one.
 */
fun sharedPage(
	title: String? = "A page",
	id: String = "page-${UUID.randomUUID()}",
	url: String? = "https://notion.so/${id.removePrefix("page-")}",
	parentType: String = "workspace",
	archived: Boolean = false,
): NotionPageRef = NotionPageRef(id = id, title = title, url = url, parentType = parentType, archived = archived)

private val json = ObjectMapper()

/**
 * A page with a title property and whatever else it was given.
 *
 * The property map is Notion's own shape — `{"Status": {"select": {"name": "Done"}}}` —
 * rather than something flattened, because the reader under test is the thing that has
 * to understand that shape, and a helper that hid it would test the helper.
 */
fun fakePage(
	title: String,
	properties: Map<String, Any?> = emptyMap(),
	id: String = "page-${UUID.randomUUID()}",
	archived: Boolean = false,
	titleProperty: String = "Name",
): NotionPage = NotionPage(
	id = id,
	lastEditedTime = OffsetDateTime.now(),
	lastEditedById = "a-human",
	archived = archived,
	properties = json.valueToTree(
		buildMap {
			put(titleProperty, mapOf("type" to "title", "title" to listOf(mapOf("plain_text" to title))))
			putAll(properties)
		}
	),
	url = "https://notion.so/${id.removePrefix("page-")}",
)

fun notionSelect(value: String): Map<String, Any?> = mapOf("type" to "select", "select" to mapOf("name" to value))

fun notionText(value: String): Map<String, Any?> =
	mapOf("type" to "rich_text", "rich_text" to listOf(mapOf("plain_text" to value)))

fun notionDate(value: String): Map<String, Any?> = mapOf("type" to "date", "date" to mapOf("start" to value))

fun notionNumber(value: Number): Map<String, Any?> = mapOf("type" to "number", "number" to value)

fun notionRelation(vararg pageIds: String): Map<String, Any?> =
	mapOf("type" to "relation", "relation" to pageIds.map { mapOf("id" to it) })

fun notionPeople(vararg people: Pair<String, String?>): Map<String, Any?> = mapOf(
	"type" to "people",
	"people" to people.map { (id, name) -> mapOf("object" to "user", "id" to id, "name" to name) },
)

/**
 * A base as the plan resolves it: what it becomes, and which of its columns answer which
 * field. The mapping is the request's own answer, so a test that cares about a link or a
 * status has to give one.
 */
fun planned(
	base: FakeDatabase,
	target: ImportTarget,
	mapping: ColumnMapping = ColumnMapping(),
): PlannedBase = PlannedBase(
	base = WorkspaceBase(base.dataSourceId, base.databaseId, base.name),
	target = target,
	pages = base.pages,
	mapping = mapping,
)

/** A page with no title at all — the shape Kanso cannot adopt. */
fun untitledPage(id: String = "page-${UUID.randomUUID()}"): NotionPage = NotionPage(
	id = id,
	lastEditedTime = OffsetDateTime.now(),
	lastEditedById = "a-human",
	archived = false,
	properties = json.valueToTree(mapOf("Name" to mapOf("type" to "title", "title" to emptyList<Any>()))),
	url = null,
)

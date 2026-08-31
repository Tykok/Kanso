package dev.kanso.sync.notion

import dev.kanso.config.KansoProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The real client.
 *
 * Every call goes through [RateLimiter] first, so the ~3 req/s ceiling is honoured
 * across the whole application rather than per call site. A 429 both raises
 * [NotionRateLimited] for the caller's backoff and penalises the shared limiter,
 * because the limit is per integration, not per request.
 */
class HttpNotionClient(
	private val props: KansoProperties.Notion,
	private val objectMapper: ObjectMapper,
	private val rateLimiter: RateLimiter,
) : NotionClient {

	private val log = LoggerFactory.getLogger(javaClass)

	private val http: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	override val enabled: Boolean = true

	@Volatile
	private var cachedBotUserId: String? = null

	/** The `object` filter this workspace accepted, remembered so later pages don't re-probe. */
	@Volatile
	private var searchFilter: String? = null

	override suspend fun botUserId(): String? {
		cachedBotUserId?.let { return it }
		val body = request("GET", "/users/me", null) ?: return null
		val id = body.path("bot").path("owner").path("user").path("id").asText(null)
			?: body.path("id").asText(null)
		cachedBotUserId = id
		return id
	}

	/**
	 * `POST /search`, filtered to the containers a page can live in.
	 *
	 * Which word names those containers depends on the API version — 2025-09-03 moved
	 * queryable schemas from `database` to `data_source` and search follows it — and the
	 * published reference documents both spellings. So the same fallback the bootstrap
	 * uses for relation targets is used here: try the newer filter, drop to the older one
	 * on a validation error, then remember which the workspace accepted. Results are
	 * classified by their own `object` field either way, so a workspace answering a mix
	 * of the two loses nothing.
	 */
	override suspend fun searchDatabases(startCursor: String?, pageSize: Int): NotionWorkspaceSearch {
		val filters = searchFilter?.let { listOf(it) } ?: SEARCH_FILTERS
		var lastError: NotionApiException? = null

		for (filter in filters) {
			val payload = buildMap<String, Any?> {
				put("filter", mapOf("property" to "object", "value" to filter))
				put("page_size", pageSize)
				startCursor?.let { put("start_cursor", it) }
			}
			val body = try {
				request("POST", "/search", payload)
			} catch (e: NotionApiException) {
				// Only a rejected body is worth retrying with the other spelling; auth and
				// server errors mean something else is wrong and retrying burns rate limit.
				if (e.status != 400) throw e
				log.debug("Search filter '{}' rejected: {}", filter, e.message)
				lastError = e
				continue
			} ?: return NotionWorkspaceSearch(emptyList(), null, false)

			if (searchFilter != filter) {
				log.info("Notion search accepts object filter '{}'", filter)
				searchFilter = filter
			}
			return NotionWorkspaceSearch(
				databases = body.path("results").mapNotNull(::workspaceDatabase),
				nextCursor = body.path("next_cursor").asText(null),
				hasMore = body.path("has_more").asBoolean(false),
			)
		}
		throw lastError ?: NotionApiException(400, "search", "Notion accepted no object filter")
	}

	/**
	 * `POST /search`, filtered to pages.
	 *
	 * One filter, no fallback: the two spellings [searchDatabases] tries are the
	 * 2025-09-03 rename of a *container*, and a page has been `page` in every version.
	 * Copying the fallback here would spend a second request to be told the same thing.
	 */
	override suspend fun searchPages(startCursor: String?, pageSize: Int): NotionPageSearch {
		val payload = buildMap<String, Any?> {
			put("filter", mapOf("property" to "object", "value" to "page"))
			put("page_size", pageSize)
			startCursor?.let { put("start_cursor", it) }
		}
		val body = request("POST", "/search", payload)
			?: return NotionPageSearch(emptyList(), null, false)
		return NotionPageSearch(
			pages = body.path("results")
				.filter { it.path("object").asText("") == "page" }
				.map(::pageRef),
			nextCursor = body.path("next_cursor").asText(null),
			hasMore = body.path("has_more").asBoolean(false),
		)
	}

	/**
	 * `GET /users`, cursor-driven like the two searches above but walked to
	 * completion here rather than left to the caller: [NotionPeople] needs the whole
	 * roster to match against, not a page of it, and a workspace's membership is
	 * small enough that this never costs more than a handful of requests.
	 *
	 * Only `type == "person"` survives — a bot integration is `type == "bot"` and
	 * carries no email — and `person.email` is read where it is there, which is not
	 * always: a guest can be invited without one.
	 */
	override suspend fun listUsers(): List<NotionMember> {
		val members = mutableListOf<NotionMember>()
		var cursor: String? = null
		while (true) {
			val query = buildString {
				append("?page_size=100")
				cursor?.let { append("&start_cursor=").append(URLEncoder.encode(it, Charsets.UTF_8)) }
			}
			val body = request("GET", "/users$query", null) ?: break
			body.path("results")
				.filter { it.path("type").asText("") == "person" }
				.mapTo(members, ::member)
			cursor = body.path("next_cursor").asText(null)
			if (cursor == null || !body.path("has_more").asBoolean(false)) break
		}
		return members
	}

	override suspend fun createDatabase(
		parentPageId: String,
		title: String,
		properties: Map<String, Any?>,
	): NotionDatabase {
		val payload = mapOf(
			"parent" to mapOf("type" to "page_id", "page_id" to parentPageId),
			"title" to listOf(NotionProps.textFragment(title)),
			// 2025-09-03: the schema of the first data source lives under this key
			// rather than at the top level.
			"initial_data_source" to mapOf("properties" to properties),
		)
		val body = requireNotNull(request("POST", "/databases", payload)) { "Empty response creating database" }
		return database(body)
	}

	override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? =
		request("GET", "/databases/$databaseId", null)?.let(::database)

	override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
		request("GET", "/data_sources/$dataSourceId", null)?.let {
			NotionDataSource(it.path("id").asText(dataSourceId), plainTitle(it.path("title")), it.path("properties"))
		}

	override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) {
		request("PATCH", "/data_sources/$dataSourceId", mapOf("properties" to properties))
	}

	override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage {
		val payload = mapOf(
			// 2025-09-03: pages hang off a data source, not a database.
			"parent" to mapOf("type" to "data_source_id", "data_source_id" to dataSourceId),
			"properties" to properties,
		)
		val body = requireNotNull(request("POST", "/pages", payload)) { "Empty response creating page" }
		return page(body)
	}

	override suspend fun updatePage(
		pageId: String,
		properties: Map<String, Any?>?,
		archived: Boolean?,
	): NotionPage {
		val payload = buildMap {
			properties?.let { put("properties", it) }
			archived?.let {
				// Notion has both spellings live; sending each keeps behaviour the
				// same whichever one the current version honours.
				put("archived", it)
				put("in_trash", it)
			}
		}
		val body = requireNotNull(request("PATCH", "/pages/$pageId", payload)) { "Empty response updating page" }
		return page(body)
	}

	override suspend fun retrievePage(pageId: String): NotionPage? =
		request("GET", "/pages/$pageId", null)?.let(::page)

	override suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	): NotionQueryPage {
		val payload = buildMap<String, Any?> {
			// Ascending, so a cursor advanced from the last row cannot skip a page
			// that was edited while we were paginating.
			put("sorts", listOf(mapOf("timestamp" to "last_edited_time", "direction" to "ascending")))
			put("page_size", pageSize)
			startCursor?.let { put("start_cursor", it) }
			if (includeArchived) put("is_archived", true)
			editedOnOrAfter?.let {
				put(
					"filter",
					mapOf(
						"timestamp" to "last_edited_time",
						"last_edited_time" to mapOf("on_or_after" to ISO.format(it)),
					),
				)
			}
		}
		val body = request("POST", "/data_sources/$dataSourceId/query", payload)
			?: return NotionQueryPage(emptyList(), null, false)
		val pages = body.path("results")
			.filter { it.path("object").asText("") == "page" }
			.map(::page)
		return NotionQueryPage(
			pages = pages,
			nextCursor = body.path("next_cursor").asText(null),
			hasMore = body.path("has_more").asBoolean(false),
		)
	}

	// --- transport -----------------------------------------------------------

	private suspend fun request(method: String, path: String, body: Any?): JsonNode? {
		rateLimiter.acquire()

		val builder = HttpRequest.newBuilder(URI.create(props.baseUrl.trimEnd('/') + path))
			.timeout(props.requestTimeout)
			.header("Authorization", "Bearer ${props.token}")
			.header("Notion-Version", props.apiVersion)
			.header("Content-Type", "application/json")

		val publisher = if (body == null) {
			HttpRequest.BodyPublishers.noBody()
		} else {
			HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))
		}
		val request = builder.method(method, publisher).build()

		val response = try {
			withContext(Dispatchers.IO) {
				http.send(request, HttpResponse.BodyHandlers.ofString())
			}
		} catch (e: Exception) {
			// Status 0: transport failure, which is exactly the retryable case.
			throw NotionApiException(0, "transport", e.message ?: e.javaClass.simpleName)
		}

		if (response.statusCode() == 429) {
			val retryAfter = response.headers().firstValue("Retry-After")
				.map { Duration.ofSeconds(it.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS) }
				.orElse(Duration.ofSeconds(DEFAULT_RETRY_AFTER_SECONDS))
			rateLimiter.penalise(retryAfter.toMillis())
			throw NotionRateLimited(retryAfter)
		}

		if (response.statusCode() == 404) return null

		if (response.statusCode() !in 200..299) {
			val parsed = runCatching { objectMapper.readTree(response.body()) }.getOrNull()
			throw NotionApiException(
				status = response.statusCode(),
				code = parsed?.path("code")?.asText(null),
				message = parsed?.path("message")?.asText(null) ?: response.body().take(500),
			)
		}

		log.debug("{} {} -> {}", method, path, response.statusCode())
		return objectMapper.readTree(response.body())
	}

	private fun database(body: JsonNode) = NotionDatabase(
		id = body.path("id").asText(),
		dataSourceIds = body.path("data_sources").mapNotNull { it.path("id").asText(null) },
		title = body.path("title").firstOrNull()?.path("plain_text")?.asText(null),
	)

	/**
	 * A search result, whichever of the two shapes it came back as.
	 *
	 * A `database` carries its data sources in an array; a `data_source` *is* one and
	 * names its database in `parent`. Anything else in the results — a page, a version
	 * that answers a third shape — is dropped rather than guessed at, which is why this
	 * returns null instead of an empty [NotionDatabase].
	 */
	private fun workspaceDatabase(body: JsonNode): NotionDatabase? {
		val id = body.path("id").asText(null) ?: return null
		val title = body.path("title").firstOrNull()?.path("plain_text")?.asText(null)
			?: body.path("name").asText(null)
		return when (body.path("object").asText("")) {
			"database" -> NotionDatabase(
				id = id,
				dataSourceIds = body.path("data_sources").mapNotNull { it.path("id").asText(null) },
				title = title,
			)

			"data_source" -> NotionDatabase(
				id = body.path("parent").path("database_id").asText(null) ?: id,
				dataSourceIds = listOf(id),
				title = title,
			)

			else -> null
		}
	}

	/**
	 * A page as a chooser sees it.
	 *
	 * The title is whichever property is of type `title` — its name is the database's to
	 * choose for a row, and `title` for a page that is not one — joined across its
	 * fragments, the same reading [dev.kanso.sync.importer.MappedPageReader] does of a row.
	 * Blank becomes null rather than "": an empty string in a list is a row that looks
	 * like a rendering bug, and the caller has a name for the case.
	 */
	private fun pageRef(body: JsonNode) = NotionPageRef(
		id = body.path("id").asText(),
		title = body.path("properties").properties()
			.map { it.value }
			.firstOrNull { it.has("title") }
			?.path("title")?.joinToString("") { it.path("plain_text").asText("") }
			?.takeIf { it.isNotBlank() },
		url = body.path("url").asText(null),
		parentType = body.path("parent").path("type").asText(null),
		archived = body.path("archived").asBoolean(false) || body.path("in_trash").asBoolean(false),
	)

	/**
	 * A title's fragments, joined the same way every other title read here is. [NotionDataSource.name]
	 * is non-nullable, unlike [NotionDatabase.title] — a schema screen has nothing sensible
	 * to print for a base with no name at all, so blank becomes "Untitled" rather than null.
	 */
	private fun plainTitle(title: JsonNode): String =
		title.joinToString("") { it.path("plain_text").asText("") }.ifBlank { "Untitled" }

	private fun member(body: JsonNode) = NotionMember(
		id = body.path("id").asText(),
		name = body.path("name").asText(null),
		email = body.path("person").path("email").asText(null),
	)

	private fun page(body: JsonNode) = NotionPage(
		id = body.path("id").asText(),
		lastEditedTime = body.path("last_edited_time").asText(null)?.let { OffsetDateTime.parse(it) },
		lastEditedById = body.path("last_edited_by").path("id").asText(null),
		// Either flag means "not in the active database" as far as Kanso cares.
		archived = body.path("archived").asBoolean(false) || body.path("in_trash").asBoolean(false),
		properties = body.path("properties").takeIf { !it.isMissingNode },
		url = body.path("url").asText(null),
	)

	private companion object {
		/** Newer spelling first; see [searchDatabases]. */
		val SEARCH_FILTERS = listOf("data_source", "database")
		const val DEFAULT_RETRY_AFTER_SECONDS = 5L
		val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
	}
}

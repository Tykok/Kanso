package dev.kanso.sync.notion

import dev.kanso.config.KansoProperties
import dev.kanso.settings.InstanceSettingsService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The [NotionClient] every call site holds.
 *
 * A token can appear, change or disappear while the application runs — the setup
 * wizard writes it — so the real implementation is chosen on first use and rebuilt
 * on [reload] instead of being decided once at startup. The worker, the poller and
 * the bootstrap keep injecting [NotionClient] and never learn about any of this.
 *
 * The [RateLimiter] is handed in and reused across rebuilds on purpose: Notion's
 * ceiling is per integration, so a rebuilt client starting with a fresh budget
 * would briefly double the request rate against the same limit.
 */
class ReloadableNotionClient(
	private val props: KansoProperties,
	private val settings: InstanceSettingsService,
	private val objectMapper: ObjectMapper,
	private val rateLimiter: RateLimiter,
) : NotionClient {

	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile
	private var delegate: NotionClient? = null

	/** Rebuilds from the current settings. Called after the wizard saves a token. */
	fun reload() {
		delegate = build()
	}

	/**
	 * Resolved at startup rather than on the first sync poll, so the log says
	 * whether the mirror is on before anyone has to guess from its silence.
	 */
	@EventListener(ApplicationReadyEvent::class)
	fun logMirrorState() {
		active()
	}

	override val enabled: Boolean get() = active().enabled

	override suspend fun botUserId(): String? = active().botUserId()

	override suspend fun searchDatabases(startCursor: String?, pageSize: Int): NotionWorkspaceSearch =
		active().searchDatabases(startCursor, pageSize)

	override suspend fun createDatabase(
		parentPageId: String,
		title: String,
		properties: Map<String, Any?>,
	): NotionDatabase = active().createDatabase(parentPageId, title, properties)

	override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? =
		active().retrieveDatabase(databaseId)

	override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
		active().updateDataSourceSchema(dataSourceId, properties)

	override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
		active().createPage(dataSourceId, properties)

	override suspend fun updatePage(
		pageId: String,
		properties: Map<String, Any?>?,
		archived: Boolean?,
	): NotionPage = active().updatePage(pageId, properties, archived)

	override suspend fun retrievePage(pageId: String): NotionPage? = active().retrievePage(pageId)

	override suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	): NotionQueryPage =
		active().queryDataSource(dataSourceId, editedOnOrAfter, startCursor, pageSize, includeArchived)

	/**
	 * Lazy: building at construction time would read the database while the context
	 * is still coming up, and there is nothing to push before then anyway.
	 */
	private fun active(): NotionClient = delegate ?: synchronized(this) {
		delegate ?: build().also { delegate = it }
	}

	private fun build(): NotionClient {
		val token = settings.notionToken()
		return if (token.isNullOrBlank()) {
			log.info(
				"No Notion token configured — the mirror is off. Sync jobs still run through the queue " +
					"against a no-op client."
			)
			NoopNotionClient()
		} else {
			log.info("Notion mirror enabled (API version {})", props.notion.apiVersion)
			HttpNotionClient(props.notion.copy(token = token), objectMapper, rateLimiter)
		}
	}
}

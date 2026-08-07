package dev.kanso.sync.notion

import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The client used when no Notion token is configured.
 *
 * It is not a silent stub: jobs are claimed, "pushed", and completed, so the
 * outbox, the throttle and the retry paths all get exercised in development and
 * in tests without a network or an account. Page ids are synthetic and marked as
 * such, which keeps them from ever being mistaken for real Notion ids.
 */
class NoopNotionClient : NotionClient {

	private val log = LoggerFactory.getLogger(javaClass)

	override val enabled: Boolean = false

	override suspend fun botUserId(): String? = null

	override suspend fun createDatabase(
		parentPageId: String,
		title: String,
		properties: Map<String, Any?>,
	): NotionDatabase {
		log.debug("[noop] would create database '{}' with {} properties", title, properties.size)
		return NotionDatabase(fakeId("db"), listOf(fakeId("ds")), title)
	}

	override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = null

	override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) {
		log.debug("[noop] would add {} properties to data source {}", properties.size, dataSourceId)
	}

	override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage {
		log.debug("[noop] would create page in {}", dataSourceId)
		return page(fakeId("page"))
	}

	override suspend fun updatePage(
		pageId: String,
		properties: Map<String, Any?>?,
		archived: Boolean?,
	): NotionPage {
		log.debug("[noop] would update page {} (archived={})", pageId, archived)
		return page(pageId, archived ?: false)
	}

	override suspend fun retrievePage(pageId: String): NotionPage? = null

	override suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	): NotionQueryPage = NotionQueryPage(emptyList(), null, false)

	private fun page(id: String, archived: Boolean = false) =
		NotionPage(id, OffsetDateTime.now(), null, archived, null, null)

	private fun fakeId(prefix: String) = "noop-$prefix-${UUID.randomUUID()}"
}

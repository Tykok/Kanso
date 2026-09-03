package dev.kanso.sync.inbound

import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDataSource
import dev.kanso.sync.notion.NotionDatabase
import dev.kanso.sync.notion.NotionMember
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionQueryPage
import java.time.OffsetDateTime

/**
 * A Notion workspace that can be read and cannot be written.
 *
 * `createPage` and `updatePage` throw rather than record, which is the point: "Kanso never
 * writes to a requests base" is a promise about code that does not exist, and the only way
 * to assert the absence of a call is to make the call fail. Every write in the interface
 * throws for the same reason, so the test does not have to guess which one a regression
 * would reach for.
 *
 * [pages] are answered for [dataSourceId] and for nothing else: a poll of the mirror's own
 * databases in the same run must not be handed a requests base's pages.
 */
class ReadOnlyClient(
	private val dataSourceId: String,
	private val pages: List<NotionPage>,
) : NotionClient {

	override val enabled = true

	/** A workspace bot that has edited nothing, which is true of a base Kanso never writes. */
	override suspend fun botUserId(): String = "kanso-bot"

	override suspend fun queryDataSource(
		dataSourceId: String,
		editedOnOrAfter: OffsetDateTime?,
		startCursor: String?,
		pageSize: Int,
		includeArchived: Boolean,
	) = NotionQueryPage(
		pages = if (dataSourceId == this.dataSourceId) pages else emptyList(),
		nextCursor = null,
		hasMore = false,
	)

	override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
		throw AssertionError("A requests base is read-only: nothing may create a page in $dataSourceId")

	override suspend fun updatePage(pageId: String, properties: Map<String, Any?>?, archived: Boolean?): NotionPage =
		throw AssertionError("A requests base is read-only: nothing may write page $pageId")

	override suspend fun createDatabase(
		parentPageId: String,
		title: String,
		properties: Map<String, Any?>,
	): NotionDatabase = throw AssertionError("Nothing here creates a database")

	override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
		throw AssertionError("Nothing here rewrites somebody's schema")

	override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = throw UnsupportedOperationException()
	override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
		throw UnsupportedOperationException()
	override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
	override suspend fun searchDatabases(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
	override suspend fun searchPages(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
	override suspend fun listUsers(): List<NotionMember> = throw UnsupportedOperationException()
}

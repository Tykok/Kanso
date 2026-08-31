package dev.kanso.sync.importer

import dev.kanso.service.BadRequestException
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDataSource
import dev.kanso.sync.notion.NotionRateLimited
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `NotionImportService.schema()` — the one path `ImportSchemaTest` cannot reach, because
 * that suite only exercises the pure `ImportSchema.of`. This is the `runBlocking` wrapper
 * around `NotionDiscovery.schema`, and it is worth its own test for the same reason
 * `NotionImportSourcesTest` exists for `sources()`: an unreachable workspace has to turn
 * into a sentence somebody can act on, not a 500, and nothing proved that until now.
 */
@Transactional
class NotionImportSchemaTest : ImportTestBase() {

	@Test
	fun `reads a base's schema and hands back its suggested mapping`() {
		val base = FakeDatabase(
			"Tasks",
			properties = mapOf(
				"Name" to mapOf("type" to "title"),
				"Status" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Done")))),
			),
		)

		val view = importerFor(base).schema(base.dataSourceId, ImportTarget.TICKETS)

		assertEquals("Status", view.suggestion.property(ImportField.STATUS))
	}

	@Test
	fun `refuses a source id the workspace no longer holds`() {
		val exception = assertFailsWith<BadRequestException> {
			importerFor(FakeNotionWorkspace()).schema("ds-gone", ImportTarget.TICKETS)
		}
		assertContains(exception.message.orEmpty(), "no longer has", message = "and names why: ${exception.message}")
	}

	@Test
	fun `turns a rate limit into the retry sentence, not a failure`() {
		val exception = assertFailsWith<BadRequestException> {
			importerFor(RateLimitedSchema).schema("ds-1", ImportTarget.TICKETS)
		}
		assertContains(exception.message.orEmpty(), "12s", message = "the wait is the useful part: ${exception.message}")
	}

	@Test
	fun `turns a Notion refusal into a sentence naming what Notion said`() {
		val exception = assertFailsWith<BadRequestException> {
			importerFor(RefusingSchema).schema("ds-1", ImportTarget.TICKETS)
		}
		assertContains(exception.message.orEmpty(), "not a real base", message = "Notion's own words: ${exception.message}")
	}

	/** A workspace that answers 429 to the one call [NotionImportService.schema] makes. */
	private object RateLimitedSchema : NotionClient by FakeNotionWorkspace() {
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
			throw NotionRateLimited(Duration.ofSeconds(12))
	}

	/** A workspace that answers a plain refusal — Notion rejected the request outright. */
	private object RefusingSchema : NotionClient by FakeNotionWorkspace() {
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
			throw NotionApiException(400, "validation_error", "not a real base")
	}
}

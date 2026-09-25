package dev.kanso.sync.notion

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The body Notion receives, rather than the map Kanso builds.
 *
 * `NotionMappingTest` pins `date(null)` as `{"date": null}`, and that map was right all
 * along — what reached Notion was `{}`, because the application's mapper runs with
 * `default-property-inclusion: non_null` and dropped the one null that meant "clear
 * this". Every project without a posed start date was refused with a 400 on its first
 * push. The e2e stub accepts any body, so only a test on the serialised string sees it.
 */
class NotionWireTest {

	private val context = ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
		// The value `application.yml` sets, so this reads the mapper the client is handed.
		.withPropertyValues("spring.jackson.default-property-inclusion=non_null")

	@Test
	fun `a cleared property reaches Notion as an explicit null`() {
		context.run { ctx ->
			val wire = notionWire(ctx.getBean(JsonMapper::class.java))
			val body = mapOf("Start" to NotionProps.date(null))

			assertEquals("""{"Start":{"date":null}}""", wire.writeValueAsString(body))
		}
	}
}

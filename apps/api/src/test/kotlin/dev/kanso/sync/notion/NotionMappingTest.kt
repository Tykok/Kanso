package dev.kanso.sync.notion

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotionMappingTest {

	@Suppress("UNCHECKED_CAST")
	private fun fragments(value: Map<String, Any?>, key: String) =
		value[key] as List<Map<String, Any>>

	@Test
	fun `a long description is split into fragments Notion accepts`() {
		val long = "x".repeat(4_500)
		val chunks = fragments(NotionProps.richText(long), "rich_text")

		assertEquals(3, chunks.size, "4500 characters should split into 2000 + 2000 + 500")
		assertTrue(chunks.all { (it["text"] as Map<*, *>)["content"].toString().length <= 2000 })
		val rejoined = chunks.joinToString("") { (it["text"] as Map<*, *>)["content"].toString() }
		assertEquals(long, rejoined, "splitting must not lose or reorder text")
	}

	@Test
	fun `an empty description clears the property instead of writing a blank fragment`() {
		assertTrue(fragments(NotionProps.richText(null), "rich_text").isEmpty())
		assertTrue(fragments(NotionProps.richText("   "), "rich_text").isEmpty())
	}

	@Test
	fun `a title is never empty because Notion shows an unnamed page as blank`() {
		val chunks = fragments(NotionProps.title("  "), "title")
		assertEquals("Untitled", (chunks.single()["text"] as Map<*, *>)["content"])
	}

	@Test
	fun `an absent date sends null so Notion clears the property`() {
		assertNull(NotionProps.date(null)["date"])
		val day = KansoInstant(LocalDate.of(2026, 8, 1).atStartOfDay().atOffset(ZoneOffset.UTC), false)
		assertEquals(mapOf("start" to "2026-08-01"), NotionProps.date(day)["date"])
	}

	@Test
	fun `a day is mirrored as a day, and a moment keeps its time`() {
		val moment = OffsetDateTime.of(2026, 8, 1, 14, 30, 0, 0, ZoneOffset.UTC)
		assertEquals(
			mapOf("start" to moment.toString()),
			NotionProps.date(KansoInstant(moment, true))["date"],
			"a value that names a moment must not be flattened to its day",
		)
		assertEquals(
			mapOf("start" to "2026-08-01"),
			NotionProps.date(KansoInstant(moment, false))["date"],
			"a floating day is written as a bare date, whatever the instant carries",
		)
	}

	@Test
	fun `people are deduplicated because Notion rejects a repeated id`() {
		@Suppress("UNCHECKED_CAST")
		val people = NotionProps.people(listOf("a", "b", "a"))["people"] as List<Map<String, Any>>
		assertEquals(listOf("a", "b"), people.map { it["id"] })
	}

	@Test
	fun `status labels round-trip through the mirror`() {
		for (status in DefaultStatus.entries) {
			assertEquals(status, DefaultStatus.fromLabel(status.label), "label '${status.label}' should map back")
		}
		for (priority in TicketPriority.entries) {
			assertEquals(priority, TicketPriority.fromLabel(priority.label))
		}
	}

	@Test
	fun `an unknown status coming back from Notion is refused rather than adopted`() {
		assertNull(DefaultStatus.fromLabel("Almost done"))
		val failure = runCatching { DefaultStatus.from("almost_done") }.exceptionOrNull()
		assertTrue(
			failure?.message?.contains("Unknown DefaultStatus") == true,
			"the error should name the offending value: ${failure?.message}",
		)
	}

	@Test
	fun `both relation dialects are expressible so the bootstrap can probe them`() {
		@Suppress("UNCHECKED_CAST")
		fun config(dialect: RelationDialect) =
			NotionSchema.relationTo("db-1", "ds-1", dialect)["relation"] as Map<String, Any>

		assertEquals("ds-1", config(RelationDialect.DATA_SOURCE)["data_source_id"])
		assertEquals("db-1", config(RelationDialect.DATABASE)["database_id"])
	}

	@Test
	fun `tickets carry two date properties rather than one ambiguous range`() {
		val schema = NotionSchema.tickets()
		assertTrue(NotionProps.START in schema, "a start date must survive the round trip on its own")
		assertTrue(NotionProps.DUE in schema, "a due date must survive the round trip on its own")
	}

	@Test
	fun `the dependency relation is not in the first pass because it points at its own database`() {
		assertTrue(
			NotionProps.BLOCKED_BY !in NotionSchema.tickets(),
			"a self-referencing relation needs the database to exist, so the bootstrap adds it in pass 2",
		)
	}
}

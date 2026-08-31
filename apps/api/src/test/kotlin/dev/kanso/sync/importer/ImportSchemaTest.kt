package dev.kanso.sync.importer

import dev.kanso.sync.notion.NotionDataSource
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema screen's reading: what a base's columns are, which fields each could fill,
 * and the mapping Kanso suggests before anyone touches it.
 *
 * Pure, unlike every other importer test in this package: [ImportSchema.of] never reads a
 * page or a repository, only the shape [NotionDataSource.properties] arrived with, so
 * there is nothing here that needs Postgres or a fake workspace.
 */
class ImportSchemaTest {

	private val json = ObjectMapper()

	private fun source(properties: Map<String, Any?>) = NotionDataSource(
		id = "ds-1", name = "Tasks", properties = json.valueToTree(properties),
	)

	@Test
	fun `a column is offered only for the fields its type can fill`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"État" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "En cours")))),
					"Échéance" to mapOf("type" to "date"),
					"Projet" to mapOf("type" to "relation", "relation" to mapOf("data_source_id" to "ds-2")),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals(listOf("État"), view.fields.single { it.field == ImportField.STATUS }.candidates)
		assertEquals(listOf("Échéance"), view.fields.single { it.field == ImportField.DUE }.candidates)
		assertEquals(listOf("Projet"), view.fields.single { it.field == ImportField.PROJECT }.candidates)
		assertEquals("ds-2", view.columns.single { it.name == "Projet" }.relationTo)
	}

	@Test
	fun `the title property is never offered — it is found by type, not chosen`() {
		val view = ImportSchema.of(source(mapOf("Name" to mapOf("type" to "title"))), ImportTarget.TICKETS)
		assertTrue(view.columns.none { it.type == "title" })
	}

	@Test
	fun `today's English names are suggested, and nothing else is guessed`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Status" to mapOf("type" to "status", "status" to mapOf("options" to listOf(mapOf("name" to "Done")))),
					"Etat" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Fini")))),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals("Status", view.suggestion.property(ImportField.STATUS))
		assertEquals(mapOf("Done" to "done"), view.suggestion.values[ImportField.STATUS])
	}

	@Test
	fun `an option outside the vocabulary is suggested as nothing, and the default is named`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Status" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Blocked")))),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals(emptyMap<String, String>(), view.suggestion.values[ImportField.STATUS])
		assertEquals("todo", view.defaults[ImportField.STATUS])
	}

	@Test
	fun `a relation column carries the data source it points at`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Team" to mapOf("type" to "relation", "relation" to mapOf("data_source_id" to "ds-team")),
				)
			),
			ImportTarget.PROJECTS,
		)

		assertEquals("ds-team", view.columns.single { it.name == "Team" }.relationTo)
		assertEquals(listOf("Team"), view.fields.single { it.field == ImportField.TEAM }.candidates)
	}

	@Test
	fun `two columns that could both fill a field are both offered, and the suggestion picks the one the mirror writes`() {
		val view = ImportSchema.of(
			source(
				mapOf(
					"Name" to mapOf("type" to "title"),
					"Etat" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Fini")))),
					"Status" to mapOf("type" to "select", "select" to mapOf("options" to listOf(mapOf("name" to "Done")))),
				)
			),
			ImportTarget.TICKETS,
		)

		assertEquals(
			setOf("Etat", "Status"),
			view.fields.single { it.field == ImportField.STATUS }.candidates.toSet(),
		)
		assertEquals("Status", view.suggestion.property(ImportField.STATUS))
	}
}

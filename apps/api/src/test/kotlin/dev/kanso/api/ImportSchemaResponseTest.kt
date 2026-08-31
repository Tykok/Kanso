package dev.kanso.api

import dev.kanso.sync.importer.ColumnMapping
import dev.kanso.sync.importer.FieldCandidates
import dev.kanso.sync.importer.ImportField
import dev.kanso.sync.importer.ImportSchemaView
import dev.kanso.sync.importer.ImportTarget
import dev.kanso.sync.importer.SchemaColumn
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The columns screen reads this response and sends the names in it straight back in
 * `ImportPlanRow.columns`, where [NotionImportController.entry] parses them through
 * `ImportField.from`. So the two directions have to agree on one vocabulary, and the only
 * way to see which one the response actually carries is to serialize it: Jackson writes an
 * enum as its own `name()`, and nothing in this application configures otherwise — which
 * is why returning `ImportSchemaView` raw put `"STATUS"` on a wire that only reads
 * `"status"` back.
 */
class ImportSchemaResponseTest {

	private val view = ImportSchemaView(
		sourceId = "ds-1",
		target = ImportTarget.TICKETS,
		columns = listOf(SchemaColumn("Etat", "select", listOf("En cours"), null)),
		fields = listOf(FieldCandidates(ImportField.STATUS, listOf("Etat"))),
		suggestion = ColumnMapping(
			columns = mapOf(ImportField.STATUS to "Etat"),
			values = mapOf(ImportField.STATUS to mapOf("En cours" to "in_progress")),
		),
		defaults = mapOf(ImportField.STATUS to "todo"),
	)

	@Test
	fun `the schema reaches the browser in the same words the request sends back`() {
		val json = JsonMapper.builder().build().writeValueAsString(ImportSchemaResponse.of(view))

		assertContains(json, """"target":"tickets"""")
		assertContains(json, """"field":"status"""")
		assertContains(json, """"columns":{"status":"Etat"}""")
		assertContains(json, """"values":{"status":{"En cours":"in_progress"}}""")
		// Only a field with a default value is asserted: this mapper is not the application's,
		// and `spring.jackson.default-property-inclusion: non_null` drops the null-valued ones
		// on the real wire — where the screen reads an absent default as no default, the same
		// as a null one.
		assertContains(json, """"defaults":{"status":"todo"}""")
		assertFalse(json.contains("STATUS"), "an enum name on the wire is a 400 when it comes back")
	}
}

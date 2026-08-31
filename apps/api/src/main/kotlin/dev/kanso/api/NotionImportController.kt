package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.sync.importer.ColumnMapping
import dev.kanso.sync.importer.Fallback
import dev.kanso.sync.importer.ImportField
import dev.kanso.sync.importer.ImportOutcome
import dev.kanso.sync.importer.ImportPlanEntry
import dev.kanso.sync.importer.ImportPreview
import dev.kanso.sync.importer.ImportSchemaView
import dev.kanso.sync.importer.ImportSources
import dev.kanso.sync.importer.ImportTarget
import dev.kanso.sync.importer.NotionImportService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One row of screen 24's mapping. An ignored base is absent, never `target: "ignore"`.
 *
 * [columns] says which of this base's Notion properties answers which [ImportField];
 * [values] says, per field, what each of that column's own options means in Kanso's
 * vocabulary. Both are keyed by the field's wire string here, same as everywhere else a
 * field crosses the wire, and [entry] turns both maps into [ColumnMapping] by parsing that
 * key through [ImportField.from]. [fallback] is read the same way it always was: a base
 * whose team cannot be resolved may want a different destination from its neighbour, and
 * screen 24 decides that per base.
 */
data class ImportPlanRow(
	val sourceId: String,
	val target: String,
	val columns: Map<String, String> = emptyMap(),
	val values: Map<String, Map<String, String>> = emptyMap(),
	val fallback: Fallback = Fallback(),
)

data class ImportPreviewRequest(val plan: List<ImportPlanRow> = emptyList())

/**
 * The import itself, and the one field the client had no way to send.
 *
 * [teamId] is where the ticket's team and its per-team number come from, but no longer
 * unconditionally: an import of teams alone has no destination to ask about, so it is
 * `null` unless something in [plan] needs one. `architecture.md` is explicit that a page
 * made by hand in Notion can supply neither a team nor a per-team number, which is why the
 * poller refuses to adopt one; an import is the case where somebody *is* present to say
 * whose work this is, so the mapping carries the answer and [NotionImportService] refuses
 * a team the actor may not write to — [teamId] or any row's own fallback — before anything
 * is written.
 */
data class ImportRequest(val teamId: UUID?, val plan: List<ImportPlanRow> = emptyList())

@RestController
@RequestMapping("/api/notion/import")
class NotionImportController(
	private val imports: NotionImportService,
	private val currentUser: CurrentUser,
) {

	/**
	 * Not `@Transactional`: this one walks the workspace over the network, and a
	 * transaction held open for the length of that is a connection out of the pool for
	 * however long Notion takes. The service opens its own where it touches Postgres.
	 */
	@GetMapping("/sources")
	fun sources(): ImportSources = imports.sources()

	/** Not `@Transactional`, for the same reason as [sources]: this walks the network too. */
	@GetMapping("/schema")
	fun schema(@RequestParam sourceId: String, @RequestParam target: String): ImportSchemaView =
		imports.schema(sourceId, ImportTarget.from(target))

	@PostMapping("/preview")
	fun preview(@RequestBody request: ImportPreviewRequest): ImportPreview =
		imports.preview(request.plan.map(::entry))

	@PostMapping
	fun confirm(@RequestBody request: ImportRequest): ImportOutcome =
		imports.perform(currentUser.require(), request.teamId, request.plan.map(::entry))

	/**
	 * An unknown target, field name, or mapped option is a 400 through [ApiExceptionHandler],
	 * the same as an unknown status: [ImportTarget.from] and [ImportField.from] both raise
	 * `IllegalArgumentException` for a wire string outside their vocabulary, and nothing here
	 * catches it — the vocabulary is closed on both sides of the wire or it is not closed.
	 */
	private fun entry(row: ImportPlanRow) = ImportPlanEntry(
		sourceId = row.sourceId,
		target = ImportTarget.from(row.target),
		mapping = ColumnMapping(
			columns = row.columns.mapKeys { (field, _) -> ImportField.from(field) },
			values = row.values.mapKeys { (field, _) -> ImportField.from(field) },
		),
		fallback = row.fallback,
	)
}

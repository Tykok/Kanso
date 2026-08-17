package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.sync.importer.ImportOutcome
import dev.kanso.sync.importer.ImportPlanEntry
import dev.kanso.sync.importer.ImportPreview
import dev.kanso.sync.importer.ImportSources
import dev.kanso.sync.importer.ImportTarget
import dev.kanso.sync.importer.NotionImportService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** One row of screen 24's mapping. An ignored base is absent, never `target: "ignore"`. */
data class ImportPlanRow(val sourceId: String, val target: String)

data class ImportPreviewRequest(val plan: List<ImportPlanRow> = emptyList())

/**
 * The import itself, and the one field the client had no way to send.
 *
 * [teamId] is where the ticket's team and its per-team number come from. `architecture.md`
 * is explicit that a page made by hand in Notion can supply neither, which is why the
 * poller refuses to adopt one; an import is the case where somebody *is* present to say
 * whose work this is, so the mapping carries the answer and [NotionImportService] refuses
 * a team the actor may not write to before anything is written.
 */
data class ImportRequest(val teamId: UUID, val plan: List<ImportPlanRow> = emptyList())

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

	@PostMapping("/preview")
	fun preview(@RequestBody request: ImportPreviewRequest): ImportPreview =
		imports.preview(request.plan.map(::entry))

	@PostMapping
	fun confirm(@RequestBody request: ImportRequest): ImportOutcome =
		imports.perform(currentUser.require(), request.teamId, request.plan.map(::entry))

	/** An unknown target is a 400 through [ApiExceptionHandler], the same as an unknown status. */
	private fun entry(row: ImportPlanRow) = ImportPlanEntry(row.sourceId, ImportTarget.from(row.target))
}

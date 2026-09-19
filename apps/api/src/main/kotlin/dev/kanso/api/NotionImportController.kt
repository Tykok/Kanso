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
import dev.kanso.sync.importer.NotionPerson
import dev.kanso.sync.importer.SchemaColumn
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
 * [ImportSchemaView] on the wire, with every field and target named the way the request
 * names them.
 *
 * The view itself is typed in [ImportField] and [ImportTarget], and Jackson writes an enum
 * as its own `name()` — so returning it raw put `"STATUS"` and `"TICKETS"` on the wire
 * while [entry] only accepts `"status"` and `"tickets"` coming back. The screen would have
 * had to hold both vocabularies and a translation between them, which is one more place
 * for the closed set to drift. Every other response in `Dtos.kt` reaches for `.wire` for
 * exactly this reason; this one had been the exception.
 */
data class ImportSchemaResponse(
	val sourceId: String,
	val target: String,
	val columns: List<SchemaColumn>,
	val fields: List<FieldCandidatesResponse>,
	val suggestion: ColumnMappingResponse,
	val defaults: Map<String, String?>,
) {
	companion object {
		fun of(view: ImportSchemaView) = ImportSchemaResponse(
			sourceId = view.sourceId,
			target = view.target.wire,
			columns = view.columns,
			fields = view.fields.map { FieldCandidatesResponse(it.field.wire, it.candidates, it.prefill) },
			suggestion = ColumnMappingResponse(
				columns = view.suggestion.columns.mapKeys { (field, _) -> field.wire },
				values = view.suggestion.values.mapKeys { (field, _) -> field.wire },
			),
			defaults = view.defaults.mapKeys { (field, _) -> field.wire },
		)
	}
}

/**
 * [prefill] is keyed by column name, never by field: it answers "if the reader picks *this*
 * column for this field, what does each of its options mean", which is the question the
 * columns step asks the moment somebody changes a select. Keyed by field it already is —
 * once, by being inside this field's entry.
 */
data class FieldCandidatesResponse(
	val field: String,
	val candidates: List<String>,
	val prefill: Map<String, Map<String, String>>,
)

data class ColumnMappingResponse(
	val columns: Map<String, String>,
	val values: Map<String, Map<String, String>>,
)

/**
 * The import itself, and the fields the client had no other way to send.
 *
 * [teamId] is where the ticket's team and its per-team number come from, but no longer
 * unconditionally: an import of teams alone has no destination to ask about, so it is
 * `null` unless something in [plan] needs one. `architecture.md` is explicit that a page
 * made by hand in Notion can supply neither a team nor a per-team number, which is why the
 * poller refuses to adopt one; an import is the case where somebody *is* present to say
 * whose work this is, so the mapping carries the answer and [NotionImportService] refuses
 * a team the actor may not write to — [teamId] or any row's own fallback — before anything
 * is written.
 *
 * [people] is screen 24's people-matching step, keyed by the Notion person id `peopleSeen`
 * answered and valued by the Kanso account the reader chose for it, or `null` for one they
 * chose to leave unmatched. `NotionImportService.perform` writes it through
 * `NotionPeople.link` before anything else, so the correspondence holds even for a page
 * whose assignee is dropped for naming an account that no longer exists.
 */
data class ImportRequest(
	val teamId: UUID?,
	val plan: List<ImportPlanRow> = emptyList(),
	val people: Map<String, UUID?> = emptyMap(),
)

/**
 * Screen 24, from "what is in this workspace" to "write it".
 *
 * **Four of these five take no actor, and that is the dispensation `NotionPeople.link`
 * already carries, said out loud here.** [sources], [schema], [preview] and [peopleSeen]
 * answer any signed-in member. Not because nobody thought about it: an import is a job a
 * member is expected to be able to carry out, and `link` is written to permit exactly that
 * — its guard fires on the *change* a people table would make, so a non-configurator can
 * walk the whole wizard, re-send the matches the instance already holds, and import. Ending
 * that walk at step 2 with a 403 would make `link`'s dispensation unreachable and leave a
 * flow the codebase is deliberately built for with no way to start.
 *
 * What was wrong before this comment was not the permission, it was the silence. Four
 * open routes with nothing saying why are correct only until somebody reads them — and then
 * they get "tightened" by a reader who sees an unguarded Notion read, or cited as precedent
 * for opening something that has no such argument. Both are edits made on the basis of
 * nothing, which is why each of the four repeats the claim beside itself rather than
 * trusting a reader to arrive here first.
 *
 * These four disclose the workspace Kanso is connected to, and that is the price named
 * rather than missed: base names, column names, options, and the Notion people a plan would
 * meet. It is the workspace this instance has already chosen to mirror every ticket into,
 * and no token crosses this wire.
 *
 * [confirm] is the fifth and it takes the actor, because it is the one that writes: it
 * access-checks every team the plan lands in and hands its people table to `link`, guard
 * and all.
 *
 * **A security review raised [sources] against exactly that gap and was answered "no".**
 * The objection is worth stating because it is a good one and will be made again: `sources`
 * does not answer about a base the caller named, it runs `discovery.search` over the whole
 * workspace minus Kanso's own four, so any member — a contractor invited to one team, or a
 * read-only seat, since `ReadOnlySeat` never refuses a GET — reads back the names and row
 * counts of every database the integration can reach, and can then ask [schema] about any of
 * them. That is a real disclosure and it is the one the paragraph above already prices in.
 *
 * It is not closed here because closing it costs more than it buys: `NotionImportIsOpenTest`
 * exists precisely to fail this edit, an import is a member's job to carry out, and a 403 at
 * step 1 ends the wizard at a door nothing downstream can get past. The narrowing that would
 * satisfy both is an admin-staged allowlist of importable bases — `sources` answering only
 * what a configurator put in play — which is a feature with a screen and a migration, not a
 * guard. Until somebody wants it, the honest position is that this instance's Notion
 * integration is scoped to what Kanso may mirror, and a workspace holding boards no member
 * may see should not have granted it in the first place.
 *
 * **The gap with `GET /api/setup/notion/pages`, which stays configurator-only, is real and
 * not an inconsistency**: that route lists the pages of the workspace so somebody can choose
 * where Kanso will *create its four databases* — an act of instance configuration, taking a
 * token off the request to answer about pages nobody has decided to mirror yet. Previewing
 * the base you are importing is a question about work already inside the mirror's scope.
 * Same workspace, two different questions, and only one of them is a setting.
 */
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
	 *
	 * Takes no actor, on the class's argument: step 1 of a wizard a member is meant to
	 * finish. It names the workspace's bases and their row counts and nothing about anyone.
	 */
	@GetMapping("/sources")
	fun sources(): ImportSources = imports.sources()

	/**
	 * Not `@Transactional`, for the same reason as [sources]: this walks the network too.
	 *
	 * Takes no actor, on the class's argument. Step 3 reads one base's columns and their
	 * options — the vocabulary the reader is about to map, which they cannot map unseen.
	 */
	@GetMapping("/schema")
	fun schema(@RequestParam sourceId: String, @RequestParam target: String): ImportSchemaResponse =
		ImportSchemaResponse.of(imports.schema(sourceId, ImportTarget.from(target)))

	/**
	 * Takes no actor, on the class's argument, and of the four it is the one where saying so
	 * matters most: it reads the pages themselves. It also writes nothing — the whole point
	 * of step 5 is that the reader sees what *would* happen — so refusing it while allowing
	 * [confirm] would leave a member able to run an import but not to check it first, which
	 * is the wrong half to keep.
	 */
	@PostMapping("/preview")
	fun preview(@RequestBody request: ImportPreviewRequest): ImportPreview =
		imports.preview(request.plan.map(::entry))

	/**
	 * A POST, not a GET, for the same reason [preview] is: the plan is the request body,
	 * and there is nowhere else on a `GET` to put it. Answers [NotionPerson] rather than
	 * the raw workspace member list `NotionPeople.view` reads — this is only who the plan's
	 * *mapped* columns would meet, before the reader is asked to match any of them.
	 *
	 * Takes no actor, on the class's argument, and the narrower answer is part of why: a
	 * member running an import is shown the people their own plan met, not the workspace's
	 * directory, which is `NotionPeople.view`'s and reached from the configurator's screen.
	 * Matching any of them is `link`'s, and `link` still asks who is calling.
	 */
	@PostMapping("/people-seen")
	fun peopleSeen(@RequestBody request: ImportPreviewRequest): List<NotionPerson> =
		imports.peopleSeen(request.plan.map(::entry))

	@PostMapping
	fun confirm(@RequestBody request: ImportRequest): ImportOutcome =
		imports.perform(currentUser.require(), request.teamId, request.plan.map(::entry), request.people)

	/**
	 * An unknown target, field name, or mapped option is a 400 through [ApiExceptionHandler],
	 * the same as an unknown status: [ImportTarget.from] and [ImportField.from] both raise
	 * `IllegalArgumentException` for a wire string outside their vocabulary, and nothing here
	 * catches it — the vocabulary is closed on both sides of the wire or it is not closed. So
	 * is a field the *target* does not read, which [field] is for.
	 */
	private fun entry(row: ImportPlanRow): ImportPlanEntry {
		val target = ImportTarget.from(row.target)
		return ImportPlanEntry(
			sourceId = row.sourceId,
			target = target,
			mapping = ColumnMapping(
				columns = row.columns.mapKeys { (field, _) -> field(target, field) },
				values = row.values.mapKeys { (field, _) -> field(target, field) },
			),
			fallback = row.fallback,
		)
	}

	/**
	 * One field name, refused unless [target] is a target that reads it.
	 *
	 * `ImportField.from` alone only asks whether the *vocabulary* holds the word. A request
	 * mapping `description` on a `teams` base passed that and then went nowhere: no writer
	 * of teams reads it, while `MappedPageReader.claimed` still took the column out of the
	 * "Imported from Notion" section — so the property was dropped from the row it would
	 * otherwise have been preserved in, silently. The plan's wire contract says "field ∈
	 * the target's fields", and this is where that half of it is enforced, as a 400 through
	 * [ApiExceptionHandler] like every other word outside a closed set.
	 */
	private fun field(target: ImportTarget, raw: String): ImportField {
		val field = ImportField.from(raw)
		require(field in target.fields) { "A base imported as ${target.wire} has no '$raw' field to map." }
		return field
	}
}

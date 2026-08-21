package dev.kanso.sync.importer

import dev.kanso.domain.Wire
import dev.kanso.domain.parse

/**
 * What a Notion base becomes. There is no `IGNORE`: an ignored base is *absent* from the
 * plan rather than present with a target meaning "do nothing" — `import-map.ts` builds the
 * request that way on purpose, and an instruction listing things not to do is one more
 * thing this service would have to be trusted to read correctly.
 *
 * `PROJECT` used to mean "this base becomes *one* project whose pages are tickets", which
 * described the container rather than the base. `TICKETS` is that behaviour under a name
 * that says it, and `PROJECTS` is the other shape: a base whose pages *are* projects.
 */
enum class ImportTarget(override val wire: String) : Wire {
	TEAMS("teams"),
	PROJECTS("projects"),
	TICKETS("tickets"),
	DOCUMENTS("documents");

	companion object {
		fun from(raw: String): ImportTarget = parse(entries.toTypedArray(), raw)
	}
}

/** One row of screen 24's second step, as it arrives. */
data class ImportPlanEntry(val sourceId: String, val target: ImportTarget)

/**
 * A base the workspace search found.
 *
 * [id] is the *data source* id, not the database's: queries target data sources since
 * 2025-09-03, and the id the plan sends back has to be the one that can be read. [pages]
 * is counted, never estimated — screen 24 puts it in front of a confirm button — and
 * [pagesExact] is false when the count stopped at the discovery cap rather than at the
 * end of the base.
 */
data class DiscoveredSource(
	val id: String,
	val databaseId: String,
	val name: String,
	val pages: Int,
	val pagesExact: Boolean,
)

/**
 * Step 1's answer. [available] false with a [reason] is a first-class state, not a
 * failure: an instance with no token has nothing to import from, and the dialog is drawn
 * to print the sentence rather than spin.
 */
data class ImportSources(
	val available: Boolean,
	val reason: String?,
	val sources: List<DiscoveredSource>,
)

/** What would happen. Nothing in producing this writes a row. */
data class ImportPreview(
	/** Bases becoming teams. Empty until Task 3's writer exists to make the group mean something. */
	val teams: List<PreviewGroup>,
	val projects: List<PreviewGroup>,
	val folders: List<PreviewGroup>,
	/** Bases holding a relation into another chosen base; those relations become dependencies. */
	val linkedSources: Int,
	/** Property names Kanso has no column for. They land in an "imported from Notion" section. */
	val unmappedProperties: List<String>,
	/** Pages that would be reported rather than adopted — see [SkippedPage]. */
	val skipped: Int,
)

data class PreviewGroup(val name: String, val pages: Int)

/**
 * A page Kanso could not adopt, and why.
 *
 * `architecture.md` is explicit that a page created in Notion has no team and no number
 * of its own; the import supplies both from the request, so what is left are pages with
 * nothing to make a row out of — no title — and pages Notion has archived. Reported
 * rather than invented, the same posture the inbound poller takes.
 */
data class SkippedPage(val source: String, val pageId: String, val reason: String)

/** What the import did. [started] is what the client's typed shape asks for. */
data class ImportOutcome(
	val started: Boolean,
	val tickets: Int,
	val docs: Int,
	val projects: Int,
	val folders: Int,
	val dependencies: Int,
	/** Relations with an end that resolved to nothing. Counted, never guessed at. */
	val droppedRelations: Int,
	val skipped: List<SkippedPage>,
)

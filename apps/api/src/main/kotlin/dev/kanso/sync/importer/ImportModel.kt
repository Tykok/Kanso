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

	/**
	 * Which fields of a row this target's pages read from a mapped column.
	 *
	 * The schema screen uses this to know which fields to offer a column against at all —
	 * a base of documents has no field to map, and a base of tickets has no use for
	 * `subTeams`, which exists only to read a teams base's own column naming its children.
	 */
	val fields: Set<ImportField> get() = when (this) {
		TEAMS -> setOf(ImportField.PARENT_TEAM, ImportField.PROJECTS, ImportField.SUB_TEAMS)
		PROJECTS -> setOf(
			ImportField.STATUS, ImportField.START, ImportField.END, ImportField.LEAD,
			ImportField.TEAM, ImportField.TICKETS,
		)
		TICKETS -> setOf(
			ImportField.STATUS, ImportField.PRIORITY, ImportField.DESCRIPTION, ImportField.START,
			ImportField.DUE, ImportField.ASSIGNEES, ImportField.PROJECT, ImportField.BLOCKED_BY,
		)
		DOCUMENTS -> emptySet()
	}

	companion object {
		fun from(raw: String): ImportTarget = parse(entries.toTypedArray(), raw)
	}
}

/**
 * One row of screen 24's second step, as it arrives.
 *
 * [mapping] is the row's own answer to "which of this base's columns answer which field".
 * It has no wire form yet — the controller still builds an empty one — but it reaches
 * [PlannedBase] from here, so the writer reads a base's relations through the same mapping
 * the request will carry once the columns screen sends it.
 *
 * [fallback] is this base's own answer to where an unlinked row lands, screen 24 filling
 * it in per base because a base whose team cannot be resolved may want a different
 * destination from its neighbour. It carries all the way to [PlannedBase] unchanged.
 */
data class ImportPlanEntry(
	val sourceId: String,
	val target: ImportTarget,
	val mapping: ColumnMapping = ColumnMapping(),
	val fallback: Fallback = Fallback(),
)

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
	/** Bases becoming teams: one group per base, counting the pages that become teams. */
	val teams: List<PreviewGroup>,
	/**
	 * Bases becoming projects, either shape: a base whose pages *are* projects, and a base
	 * whose pages are tickets in one project named after it. The count is pages either way,
	 * which is what the screen prints under the base's name.
	 */
	val projects: List<PreviewGroup>,
	val folders: List<PreviewGroup>,
	/**
	 * Bases taking part in a mapped link that crosses into another kept base — bases, not
	 * arrows, because a hundred relations between two databases is still two databases.
	 */
	val linkedSources: Int,
	/** Property names Kanso has no column for. They land in an "imported from Notion" section. */
	val unmappedProperties: List<String>,
	/** Pages that would be reported rather than adopted — see [SkippedPage]. */
	val skipped: Int,
	/**
	 * Pages a row already exists for, per `notion_import_origin`. Distinct from [skipped]:
	 * these pages are perfectly adoptable, there is simply already a Kanso row for them,
	 * and a second run of the same import would leave them alone rather than refuse them.
	 */
	val alreadyImported: Int,
	/**
	 * Rows a relation placed, per [ImportLinks] — one per child page whose parent was found.
	 * A dependency is not one of them: it draws an arrow between two rows, it does not
	 * decide where either of them lands.
	 */
	val linkedByRelation: Int = 0,
	/**
	 * Rows [ImportLinks] alone cannot place, and so would land in a [Fallback] the writer
	 * chooses. Always 0 here: [ImportPlanner.preview] takes a base's own [Fallback] into
	 * account nowhere in its arithmetic, so a fallback-heavy plan under-reports this count
	 * today. Counting it is still to be done, whenever a task decides to.
	 */
	val fellBack: Int = 0,
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
	/** Teams created from a base whose pages *are* teams. Their parents are set in a second pass. */
	val teams: Int,
	val tickets: Int,
	val docs: Int,
	val projects: Int,
	val folders: Int,
	val dependencies: Int,
	/** Relations with an end that resolved to nothing. Counted, never guessed at. */
	val droppedRelations: Int,
	val skipped: List<SkippedPage>,
	/**
	 * Pages this run left untouched because `notion_import_origin` already had a row for
	 * them. What makes pressing the same import twice safe rather than merely harmless: the
	 * second run says how much it skipped instead of silently doing nothing.
	 */
	val alreadyImported: Int,
	/**
	 * Disagreements [ImportLinks] settled by letting the child win — see
	 * [ImportLinks.Resolved.conflicts]. Reported rather than repaired: a workspace whose two
	 * sides of a relation contradict each other is a fact about the workspace, and the
	 * number is how somebody finds out.
	 */
	val linkConflicts: Int = 0,
	/**
	 * A mapped `ASSIGNEES` person who resolved to a real account, but not one the
	 * destination team holds — `TicketService.create` will not put them on the ticket, so
	 * the ticket is written unassigned instead, and this is how the reader finds out. A
	 * Notion person nobody mapped at all is not one of these: that ticket is unassigned
	 * too, but nothing was dropped, because nothing was ever named.
	 */
	val droppedAssignees: Int = 0,
)

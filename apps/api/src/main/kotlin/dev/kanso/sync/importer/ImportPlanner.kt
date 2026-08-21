package dev.kanso.sync.importer

import dev.kanso.sync.notion.NotionPage

/**
 * One relation that will become a dependency: [successorPageId] waits on [predecessorPageId].
 *
 * The direction is Kanso's own reading of a relation, the one the mirror already writes:
 * `Blocked by` points from a ticket to the tickets it waits on. So the page holding the
 * relation is the successor, and the pages it names are what it waits on. Notion attaches
 * no meaning to a relation's direction, so this is a decision rather than a translation —
 * but it is the same decision in both directions of the sync, which is the property worth
 * having.
 */
data class PageDependency(val predecessorPageId: String, val successorPageId: String)

/**
 * What a mapping adds up to, computed from pages already read.
 *
 * Pure, and shared by the preview and the import: step 3's promise is that the numbers it
 * shows are the ones about to happen, and the only way to keep that true is for both to
 * be the same arithmetic over the same input. `import-map.ts` holds the other half of it,
 * in front of the reader, and neither side derives its counts from the other.
 */
object ImportPlanner {

	fun preview(bases: List<PlannedBase>): ImportPreview {
		val links = ImportLinks.resolve(bases)
		return ImportPreview(
			teams = bases.filter { it.target == ImportTarget.TEAMS }
				.map { PreviewGroup(it.base.name, it.adoptable.size) },
			projects = bases.filter { it.target == ImportTarget.TICKETS }
				.map { PreviewGroup(it.base.name, it.adoptable.size) },
			folders = bases.filter { it.target == ImportTarget.DOCUMENTS }
				.map { PreviewGroup(it.base.name, it.adoptable.size) },
			linkedSources = linkedBases(bases, links).size,
			unmappedProperties = bases.flatMap { base -> base.adoptable.flatMap(base.reader::unmapped) }
				.map { (name, _) -> name }
				.distinct()
				.sorted(),
			// `PlannedBase.skippedPages` already excludes pages already imported — see it for
			// why. The writer counts the same list for the same reason, from the same property,
			// so the two can no longer drift into counting one page in both buckets.
			skipped = bases.sumOf { it.skippedPages.size },
			alreadyImported = bases.sumOf { it.alreadyImported.size },
			// A row placed by a relation, which is what these three maps are: one entry per
			// child page whose parent was found. A dependency is not one of them — it places
			// no row, it draws an arrow between two rows already placed.
			linkedByRelation = links.parentOfTeam.size + links.teamOfProject.size + links.projectOfTicket.size,
		)
	}

	/**
	 * The bases taking part in a link that crosses from one kept base into another.
	 *
	 * Step 3's sentence — "three of them are linked to each other" — is about bases, not
	 * arrows: a hundred relations between the same two databases is still two databases
	 * linked. Every link [ImportLinks] resolved counts, a dependency included; a link whose
	 * two ends sit in the *same* base is real and still says nothing about which bases are
	 * linked to which.
	 */
	private fun linkedBases(bases: List<PlannedBase>, links: ImportLinks.Resolved): Set<String> {
		val owner = bases.flatMap { base -> base.adoptable.map { it.id to base.base.dataSourceId } }.toMap()
		val ends = listOf(links.parentOfTeam, links.teamOfProject, links.projectOfTicket)
			.flatMap { linked -> linked.map { (child, parent) -> child to parent } } +
			links.dependencies.map { it.successorPageId to it.predecessorPageId }

		val linked = mutableSetOf<String>()
		for ((from, to) in ends) {
			val here = owner[from] ?: continue
			val there = owner[to] ?: continue
			if (here == there) continue
			linked += here
			linked += there
		}
		return linked
	}
}

/** A base, what it becomes, and the pages that were read out of it. */
class PlannedBase(
	val base: WorkspaceBase,
	val target: ImportTarget,
	val pages: List<NotionPage>,
	/** Pages this instance has already imported, by id. Skipped, and counted. */
	val alreadyImported: Set<String> = emptySet(),
	/** Which columns of this base answer which fields — see [ImportLinks] for the relations. */
	val mapping: ColumnMapping = ColumnMapping(),
) {
	/**
	 * How this base's pages are read. The only way anything reaches a [MappedPageReader]:
	 * one base is one mapping, so a reader built anywhere else would be a reader built
	 * without one.
	 */
	val reader: MappedPageReader by lazy { MappedPageReader(mapping) }

	/** The pages that can become rows, in the order Notion returned them. */
	val adoptable: List<NotionPage> by lazy {
		pages.filter { reader.refusal(it) == null && it.id !in alreadyImported }
	}

	/**
	 * Pages that are neither adoptable nor already imported — refused by
	 * [MappedPageReader.refusal], and the only pages that end up in a [SkippedPage].
	 *
	 * A page already imported is already-imported *only*: what Notion currently says about
	 * it — archived, untitled, whatever — is beside the point of what this import will do,
	 * so [alreadyImported] is excluded here, the same exclusion [adoptable] makes. Both the
	 * preview and the writer count this one property instead of each writing their own copy
	 * of the exclusion, which is how a page already imported and now untitled once ended up
	 * counted as both skipped and already-imported in the same run.
	 */
	val skippedPages: List<NotionPage> by lazy {
		pages.filter { it.id !in alreadyImported && reader.refusal(it) != null }
	}
}

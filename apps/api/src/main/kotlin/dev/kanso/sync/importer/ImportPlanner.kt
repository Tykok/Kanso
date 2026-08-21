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

	fun preview(bases: List<PlannedBase>): ImportPreview = ImportPreview(
		teams = bases.filter { it.target == ImportTarget.TEAMS }
			.map { PreviewGroup(it.base.name, it.adoptable.size) },
		projects = bases.filter { it.target == ImportTarget.TICKETS }
			.map { PreviewGroup(it.base.name, it.adoptable.size) },
		folders = bases.filter { it.target == ImportTarget.DOCUMENTS }
			.map { PreviewGroup(it.base.name, it.adoptable.size) },
		linkedSources = linkedBases(bases).size,
		unmappedProperties = bases.flatMap { it.adoptable }
			.flatMap(NotionPageReader::unmapped)
			.map { (name, _) -> name }
			.distinct()
			.sorted(),
		// `PlannedBase.skippedPages` already excludes pages already imported — see it for
		// why. The writer counts the same list for the same reason, from the same property,
		// so the two can no longer drift into counting one page in both buckets.
		skipped = bases.sumOf { it.skippedPages.size },
		alreadyImported = bases.sumOf { it.alreadyImported.size },
	)

	/**
	 * The bases taking part in a relation that crosses from one kept base into another.
	 *
	 * Step 3's sentence — "three of them are linked to each other" — is about bases, not
	 * arrows: a hundred relations between the same two databases is still two databases
	 * linked. A relation inside a single base still becomes a dependency (see
	 * [dependencies]); it just says nothing about which bases are linked.
	 */
	private fun linkedBases(bases: List<PlannedBase>): Set<String> {
		val ticketable = bases.filter { it.target == ImportTarget.TICKETS }
		val owner = ticketable.flatMap { base -> base.adoptable.map { it.id to base.base.dataSourceId } }.toMap()

		val linked = mutableSetOf<String>()
		for (base in ticketable) {
			for (page in base.adoptable) {
				for (target in NotionPageReader.relations(page)) {
					val other = owner[target] ?: continue
					if (other == base.base.dataSourceId) continue
					linked += base.base.dataSourceId
					linked += other
				}
			}
		}
		return linked
	}

	/**
	 * Every relation whose two ends both became tickets, and the count of those that did
	 * not.
	 *
	 * A relation pointing at a page in an ignored base, in a base that became documents, or
	 * at a page that could not be adopted has one end and no other. It is dropped and
	 * counted — never invented, and never turned into a dependency on the nearest thing
	 * that happened to resolve.
	 */
	fun dependencies(bases: List<PlannedBase>): DependencyPlan {
		val ticketable = bases.filter { it.target == ImportTarget.TICKETS }
		val adoptedIds = ticketable.flatMapTo(mutableSetOf()) { base -> base.adoptable.map { it.id } }

		val edges = mutableListOf<PageDependency>()
		var dropped = 0
		for (base in ticketable) {
			for (page in base.adoptable) {
				for (target in NotionPageReader.relations(page)) {
					if (target in adoptedIds && target != page.id) {
						edges += PageDependency(predecessorPageId = target, successorPageId = page.id)
					} else {
						dropped++
					}
				}
			}
		}
		return DependencyPlan(edges, dropped)
	}
}

data class DependencyPlan(val edges: List<PageDependency>, val dropped: Int)

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
	/** The pages that can become rows, in the order Notion returned them. */
	val adoptable: List<NotionPage> by lazy {
		pages.filter { NotionPageReader.refusal(it) == null && it.id !in alreadyImported }
	}

	/**
	 * Pages that are neither adoptable nor already imported — refused by
	 * [NotionPageReader.refusal], and the only pages that end up in a [SkippedPage].
	 *
	 * A page already imported is already-imported *only*: what Notion currently says about
	 * it — archived, untitled, whatever — is beside the point of what this import will do,
	 * so [alreadyImported] is excluded here, the same exclusion [adoptable] makes. Both the
	 * preview and the writer count this one property instead of each writing their own copy
	 * of the exclusion, which is how a page already imported and now untitled once ended up
	 * counted as both skipped and already-imported in the same run.
	 */
	val skippedPages: List<NotionPage> by lazy {
		pages.filter { it.id !in alreadyImported && NotionPageReader.refusal(it) != null }
	}
}

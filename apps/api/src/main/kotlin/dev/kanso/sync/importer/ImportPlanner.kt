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
		projects = bases.filter { it.target == ImportTarget.PROJECT }
			.map { PreviewGroup(it.base.name, it.adoptable.size) },
		folders = bases.filter { it.target == ImportTarget.DOCUMENTS }
			.map { PreviewGroup(it.base.name, it.adoptable.size) },
		linkedSources = linkedBases(bases).size,
		unmappedProperties = bases.flatMap { it.adoptable }
			.flatMap(NotionPageReader::unmapped)
			.map { (name, _) -> name }
			.distinct()
			.sorted(),
		skipped = bases.sumOf { it.pages.size - it.adoptable.size },
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
		val ticketable = bases.filter { it.target == ImportTarget.PROJECT }
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
		val ticketable = bases.filter { it.target == ImportTarget.PROJECT }
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
) {
	/** The pages that can become rows, in the order Notion returned them. */
	val adoptable: List<NotionPage> by lazy { pages.filter { NotionPageReader.refusal(it) == null } }
}

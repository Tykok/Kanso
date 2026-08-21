package dev.kanso.sync.importer

import dev.kanso.sync.notion.NotionPage

/**
 * Resolves the relations a mapping names, whichever side of the workspace declares them.
 *
 * A Notion relation created `single_property` exists on *one* side only — the mirror
 * relies on exactly that for `Blocked by` and for a team's parent — so a workspace built
 * by someone who never heard of Kanso is free to carry, say, the project link as a
 * `Projet` column on the tasks base, or as a `Tâches` column on the projects base, and
 * neither shape is more correct than the other. Reading only the child's own column would
 * leave the second shape with every ticket unlinked, so every one-to-one relation here is
 * read from both ends: the child's own field, and the parent's inverse field naming it back.
 *
 * When both ends speak and disagree, the row that names its own parent wins — a page's
 * own answer about its own parent outranks somebody else's guess about it — and the
 * disagreement is [Resolved.conflicts], counted rather than settled quietly. `Blocked by`
 * has no inverse column to read: the mirror only ever writes it on one side, so a
 * dependency here is exactly what the reader mapped as one, on the page that names it.
 *
 * Pure: nothing here reads or writes anything beyond the [PlannedBase] list it is handed.
 * Turning a Notion page id into a Kanso [java.util.UUID] is the writer's job, done by
 * looking these page-id answers up in its own map of what it has already created.
 */
object ImportLinks {

	data class Resolved(
		val parentOfTeam: Map<String, String>,
		val teamOfProject: Map<String, String>,
		val projectOfTicket: Map<String, String>,
		val dependencies: List<PageDependency>,
		/**
		 * Relation ends that named a page which resolved to nothing: a page in a base the
		 * plan ignores, in a base that became documents, or a page that could not be
		 * adopted. Never invented into a link to the nearest thing that happened to resolve.
		 */
		val droppedRelations: Int,
		/**
		 * Disagreements settled rather than silently picked: a child's own field naming a
		 * different parent than the one whose inverse field claims it (settled in the
		 * child's favour), and two parents' inverse fields both claiming the same child
		 * (settled in the first claim's favour).
		 */
		val conflicts: Int,
	)

	fun resolve(bases: List<PlannedBase>): Resolved {
		// Which pages can be the *target* end of a relation, per target kind — a page that
		// is not adoptable, or sits in a base the plan does not carry at all, is exactly
		// the shape of page a relation end can name and never resolve.
		val adopted: Map<ImportTarget, Set<String>> = ImportTarget.entries.associateWith { target ->
			bases.filter { it.target == target }
				.flatMapTo(mutableSetOf()) { base -> base.adoptable.map { it.id } }
		}

		val parentOfTeam = resolveOneToOne(
			bases, adopted,
			childTarget = ImportTarget.TEAMS, childField = ImportField.PARENT_TEAM,
			parentTarget = ImportTarget.TEAMS, inverseField = ImportField.SUB_TEAMS,
		)
		val teamOfProject = resolveOneToOne(
			bases, adopted,
			childTarget = ImportTarget.PROJECTS, childField = ImportField.TEAM,
			parentTarget = ImportTarget.TEAMS, inverseField = ImportField.PROJECTS,
		)
		val projectOfTicket = resolveOneToOne(
			bases, adopted,
			childTarget = ImportTarget.TICKETS, childField = ImportField.PROJECT,
			parentTarget = ImportTarget.PROJECTS, inverseField = ImportField.TICKETS,
		)
		val deps = resolveDependencies(bases, adopted)

		return Resolved(
			parentOfTeam = parentOfTeam.links,
			teamOfProject = teamOfProject.links,
			projectOfTicket = projectOfTicket.links,
			dependencies = deps.edges,
			droppedRelations = parentOfTeam.dropped + teamOfProject.dropped + projectOfTicket.dropped + deps.dropped,
			conflicts = parentOfTeam.conflicts + teamOfProject.conflicts + projectOfTicket.conflicts,
		)
	}

	private data class RelationResult(val links: Map<String, String>, val dropped: Int, val conflicts: Int)
	private data class DependencyResult(val edges: List<PageDependency>, val dropped: Int)

	/**
	 * One relation between a child and its parent, read from both ends.
	 *
	 * The child's own [childField] names at most one parent; the parent's [inverseField]
	 * names every child it claims. Both are optional independently of one another — a
	 * mapping may map neither, either, or both — and whichever side speaks is trusted. Only
	 * when both speak for the *same* child and disagree does the child's own answer win,
	 * with the disagreement added to [RelationResult.conflicts].
	 *
	 * Two disagreements are possible, and both are counted rather than settled quietly:
	 * a child naming one parent while a different parent's inverse column also claims it
	 * (child wins), and two *parents* both claiming the same child through their own
	 * inverse columns, which is a disagreement of the same kind even though no child field
	 * is involved at all.
	 */
	private fun resolveOneToOne(
		bases: List<PlannedBase>,
		adopted: Map<ImportTarget, Set<String>>,
		childTarget: ImportTarget,
		childField: ImportField,
		parentTarget: ImportTarget,
		inverseField: ImportField,
	): RelationResult {
		val childAdopted = adopted[childTarget].orEmpty()
		val parentAdopted = adopted[parentTarget].orEmpty()
		var dropped = 0
		var conflicts = 0

		// The child's own column. At most one target is read per page — the field means
		// "my one parent" — and a page pointing at itself names nothing.
		val fromChild = mutableMapOf<String, String>()
		for (base in bases.filter { it.target == childTarget }) {
			val property = base.mapping.property(childField) ?: continue
			for (page in base.adoptable) {
				val target = relationIds(page, property).firstOrNull { it != page.id } ?: continue
				if (target in parentAdopted) fromChild[page.id] = target else dropped++
			}
		}

		// The parent's inverse column: one page can legitimately claim several children,
		// so every named id is read, not just the first.
		val fromParent = mutableMapOf<String, String>()
		for (base in bases.filter { it.target == parentTarget }) {
			val property = base.mapping.property(inverseField) ?: continue
			for (page in base.adoptable) {
				for (target in relationIds(page, property)) {
					if (target == page.id) continue
					if (target !in childAdopted) {
						dropped++
						continue
					}
					val claimedBy = fromParent[target]
					when {
						claimedBy == null -> fromParent[target] = page.id
						claimedBy != page.id ->
							// Two different parent pages both name this child. Neither is
							// more correct than the other, so this is a disagreement, not
							// a decision this function gets to make — it is counted, and
							// the first claim is kept rather than the last: pages arrive
							// in the order Notion returned them, and "first wins" is at
							// least stable across two runs of the same import, unlike
							// "last wins", which would depend on base and page order.
							conflicts++
						// else: the same parent named the same child twice in one column — not a disagreement.
					}
				}
			}
		}

		val merged = mutableMapOf<String, String>()
		for (childId in fromChild.keys + fromParent.keys) {
			val ownAnswer = fromChild[childId]
			val parentsAnswer = fromParent[childId]
			merged[childId] = when {
				ownAnswer == null -> checkNotNull(parentsAnswer)
				parentsAnswer == null || parentsAnswer == ownAnswer -> ownAnswer
				else -> {
					conflicts++
					ownAnswer
				}
			}
		}
		return RelationResult(merged, dropped, conflicts)
	}

	/**
	 * `Blocked by`, read from the child's side only — the mirror never writes an inverse
	 * `Blocks` column, so there is no second side to reconcile against. A relation pointing
	 * at a page outside the adopted tickets is dropped and counted, the same rule every
	 * other relation end follows.
	 */
	private fun resolveDependencies(bases: List<PlannedBase>, adopted: Map<ImportTarget, Set<String>>): DependencyResult {
		val ticketIds = adopted[ImportTarget.TICKETS].orEmpty()
		val edges = mutableListOf<PageDependency>()
		var dropped = 0

		for (base in bases.filter { it.target == ImportTarget.TICKETS }) {
			val property = base.mapping.property(ImportField.BLOCKED_BY) ?: continue
			for (page in base.adoptable) {
				for (target in relationIds(page, property)) {
					if (target == page.id) continue
					if (target in ticketIds) edges += PageDependency(predecessorPageId = target, successorPageId = page.id)
					else dropped++
				}
			}
		}
		return DependencyResult(edges, dropped)
	}

	/** Every page id one named relation column points at, read straight off the page's own JSON. */
	private fun relationIds(page: NotionPage, property: String): List<String> = page.properties?.properties()
		?.firstOrNull { it.key.equals(property, ignoreCase = true) }
		?.value?.path("relation")?.mapNotNull { it.path("id").asText(null) }
		.orEmpty()
}

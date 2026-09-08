package dev.kanso.domain

/**
 * How a grouped page buckets and stacks statuses — `KAN-28`.
 *
 * It used to be `StatusOrder.WORKFLOW` folded into a SQL `CASE`, which was right while
 * every team read the same six words in the same order. Two things broke that: a team can
 * reorder its own list, so the order is `team_statuses.position` and not a constant; and a
 * scope spanning teams cannot use either team's words as a header, so its buckets are the
 * five categories.
 *
 * Both are the same shape of answer — which bucket a status falls in, and where that
 * bucket sits — so both are this, and `TicketQueryRepository` keeps its opinion about how
 * to write a `CASE` and has none at all about what the order is.
 *
 * [bucketOf] maps a status key to the bucket it lands in: itself, for a team reading its
 * own list, or its category across teams. [rankOf] ranks the buckets. One-based, because
 * the rank is rendered as a `CASE` whose `Else` has to be a number larger than every
 * branch — a missing `WHEN` yields `NULL`, which Postgres sorts *first*.
 */
data class StatusGrouping(
	val bucketOf: Map<String, String>,
	val rankOf: Map<String, Int>,
) {

	/** True when a status is its own bucket, and the `CASE` can be the column itself. */
	val identity: Boolean get() = bucketOf.all { (status, bucket) -> status == bucket }

	companion object {

		/** A team reading its own list: its words, its order. */
		fun of(catalogue: List<TeamStatus>): StatusGrouping = StatusGrouping(
			bucketOf = catalogue.associate { it.key to it.key },
			rankOf = catalogue.mapIndexed { index, status -> status.key to index + 1 }.toMap(),
		)

		/**
		 * A scope spanning teams: the five categories, in `StatusOrder.CATEGORY_ORDER`.
		 *
		 * [catalogues] is every status row of every team in scope, and reading it is what
		 * `KAN-28` said `KAN-90` would have to do here — the shape it produces has not
		 * changed. Built from `DefaultStatus` it was exact only while the six keys were
		 * fixed; a team's seventh word would have had no bucket, so its rows would have
		 * grouped under no header and the page's counts would not have matched its rows.
		 *
		 * **Two teams may declare one key in two categories** — `statusKeyOf("Review")` is
		 * `review` for both, and one may call it started while the other calls it
		 * unstarted. `bucketOf` is keyed by the word alone, so it cannot hold both, and the
		 * first catalogue read wins. That is a real narrowing and it is the honest one
		 * available at this shape: the alternative is a per-team bucket map, which would
		 * make the header of a cross-team page depend on which team a row came from —
		 * which is precisely the thing categories exist to avoid. The six are seeded
		 * identically in every team, so the collision needs two teams to have invented the
		 * same word for different meanings.
		 *
		 * The default is folded in for a key no catalogue in scope declares: a row read
		 * through a path nobody has written yet groups as unstarted rather than vanishing.
		 */
		fun byCategory(catalogues: Collection<TeamStatus>): StatusGrouping = StatusGrouping(
			bucketOf = DefaultStatus.entries.associate { it.wire to it.category.wire } +
				catalogues.associate { it.key to it.category.wire },
			rankOf = StatusOrder.CATEGORY_ORDER.associate { it.wire to StatusOrder.rankOfCategory(it) },
		)

		/**
		 * The seed order, for a query that is not grouped by status at all.
		 *
		 * A flat list still orders by bucket before the view's sort — page contiguity
		 * depends on it — so a caller that never asked about statuses still needs an
		 * answer here, and the honest one is the order a team starts with.
		 */
		val SEEDED: StatusGrouping = StatusGrouping(
			bucketOf = StatusOrder.WORKFLOW.associate { it.wire to it.wire },
			rankOf = StatusOrder.WORKFLOW.associate { it.wire to StatusOrder.rankOf(it) },
		)
	}
}

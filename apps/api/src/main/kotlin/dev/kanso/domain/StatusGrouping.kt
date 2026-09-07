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
		 * Built from [DefaultStatus] rather than from the teams' catalogues, and that is
		 * exact rather than approximate *while the six keys are fixed* — which is the whole
		 * of `KAN-28`. `KAN-90` lets a team define a seventh, and this becomes a read of
		 * every catalogue in scope; the shape it produces does not change.
		 */
		fun byCategory(): StatusGrouping = StatusGrouping(
			bucketOf = DefaultStatus.entries.associate { it.wire to it.category.wire },
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

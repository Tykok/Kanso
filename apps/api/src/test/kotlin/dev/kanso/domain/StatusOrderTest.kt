package dev.kanso.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's half of the orders that cross the wire.
 *
 * There are two now — `KAN-28`. [StatusOrder.WORKFLOW] is the order a *new team* is
 * seeded in, and it stopped being the order grouped views read the day a team could
 * reorder its own list. [StatusOrder.CATEGORY_ORDER] is what took that job: it is what a
 * scope spanning two vocabularies groups by, and it can still be a constant on both sides
 * because the five categories are closed while a team's statuses are not.
 *
 * The sequences below are written out as literal strings rather than built from the
 * objects they check, because an assertion derived from the thing it checks passes
 * whatever that thing does. The identical literals are asserted in the web app's
 * `lib/status-order.test.ts`: between them, the copy that drifts turns its own side red.
 */
class StatusOrderTest {

	@Test
	fun `stacks the buckets downwards as the work flows`() {
		assertEquals(
			listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled"),
			StatusOrder.WORKFLOW.map { it.wire },
		)
	}

	/**
	 * The guard that makes a seventh status a decision instead of an omission. Adding one
	 * to the enum turns this red, and the fix is to say where it reads — which is the
	 * whole point of the order being written down rather than derived.
	 */
	@Test
	fun `names every status exactly once`() {
		assertEquals(DefaultStatus.entries.toSet(), StatusOrder.WORKFLOW.toSet())
		assertEquals(DefaultStatus.entries.size, StatusOrder.WORKFLOW.size)
	}

	@Test
	fun `ranks one-based, in that order`() {
		assertEquals(
			listOf(1, 2, 3, 4, 5, 6),
			StatusOrder.WORKFLOW.map { StatusOrder.rankOf(it) },
		)
	}

	/**
	 * The `Else` branch of the SQL `CASE`, asserted as a number rather than as a `NULL`.
	 * Postgres sorts `NULL` first by default, so a status the ranking has not been told
	 * about must come back as a large integer or it would head the list.
	 */
	@Test
	fun `sorts an unplaced status after every placed one`() {
		val unplaced = StatusOrder.UNPLACED

		assertEquals(StatusOrder.WORKFLOW.size + 1, unplaced)
		assertTrue(StatusOrder.WORKFLOW.all { StatusOrder.rankOf(it) < unplaced })
	}

	/**
	 * Membership is the category's business and sequence is this object's, and the two are
	 * not the same question — the reason `KAN-42` split them on the client. Every status
	 * the order places has a meaning, and no meaning is left without a place.
	 */
	@Test
	fun `places every status whatever its category says it means`() {
		for (category in StatusCategory.entries) {
			val statuses = DefaultStatus.entries.filter { it.category == category }
			assertTrue(
				statuses.all { StatusOrder.rankOf(it) < StatusOrder.UNPLACED },
				"$category has a status the workflow order does not place",
			)
		}
	}

	@Test
	fun `groups a scope that spans teams by category, in this order`() {
		assertEquals(
			listOf("backlog", "unstarted", "started", "completed", "canceled"),
			StatusOrder.CATEGORY_ORDER.map { it.wire },
		)
	}

	@Test
	fun `ranks every category once, so no two buckets can collide`() {
		val ranks = StatusCategory.entries.map { StatusOrder.rankOfCategory(it) }

		assertEquals(ranks.distinct(), ranks)
		assertEquals(StatusCategory.entries.size, ranks.size)
		// One-based, like `rankOf`: the rank is rendered as a SQL `CASE` whose `Else` has
		// to be a number larger than every branch, and zero would tie with it.
		assertTrue(ranks.all { it > 0 })
	}
}

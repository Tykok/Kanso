package dev.kanso.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's half of the one status order that crosses the wire.
 *
 * The sequence below is written out as literal wire strings rather than built from
 * [StatusOrder.WORKFLOW], because an assertion derived from the thing it checks passes
 * whatever that thing does. The identical literal is asserted in the web app's
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
		assertEquals(TicketStatus.entries.toSet(), StatusOrder.WORKFLOW.toSet())
		assertEquals(TicketStatus.entries.size, StatusOrder.WORKFLOW.size)
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
			val statuses = TicketStatus.entries.filter { it.category == category }
			assertTrue(
				statuses.all { StatusOrder.rankOf(it) < StatusOrder.UNPLACED },
				"$category has a status the workflow order does not place",
			)
		}
	}
}

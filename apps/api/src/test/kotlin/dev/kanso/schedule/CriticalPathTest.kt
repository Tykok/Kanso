package dev.kanso.schedule

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CriticalPathTest {

	private fun day(d: Int): OffsetDateTime =
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC)

	private val a = UUID.randomUUID()
	private val b = UUID.randomUUID()
	private val c = UUID.randomUUID()
	private val loner = UUID.randomUUID()

	@Test
	fun `the longest chain has zero slack and the shorter branch has some`() {
		// A -> C and B -> C. A ends on the 10th, B on the 4th, C runs 10th to 12th.
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(1), day(4), done = false),
			Node(c, day(10), day(12), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, c), Edge(b, c)), emptyMap())

		assertEquals(Duration.ZERO, slack[a], "A binds C, so A is on the critical path")
		assertEquals(Duration.ZERO, slack[c], "and C ends the chain")
		assertEquals(Duration.ofDays(6), slack[b], "B could run six days later without moving C")
	}

	@Test
	fun `a ticket with no dependencies has no slack at all`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(loner, day(1), day(30), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, loner)).drop(1), emptyMap())

		assertFalse(loner in slack, "painting a ticket red says something about dependencies it does not have")
	}

	@Test
	fun `an explicit project deadline tightens the anchor and can make slack negative`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(10), day(20), done = false),
		)

		val onTime = CriticalPath.slack(nodes, listOf(Edge(a, b)), emptyMap())
		assertEquals(Duration.ZERO, onTime[b], "with no deadline the anchor is the chain's own end")

		val late = CriticalPath.slack(nodes, listOf(Edge(a, b)), mapOf(b to day(15)))
		assertTrue(late.getValue(b).isNegative, "the chain overruns the deadline by five days")
		assertEquals(Duration.ofDays(-5), late.getValue(b))
	}

	@Test
	fun `the tightest deadline in the component wins, even across projects`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(10), day(20), done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, b)), mapOf(a to day(12), b to day(30)))

		assertEquals(
			Duration.ofDays(-8),
			slack.getValue(b),
			"a chain crossing two projects takes the tighter of the two ends",
		)
	}

	@Test
	fun `unscheduled tickets take no part in the computation`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, null, null, done = false),
		)

		val slack = CriticalPath.slack(nodes, listOf(Edge(a, b)), emptyMap())

		assertFalse(b in slack, "a ticket with no dates has no slack to report")
	}
}

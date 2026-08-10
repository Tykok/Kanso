package dev.kanso.schedule

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CascadeTest {

	private fun day(d: Int): OffsetDateTime =
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC)

	private val a = UUID.randomUUID()
	private val b = UUID.randomUUID()
	private val c = UUID.randomUUID()

	@Test
	fun `slack absorbs the move and nothing shifts`() {
		val nodes = listOf(
			Node(a, day(1), day(10), done = false),
			Node(b, day(15), day(20), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "B keeps five days of slack, so B keeps its dates")
	}

	@Test
	fun `an overlap pushes the successor by exactly the overlap and keeps its duration`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		val moved = result.moved.single()
		assertEquals(b, moved.id)
		assertEquals(day(18), moved.start, "B starts when A ends, not a day later")
		assertEquals(day(23), moved.end, "five days long before, five days long after")
	}

	@Test
	fun `the push carries down the chain and stops where slack absorbs it`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = false),
			Node(c, day(28), day(30), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b), Edge(b, c)), changedId = a)

		assertEquals(listOf(b), result.moved.map { it.id }, "B ends on the 23rd, C starts on the 28th")
	}

	@Test
	fun `a done successor is never moved and its edge is reported violated`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(15), day(20), done = true),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "finished work does not get rewritten")
		assertEquals(setOf(Edge(a, b)), result.violated, "and the plan must say so rather than pretend")
	}

	@Test
	fun `nothing is ever pulled backwards`() {
		val nodes = listOf(
			Node(a, day(1), day(3), done = false),
			Node(b, day(20), day(25), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		assertEquals(emptyList(), result.moved, "freeing slack is not a reason to drag work into the past")
	}

	@Test
	fun `an unscheduled successor stops the descent`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, null, null, done = false),
			Node(c, day(2), day(4), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b), Edge(b, c)), changedId = a)

		assertEquals(emptyList(), result.moved, "an undated ticket has nothing to move, and blocks nothing")
	}

	@Test
	fun `a ticket with one bound is a milestone and keeps its zero duration`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, null, day(15), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b)), changedId = a)

		val moved = result.moved.single()
		assertEquals(day(18), moved.start)
		assertEquals(day(18), moved.end, "a milestone has no length to preserve")
	}

	@Test
	fun `a node with two predecessors settles once, against the later one`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(1), day(22), done = false),
			Node(c, day(5), day(6), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, c), Edge(b, c)), changedId = a)

		val moved = result.moved.single { it.id == c }
		assertEquals(day(22), moved.start, "the binding constraint is the later predecessor")
		assertTrue(result.moved.count { it.id == c } == 1, "and C is written once, not twice")
	}

	@Test
	fun `a violation that predates the edit is left alone`() {
		// B has slack and does not move, so C — already overlapping B before anyone
		// touched anything — is none of this edit's business.
		val nodes = listOf(
			Node(a, day(1), day(3), done = false),
			Node(b, day(20), day(25), done = false),
			Node(c, day(1), day(2), done = false),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, b), Edge(b, c)), changedId = a)

		assertEquals(
			emptyList(),
			result.moved,
			"the descent stops at B, so C keeps the dates it had — repairing it here would move a ticket nobody touched",
		)
	}

	@Test
	fun `every edge a done ticket breaks is reported, not just the binding one`() {
		val nodes = listOf(
			Node(a, day(1), day(18), done = false),
			Node(b, day(1), day(22), done = false),
			Node(c, day(15), day(16), done = true),
		)

		val result = Cascade.apply(nodes, listOf(Edge(a, c), Edge(b, c)), changedId = a)

		assertEquals(
			setOf(Edge(a, c), Edge(b, c)),
			result.violated,
			"two broken promises are two red arrows; drawing one under-reports the damage",
		)
	}
}

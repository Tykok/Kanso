package dev.kanso.github

import dev.kanso.domain.MirrorInfo
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.service.TicketDetail
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The branch name a ticket offers, which is the whole of "branch from a ticket" —
 * `[Open the PR]` and the writes behind it are part five.
 *
 * A plain unit test with a hand-built row rather than a `PostgresTest`: the value is
 * **derived on read** from two columns, so there is nothing about it a database could
 * confirm, and the cases worth asserting are all about punctuation in a title.
 */
class BranchNameTest {

	private fun detail(title: String, number: Int? = 142, teamKey: String? = "KAN") = TicketDetail(
		ticket = Ticket(
			id = UUID.randomUUID(),
			number = number,
			teamId = teamKey?.let { UUID.randomUUID() },
			createdBy = null,
			title = title,
			description = null,
			status = "todo",
			priority = TicketPriority.MEDIUM,
			estimate = null,
			start = null,
			due = null,
			completedAt = null,
			projectId = null,
			parentId = null,
			archived = false,
			mirror = MirrorInfo(),
			createdAt = OffsetDateTime.now(),
			updatedAt = OffsetDateTime.now(),
		),
		teamKey = teamKey,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `the identifier lowercased, then the title slugged`() {
		assertEquals(
			"feat/kan-142-overlap-warning",
			detail("Overlap warning").branchName,
			"the example the design uses throughout",
		)
	}

	/**
	 * Two passes — replace then collapse — rather than one clever pattern. A single
	 * character-class substitution would leave `a-b--testing--round-2`, which is a valid
	 * branch name and an ugly one, and `git switch -c` with a double hyphen is the kind of
	 * paste people silently fix by hand instead of reporting.
	 */
	@Test
	fun `punctuation collapses instead of doubling up`() {
		assertEquals("feat/kan-142-a-b-testing-round-2", detail("A/B testing: round 2").branchName)
	}

	@Test
	fun `accents and symbols do not reach a branch name`() {
		val name = detail("Réparer l'export CSV (urgent!)").branchName!!
		assertEquals("feat/kan-142-r-parer-l-export-csv-urgent", name)
		assertTrue(
			name.all { it.isLetterOrDigit() || it == '-' || it == '/' },
			"a branch name is pasted into a shell: $name",
		)
	}

	@Test
	fun `leading and trailing separators are trimmed, not left dangling`() {
		assertEquals("feat/kan-142-cleanup", detail("...cleanup!!!").branchName)
	}

	/**
	 * A title with nothing sluggable is the case that would otherwise produce a trailing
	 * hyphen — `feat/kan-142-` — which git accepts and nobody wants.
	 */
	@Test
	fun `a title that slugs to nothing leaves the identifier alone`() {
		assertEquals("feat/kan-142", detail("!!!").branchName)
	}

	@Test
	fun `a long title is bounded and does not end on a separator`() {
		val name = detail("A ".repeat(60)).branchName!!
		assertTrue(name.length <= "feat/kan-142-".length + 48, "unbounded: $name")
		assertTrue(!name.endsWith("-"), "ends on a separator: $name")
	}

	/**
	 * A draft has no identifier, so there is no branch to name after it. Null rather than a
	 * placeholder, for the reason [TicketDetail] gives about names that change.
	 */
	@Test
	fun `a draft offers no branch`() {
		assertNull(detail("Something", number = null, teamKey = null).branchName)
	}
}

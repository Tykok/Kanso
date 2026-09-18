package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.TicketQueryRepository
import dev.kanso.repo.TicketScope
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grouping, counted by the database over everything that matches.
 *
 * The bug this suite exists to keep closed is not slowness, it is a wrong answer: the
 * client used to bucket whatever page it happened to hold, so `Todo · 12` meant "twelve
 * of the two hundred rows I was sent" while the question had two thousand. Every count
 * asserted below is therefore asserted against a page too small to contain it — a test
 * whose page holds the whole match cannot tell the two readings apart.
 */
@Transactional
class TicketGroupingTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var groups: TicketGroups
	@Autowired lateinit var query: TicketQueryRepository
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "grouping-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.ADMIN,
	)

	private val admin: User by lazy { person("Grouper") }

	private val team by lazy {
		teams.create(admin, "Grouping", "G${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(
		title: String,
		status: String = "todo",
		priority: TicketPriority = TicketPriority.NONE,
		projectId: UUID? = null,
		assignees: List<UUID> = emptyList(),
	) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = status,
		priority = priority,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = assignees,
		docIds = emptyList(),
	)

	private val scope get() = TicketScope(listOf(team.id))

	private fun grouped(
		groupBy: ViewGroupBy,
		sortBy: ViewSortBy = ViewSortBy.UPDATED,
		limit: Int = 200,
		offset: Long = 0,
		filters: TicketFilters = TicketFilters(),
	) = groups.of(scope, filters, groupBy, sortBy, limit, offset)

	// --- the buckets ---------------------------------------------------------

	@Test
	fun `groups by status in the order the work flows, not alphabetically`() {
		ticket("Echo suppression drops our own writes", status = "done")
		ticket("Reconnect storms the socket", status = "backlog")
		ticket("Cursor jumps on a remote rename", status = "in_progress")
		ticket("Presence ghosts survive a refresh", status = "in_progress")

		val answer = grouped(ViewGroupBy.STATUS)

		assertEquals(listOf("backlog", "in_progress", "done"), answer.map { it.key })
		assertEquals(listOf(1, 2, 1), answer.map { it.count })
	}

	@Test
	fun `groups by priority, urgent first`() {
		ticket("Low", priority = TicketPriority.LOW)
		ticket("Urgent", priority = TicketPriority.URGENT)
		ticket("Medium", priority = TicketPriority.MEDIUM)

		assertEquals(listOf("urgent", "medium", "low"), grouped(ViewGroupBy.PRIORITY).map { it.key })
	}

	@Test
	fun `groups by project, and the ones in none land in a bucket of their own`() {
		val alpha = projects.create(actor = admin, "Alpha", ProjectStatus.PLANNED, null, null, null, team.id, emptyList())
		ticket("In a project", projectId = alpha.project.id)
		ticket("In no project")

		val answer = grouped(ViewGroupBy.PROJECT)

		assertEquals(setOf(alpha.project.id.toString(), ""), answer.map { it.key }.toSet())
		assertEquals(1, answer.single { it.key == "" }.count)
	}

	/**
	 * The unassigned pile sorts last, as it does on screen: it is the leftovers, and a
	 * nameless bucket at the top reads as a group whose name failed to load.
	 */
	@Test
	fun `groups by assignee, with the unassigned pile last`() {
		val owner = person("A. Okonkwo")
		ticket("Owned", assignees = listOf(owner.id))
		ticket("Nobody's")

		val answer = grouped(ViewGroupBy.ASSIGNEE)

		assertEquals(listOf(owner.id.toString(), ""), answer.map { it.key })
	}

	/** `none` is a real choice on the control: one flat bucket, not zero. */
	@Test
	fun `groups not at all into exactly one bucket`() {
		ticket("One")
		ticket("Two")

		val answer = grouped(ViewGroupBy.NONE)

		assertEquals(1, answer.size)
		assertEquals("", answer.single().key)
		assertEquals(2, answer.single().count)
	}

	@Test
	fun `has no empty buckets, so nothing draws a header over nothing`() {
		ticket("Only one, and it is todo")

		assertEquals(listOf("todo"), grouped(ViewGroupBy.STATUS).map { it.key })
	}

	@Test
	fun `answers nothing for nothing`() {
		assertEquals(emptyList(), grouped(ViewGroupBy.STATUS))
	}

	// --- the counts ----------------------------------------------------------

	/**
	 * The whole point. Five rows match, the page carries two, and the header still has to
	 * say five — which is precisely what the client-side bucketing could not do.
	 */
	@Test
	fun `counts the whole match even when the page holds a fraction of it`() {
		repeat(5) { ticket("Todo $it", status = "todo") }

		val answer = grouped(ViewGroupBy.STATUS, limit = 2)

		assertEquals(5, answer.single().count)
		assertEquals(2, answer.single().tickets.size)
	}

	/** A count is of the question asked, so a chip has to move it. */
	@Test
	fun `counts what the filters left, not what the team holds`() {
		ticket("Urgent", status = "todo", priority = TicketPriority.URGENT)
		ticket("Quiet", status = "todo", priority = TicketPriority.LOW)

		val answer = grouped(
			ViewGroupBy.STATUS,
			filters = TicketFilters(priorities = listOf(TicketPriority.URGENT)),
		)

		assertEquals(1, answer.single().count)
	}

	// --- the rows ------------------------------------------------------------

	/**
	 * Grouping is an ordering before it is a shape: the rows come back stacked by group,
	 * so page two continues the group page one ended in rather than restarting the
	 * question. Without it a page boundary could put half of `Todo` under `Done`.
	 */
	@Test
	fun `orders the rows by group first, so a page never interleaves two of them`() {
		repeat(3) { ticket("Backlog $it", status = "backlog") }
		repeat(3) { ticket("Done $it", status = "done") }

		val page = query.matching(scope, TicketFilters(), ViewGroupBy.STATUS, ViewSortBy.UPDATED, limit = 6)

		assertEquals(
			listOf("backlog", "backlog", "backlog", "done", "done", "done"),
			page.map { it.status },
		)
	}

	/**
	 * Two pages of three over six rows: every ticket exactly once. A sort that is not
	 * total repeats a row on both pages and drops another from both, and the reader sees
	 * neither happen — which is why this is asserted rather than assumed.
	 */
	@Test
	fun `pages a tie without repeating or skipping a row`() {
		// All six share a priority, so `priority` alone cannot order them and the tie-break
		// is the only thing standing between the two pages and a lost row.
		repeat(6) { ticket("Tied $it", priority = TicketPriority.HIGH) }

		val first = grouped(ViewGroupBy.NONE, ViewSortBy.PRIORITY, limit = 3, offset = 0)
		val second = grouped(ViewGroupBy.NONE, ViewSortBy.PRIORITY, limit = 3, offset = 3)
		val seen = (first.single().tickets + second.single().tickets).map { it.ticket.id }

		assertEquals(6, seen.size)
		assertEquals(6, seen.toSet().size)
	}

	/** The documented tie-break, asserted as a promise rather than as an accident. */
	@Test
	fun `breaks a tie on the ticket number, newest first`() {
		val older = ticket("Tied, older", priority = TicketPriority.HIGH)
		val newer = ticket("Tied, newer", priority = TicketPriority.HIGH)

		val ordered = grouped(ViewGroupBy.NONE, ViewSortBy.PRIORITY).single().tickets

		assertEquals(listOf(newer.ticket.id, older.ticket.id), ordered.map { it.ticket.id })
	}

	/** The same question, two shapes. They may order differently; they may not disagree. */
	@Test
	fun `a grouped answer and a flat one hold exactly the same rows`() {
		ticket("Backlog", status = "backlog")
		ticket("Todo", status = "todo")
		ticket("Done", status = "done")

		val flat = tickets.list(team.id, false, false, TicketFilters(), ViewSortBy.UPDATED, 200, 0)
		val stacked = grouped(ViewGroupBy.STATUS).flatMap { it.tickets }

		assertEquals(flat.map { it.ticket.id }.toSet(), stacked.map { it.ticket.id }.toSet())
		assertEquals(flat.size, stacked.size)
	}

	/**
	 * A bucket the page did not reach still carries its true count and an empty list —
	 * it is not silently dropped. That is what lets a header say `Done · 3` above a group
	 * whose rows are still one scroll away.
	 */
	@Test
	fun `names a bucket the page never reached, with its count and no rows`() {
		repeat(2) { ticket("Backlog $it", status = "backlog") }
		repeat(3) { ticket("Done $it", status = "done") }

		val answer = grouped(ViewGroupBy.STATUS, limit = 2)

		assertEquals(listOf("backlog", "done"), answer.map { it.key })
		assertEquals(2, answer.first().tickets.size)
		assertEquals(3, answer.last().count)
		assertTrue(answer.last().tickets.isEmpty())
	}

	/** Grouping must not reorder inside a group, or `sortBy` stops working for grouped views. */
	@Test
	fun `keeps the view's sort inside each group`() {
		ticket("Low but todo", status = "todo", priority = TicketPriority.LOW)
		val urgent = ticket("Urgent and todo", status = "todo", priority = TicketPriority.URGENT)

		val todo = grouped(ViewGroupBy.STATUS, ViewSortBy.PRIORITY).single { it.key == "todo" }

		assertEquals(urgent.ticket.id, todo.tickets.first().ticket.id)
	}

	// --- the saved view ------------------------------------------------------

	/**
	 * A saved view stores `groupBy` and `sortBy` and has always drawn them; it now asks
	 * the server for the stacking instead of doing it itself, and the stored question is
	 * still the only thing that decides.
	 */
	@Test
	fun `a saved view groups the way it was stored`() {
		ticket("Backlog", status = "backlog")
		ticket("Done", status = "done")
		val view = views.create(
			actor = admin,
			teamId = team.id,
			name = "Everything-${UUID.randomUUID()}",
			shared = true,
			filters = emptyMap(),
			groupBy = ViewGroupBy.STATUS,
			sortBy = ViewSortBy.PRIORITY,
		)

		assertEquals(listOf("backlog", "done"), views.grouped(admin, view.id).map { it.key })
	}

	/**
	 * A view answers one number through every door it has.
	 *
	 * This used to assert that the flat `views.tickets(id)` was untouched by the grouped
	 * answer beside it. That door is gone — nothing called the route it served — so what is
	 * left to hold is the identity that replaced it: the sidebar's count, the sum of the
	 * buckets and the buckets laid end to end all describe the same match.
	 */
	@Test
	fun `a view's count, its buckets and its rows are one answer`() {
		ticket("Done", status = "done")
		ticket("Open", status = "todo")
		val view = views.create(
			actor = admin,
			teamId = team.id,
			name = "Open-${UUID.randomUUID()}",
			shared = true,
			filters = mapOf("statusNot" to listOf("done")),
			groupBy = ViewGroupBy.STATUS,
			sortBy = ViewSortBy.PRIORITY,
		)

		assertEquals(1, views.rows(admin, view.id).size)
		assertEquals(1, views.count(admin, view.id))
		assertEquals(1, views.grouped(admin, view.id).sumOf { it.count })
	}

	/**
	 * The sidebar count and the header count are the same number, and both are the
	 * database's — the header used to be the size of a page of rows, which is a count of a
	 * page and stops growing at 200.
	 */
	@Test
	fun `a view's count is of the match, not of a page of it`() {
		repeat(3) { ticket("Row $it") }
		val view = views.create(
			actor = admin,
			teamId = team.id,
			name = "Counted-${UUID.randomUUID()}",
			shared = true,
			filters = emptyMap(),
			groupBy = ViewGroupBy.NONE,
			sortBy = ViewSortBy.UPDATED,
		)

		assertEquals(3, views.count(admin, view.id))
		assertEquals(3, views.grouped(admin, view.id, limit = 1).single().count)
		assertEquals(1, views.grouped(admin, view.id, limit = 1).single().tickets.size)
	}
}

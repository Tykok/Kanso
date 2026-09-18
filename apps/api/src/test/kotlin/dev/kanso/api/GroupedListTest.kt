package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.SavedViewService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `GET /api/tickets/grouped` and `GET /api/views/{id}/grouped` — the walls of the room.
 *
 * The buckets themselves are `TicketGroupingTest`'s subject. What is only decidable at
 * this layer is what the route does with a page size nobody should have sent, and that
 * the grouped door is held to exactly the same filter vocabulary as the flat one beside
 * it: two doors onto one question that disagreed about which questions exist would be
 * the divergence the shared gate was built to end.
 */
@Transactional
class GroupedListTest : PostgresTest() {

	@Autowired lateinit var controller: TicketController
	@Autowired lateinit var viewController: SavedViewController
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "grouped-${UUID.randomUUID()}@kanso.test",
			displayName = "Grouped",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Grouped", "H${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String, status: String = "todo") = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	/**
	 * Stands in for the auth filter, which has no servlet request here to run inside.
	 *
	 * Only [SavedViewController] needs it: a saved view is read against the caller now that
	 * `shared = false` means what `V10` always said it did, so the route asks who is calling.
	 * [TicketController]'s grouped door takes no actor and the tests around it are unchanged.
	 */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	private fun params(vararg pairs: Pair<String, String>): MultiValueMap<String, String> =
		LinkedMultiValueMap<String, String>().apply { pairs.forEach { (k, v) -> add(k, v) } }

	private fun grouped(
		groupBy: String = "status",
		limit: Int = 200,
		offset: Long = 0,
		query: MultiValueMap<String, String> = params(),
	) = controller.grouped(
		teamId = team.id,
		includeDescendants = false,
		includeArchived = false,
		groupBy = groupBy,
		sort = "updated",
		limit = limit,
		offset = offset,
		query = query,
	)

	@Test
	fun `hands back the buckets with their counts and the page's rows`() {
		repeat(4) { ticket("Todo $it") }
		ticket("Done", status = "done")

		val answer = grouped(limit = 2)

		assertEquals("status", answer.groupBy)
		assertEquals(5, answer.total)
		assertEquals(listOf("todo", "done"), answer.groups.map { it.key })
		assertEquals(listOf(4, 1), answer.groups.map { it.count })
		// Two rows asked for, two rows carried — and the bucket still says four.
		assertEquals(2, answer.groups.sumOf { it.tickets.size })
	}

	/** `total` is the sum of the buckets, so the two numbers on screen cannot disagree. */
	@Test
	fun `totals what the buckets total`() {
		repeat(3) { ticket("Row $it") }

		val answer = grouped()

		assertEquals(answer.groups.sumOf { it.count }, answer.total)
	}

	/**
	 * The same clamp the flat list has always applied. A page size is scope, not a
	 * question, so an absurd one is corrected rather than refused — but it is corrected,
	 * because `limit=100000` on a grown instance is a request nobody meant to make.
	 */
	@Test
	fun `clamps the page size the way the flat list does`() {
		repeat(3) { ticket("Row $it") }

		// Below the floor: one row, not zero and not an error.
		assertEquals(1, grouped(limit = 0).groups.sumOf { it.tickets.size })
		// Above the ceiling: everything there is, and no exception on the way.
		assertEquals(3, grouped(limit = 100_000).groups.sumOf { it.tickets.size })
	}

	@Test
	fun `pages with an offset, and a negative one reads as none`() {
		repeat(3) { ticket("Row $it") }

		assertEquals(1, grouped(limit = 2, offset = 2).groups.sumOf { it.tickets.size })
		assertEquals(3, grouped(offset = -5).groups.sumOf { it.tickets.size })
	}

	/**
	 * The gate, on this door too. A name nobody serves is a 400 here exactly as it is on
	 * the flat list: a grouped page that looks filtered and is not would be worse, because
	 * the reader has the counts to trust as well as the rows.
	 */
	@Test
	fun `refuses a filter nobody serves`() {
		assertFailsWith<BadRequestException> { grouped(query = params("labelColour" to "indigo")) }
	}

	@Test
	fun `refuses a grouping nobody draws`() {
		assertFailsWith<IllegalArgumentException> { grouped(groupBy = "colour") }
	}

	/** The facets the flat list answers, the grouped one answers — same parse, same names. */
	@Test
	fun `answers the same facets as the flat list`() {
		ticket("Done", status = "done")
		ticket("Open")

		val answer = grouped(query = params("statusNot" to "done"))

		assertEquals(1, answer.total)
		assertEquals(listOf("todo"), answer.groups.map { it.key })
	}

	@Test
	fun `a view's grouped answer is the view's own question, stacked its own way`() {
		ticket("Backlog", status = "backlog")
		ticket("Done", status = "done")
		val view = views.create(
			actor = admin,
			teamId = team.id,
			name = "Stacked-${UUID.randomUUID()}",
			shared = true,
			filters = mapOf("statusNot" to listOf("done")),
			groupBy = ViewGroupBy.STATUS,
			sortBy = ViewSortBy.PRIORITY,
		)

		actAs(admin)

		val answer = viewController.grouped(view.id, limit = 200, offset = 0)

		assertEquals("status", answer.groupBy)
		assertEquals("priority", answer.sortBy)
		assertEquals(listOf("backlog"), answer.groups.map { it.key })
		assertEquals(1, answer.total)
	}
}

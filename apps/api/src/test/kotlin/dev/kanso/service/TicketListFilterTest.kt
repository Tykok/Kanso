package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.api.TicketController
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `GET /api/tickets` and a saved view are one question asked twice.
 *
 * Every test here pins the property the merge exists for: a filter answers the same way
 * whichever door it came through. The suite is written against the controller rather
 * than the repository because the query string is the half that was poor — the predicate
 * could already do all of this for a saved view, and the list could not ask for it.
 */
@Transactional
class TicketListFilterTest : PostgresTest() {

	@Autowired lateinit var controller: TicketController
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var labels: LabelService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var bulk: BulkEditService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: org.springframework.security.crypto.password.PasswordEncoder

	private fun user(role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "list-${UUID.randomUUID()}@kanso.test",
		displayName = "List ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "L${UUID.randomUUID().toString().take(4).uppercase()}"

	private val team by lazy { teams.create(admin, "Listing", key(), null) }

	private fun ticket(
		title: String,
		status: TicketStatus = TicketStatus.TODO,
		priority: TicketPriority = TicketPriority.NONE,
		teamId: UUID = team.id,
		assignees: List<UUID> = emptyList(),
		projectId: UUID? = null,
		estimate: Int? = null,
	) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = title,
		description = null,
		status = status,
		priority = priority,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	).ticket.id

	/**
	 * The controller's own door. Spring binds the query string into a [MultiValueMap]; the
	 * tests build one directly because the point under test is what the *names* mean, not
	 * how Tomcat splits them.
	 */
	private fun params(vararg pairs: Pair<String, Any?>): MultiValueMap<String, String> {
		val map = LinkedMultiValueMap<String, String>()
		pairs.forEach { (name, value) ->
			when (value) {
				is Iterable<*> -> value.forEach { map.add(name, it.toString()) }
				else -> map.add(name, value.toString())
			}
		}
		return map
	}

	/** `GET /api/tickets?teamId=…&<filters>`, answered as the ids it would draw. */
	private fun list(
		vararg filters: Pair<String, Any?>,
		teamId: UUID? = team.id,
		includeDescendants: Boolean = false,
		includeArchived: Boolean = false,
		limit: Int = 200,
		offset: Long = 0,
		sort: String = "updated",
	): List<UUID> = controller.list(
		teamId = teamId,
		includeDescendants = includeDescendants,
		includeArchived = includeArchived,
		limit = limit,
		offset = offset,
		sort = sort,
		query = params(*filters),
	).map { it.id }

	// --- the nine-plus filters, each now reachable from the list ---------------

	@Test
	fun `the list filters by status, the way it always could`() {
		val doing = ticket("doing", TicketStatus.IN_PROGRESS)
		ticket("waiting", TicketStatus.TODO)

		assertEquals(listOf(doing), list("status" to "in_progress"))
	}

	@Test
	fun `the list can ask for not-done, which it could not before`() {
		val open = ticket("open", TicketStatus.IN_PROGRESS)
		ticket("finished", TicketStatus.DONE)

		assertEquals(
			listOf(open),
			list("statusNot" to "done"),
			"`Statut ≠ Done` is the shape the sidebar is full of, and the list had no way to say it",
		)
	}

	@Test
	fun `the list filters by priority, which it could not before`() {
		val urgent = ticket("urgent", priority = TicketPriority.URGENT)
		ticket("low", priority = TicketPriority.LOW)

		assertEquals(listOf(urgent), list("priority" to "urgent"))
	}

	@Test
	fun `the list filters by project, and by several at once`() {
		val one = project("One")
		val two = project("Two")
		val a = ticket("a", projectId = one)
		val b = ticket("b", projectId = two)
		ticket("loose")

		assertEquals(listOf(a), list("project" to one.toString()))
		assertEquals(
			setOf(a, b),
			list("project" to listOf(one.toString(), two.toString())).toSet(),
			"two values of one chip read as `either`, the same way the priority chip's do",
		)
	}

	@Test
	fun `the list filters by assignee`() {
		val mine = user()
		val owned = ticket("owned", assignees = listOf(mine.id))
		ticket("nobody's")

		assertEquals(listOf(owned), list("assignee" to mine.id.toString()))
	}

	@Test
	fun `the list can ask for the unassigned, which it could not before`() {
		val mine = user()
		ticket("owned", assignees = listOf(mine.id))
		val orphan = ticket("nobody's")

		assertEquals(listOf(orphan), list("unassigned" to true))
	}

	@Test
	fun `the list filters by cycle, which it could not before`() {
		val cycle = cycles.create(
			actor = admin,
			teamId = team.id,
			number = 24,
			startsOn = LocalDate.now(),
			endsOn = LocalDate.now().plusDays(13),
			state = CycleState.ACTIVE,
		)
		val committed = ticket("committed")
		ticket("not committed")
		bulk.apply(admin, BulkEdit(ticketIds = listOf(committed), cycleId = cycle.id))

		assertEquals(listOf(committed), list("cycle" to cycle.id.toString()))
	}

	@Test
	fun `the list filters by label, which it could not before`() {
		val sync = labels.create(admin, team.id, "sync", "indigo")
		val wearing = ticket("wearing it")
		ticket("bare")
		labels.attach(admin, wearing, sync.id)

		assertEquals(listOf(wearing), list("label" to sync.id.toString()))
	}

	@Test
	fun `the list filters by age, which it could not before`() {
		val old = ticket("opened a while ago")
		ticket("opened just now")
		agedByDays(old, 5)

		assertEquals(
			listOf(old),
			list("openedForDays" to 3),
			"`Blocked for 3 days` is measured from creation, not from the last edit",
		)
	}

	@Test
	fun `the list filters by the three estimate questions, which it could not before`() {
		val unsized = ticket("nobody sized it")
		val small = ticket("small", estimate = 1)
		val big = ticket("big", estimate = 8)

		assertEquals(listOf(unsized), list("unestimated" to true))
		assertEquals(listOf(big), list("estimateMin" to 5))
		assertEquals(listOf(small), list("estimateMax" to 3))
		assertEquals(
			setOf(small, big),
			list("estimateMin" to 1, "estimateMax" to 8).toSet(),
			"both bounds may be asked at once, and neither of them may claim the unsized one",
		)
	}

	@Test
	fun `two chips on the list narrow together, not separately`() {
		val both = ticket("urgent and doing", TicketStatus.IN_PROGRESS, TicketPriority.URGENT)
		ticket("urgent and done", TicketStatus.DONE, TicketPriority.URGENT)
		ticket("low and doing", TicketStatus.IN_PROGRESS, TicketPriority.LOW)

		assertEquals(listOf(both), list("statusNot" to "done", "priority" to "urgent"))
	}

	// --- one vocabulary, one answer -------------------------------------------

	/**
	 * The whole ticket, in one assertion: the same question asked through the list and
	 * through a saved view has to come back with the same rows. Anything that drifts
	 * between the two predicates fails here first.
	 */
	@Test
	fun `the list and a saved view answer a shared question identically`() {
		val sync = labels.create(admin, team.id, "sync", "indigo")
		val wanted = ticket("urgent, labelled, open", TicketStatus.IN_PROGRESS, TicketPriority.URGENT)
		labels.attach(admin, wanted, sync.id)
		ticket("urgent, labelled, done", TicketStatus.DONE, TicketPriority.URGENT).also {
			labels.attach(admin, it, sync.id)
		}
		ticket("urgent, unlabelled, open", TicketStatus.IN_PROGRESS, TicketPriority.URGENT)

		val saved = views.create(
			actor = admin,
			teamId = team.id,
			name = "Same question ${UUID.randomUUID()}",
			shared = true,
			filters = mapOf(
				"statusNot" to listOf("done"),
				"priority" to listOf("urgent"),
				"label" to listOf(sync.id.toString()),
			),
			groupBy = ViewGroupBy.NONE,
			sortBy = ViewSortBy.UPDATED,
		)

		assertEquals(
			views.tickets(saved.id).map { it.ticket.id },
			list(
				"statusNot" to "done",
				"priority" to "urgent",
				"label" to sync.id.toString(),
				teamId = team.id,
				includeDescendants = true,
			),
			"one predicate or two is exactly the difference this ticket exists to remove",
		)
		assertEquals(listOf(wanted), views.tickets(saved.id).map { it.ticket.id })
	}

	// --- the gate --------------------------------------------------------------

	@Test
	fun `a filter name nobody serves is refused by the list, not quietly ignored`() {
		ticket("visible")

		val error = assertFailsWith<BadRequestException> { list("labelColour" to "indigo") }

		assertTrue(
			error.message!!.contains("labelColour"),
			"a query string that looks filtered and is not is the same lie as a chip that does not filter",
		)
	}

	@Test
	fun `the list and a saved view are refused by the same gate`() {
		val fromList = assertFailsWith<BadRequestException> { list("labelColour" to "indigo") }
		val fromView = assertFailsWith<BadRequestException> {
			views.create(
				actor = admin,
				teamId = team.id,
				name = "Refused ${UUID.randomUUID()}",
				shared = true,
				filters = mapOf("labelColour" to listOf("indigo")),
				groupBy = ViewGroupBy.NONE,
				sortBy = ViewSortBy.UPDATED,
			)
		}

		assertEquals(fromList.message, fromView.message, "one gate means one sentence")
	}

	@Test
	fun `a status outside the vocabulary is refused rather than answered with nothing`() {
		assertFailsWith<IllegalArgumentException> { list("status" to "nearly_done") }
	}

	@Test
	fun `a number the list cannot read is refused rather than dropped`() {
		assertFailsWith<BadRequestException> { list("estimateMin" to "soon") }
	}

	// --- the old parameter shape ------------------------------------------------

	@Test
	fun `projectId still answers exactly what project answers`() {
		val one = project("One")
		val a = ticket("a", projectId = one)
		ticket("loose")

		assertEquals(
			list("project" to one.toString()),
			list("projectId" to one.toString()),
			"`projectId` is the name the web app and the MCP server already send",
		)
		assertEquals(listOf(a), list("projectId" to one.toString()))
	}

	@Test
	fun `assigneeId still answers exactly what assignee answers`() {
		val mine = user()
		val owned = ticket("owned", assignees = listOf(mine.id))
		ticket("nobody's")

		assertEquals(
			list("assignee" to mine.id.toString()),
			list("assigneeId" to mine.id.toString()),
		)
		assertEquals(listOf(owned), list("assigneeId" to mine.id.toString()))
	}

	@Test
	fun `the old status parameter is the new one, spelled the same`() {
		val doing = ticket("doing", TicketStatus.IN_PROGRESS)
		ticket("waiting")

		assertEquals(listOf(doing), list("status" to listOf("in_progress")))
	}

	@Test
	fun `an alias and its canonical name asked together read as either`() {
		val one = project("One")
		val two = project("Two")
		val a = ticket("a", projectId = one)
		val b = ticket("b", projectId = two)

		assertEquals(
			setOf(a, b),
			list("projectId" to one.toString(), "project" to two.toString()).toSet(),
		)
	}

	// --- scope, paging and the cap ----------------------------------------------

	@Test
	fun `the list never reaches outside the team asked for`() {
		val elsewhere = teams.create(admin, "Elsewhere", key(), null)
		ticket("theirs", teamId = elsewhere.id)
		val mine = ticket("ours")

		assertEquals(listOf(mine), list())
	}

	@Test
	fun `includeDescendants is unchanged by the merge`() {
		val child = teams.create(admin, "Child", key(), team.id)
		val here = ticket("here")
		val below = ticket("below", teamId = child.id)

		assertEquals(listOf(here), list())
		assertEquals(setOf(here, below), list(includeDescendants = true).toSet())
	}

	@Test
	fun `no teamId is every team, as it always was`() {
		val elsewhere = teams.create(admin, "Elsewhere", key(), null)
		val theirs = ticket("theirs", teamId = elsewhere.id)
		val mine = ticket("ours")

		val all = list(teamId = null)

		assertTrue(all.containsAll(listOf(mine, theirs)))
	}

	@Test
	fun `archived work stays out unless it is asked for`() {
		val live = ticket("live")
		val filed = ticket("filed")
		tickets.patch(admin, filed, TicketPatch(archived = true))

		assertEquals(listOf(live), list())
		assertEquals(setOf(live, filed), list(includeArchived = true).toSet())
	}

	@Test
	fun `a ticket in the trash is out of the list and out of a saved view alike`() {
		val kept = ticket("kept")
		val thrown = ticket("thrown away")
		tickets.delete(admin, thrown)

		val saved = views.create(
			actor = admin,
			teamId = team.id,
			name = "Everything ${UUID.randomUUID()}",
			shared = true,
			filters = emptyMap(),
			groupBy = ViewGroupBy.NONE,
			sortBy = ViewSortBy.UPDATED,
		)

		assertEquals(listOf(kept), list())
		assertEquals(
			listOf(kept),
			views.tickets(saved.id).map { it.ticket.id },
			"the trash is not a filter either side may forget: a deleted ticket is not work",
		)
		assertEquals(1, views.list(team.id).single { it.view.id == saved.id }.count)
	}

	@Test
	fun `limit and offset walk the list without repeating or skipping a row`() {
		val made = (1..5).map { ticket("row $it") }

		val whole = list(limit = 200)
		assertEquals(5, whole.size)
		assertEquals(made.toSet(), whole.toSet())

		val first = list(limit = 2, offset = 0)
		val second = list(limit = 2, offset = 2)
		val third = list(limit = 2, offset = 4)

		assertEquals(whole, first + second + third, "the page boundaries have to be stable")
	}

	@Test
	fun `the 500 cap survives the merge`() {
		ticket("only one")

		assertEquals(1, list(limit = 100_000).size, "an absurd limit is clamped, not refused")
		assertEquals(1, list(limit = 0).size, "and so is a nonsensical one")
		assertEquals(1, list(offset = -5).size)
	}

	@Test
	fun `sort is part of the one vocabulary too`() {
		val second = ticket("b", priority = TicketPriority.LOW)
		val first = ticket("a", priority = TicketPriority.URGENT)

		assertEquals(
			listOf(first, second),
			list(sort = "priority"),
			"urgent first, as the screen reads downwards",
		)
		assertEquals(listOf("a", "b"), list(sort = "title").let { ids -> ids.map { titleOf(it) } })
	}

	// --- helpers ----------------------------------------------------------------

	private fun project(name: String): UUID = projects.create(
		name = name,
		status = ProjectStatus.IN_PROGRESS,
		start = null,
		end = null,
		leadUserId = null,
		teamId = team.id,
		docIds = emptyList(),
	).project.id

	private fun titleOf(id: UUID): String = tickets.get(id).ticket.title

	/**
	 * Backdates `created_at`, which no service will do: age is the one filter whose input
	 * is the clock, and a test that slept for it would be a test nobody runs.
	 */
	private fun agedByDays(id: UUID, days: Long) {
		Tickets.update({ Tickets.id eq id }) {
			it[Tickets.createdAt] = OffsetDateTime.now().minusDays(days)
		}
	}
}

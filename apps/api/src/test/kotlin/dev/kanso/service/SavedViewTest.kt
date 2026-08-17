package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Screen 21: a stored question with removable chips, and a strip that edits six rows at
 * once.
 *
 * A view stores the predicate, never the answer. Every test here asks the same view
 * twice across a change and expects a different list, because a view that caches ids is
 * a view that lies the first time somebody edits a ticket outside it.
 */
@Transactional
class SavedViewTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var bulk: BulkEditService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "view-${UUID.randomUUID()}@kanso.test",
		displayName = "View ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "V${UUID.randomUUID().toString().take(4).uppercase()}"

	private val team by lazy { teams.create(admin, "Viewing", key(), null) }

	private fun ticket(
		title: String,
		status: TicketStatus = TicketStatus.TODO,
		priority: TicketPriority = TicketPriority.NONE,
		teamId: UUID = team.id,
		assignees: List<UUID> = emptyList(),
	) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = title,
		description = null,
		status = status,
		priority = priority,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
	).ticket.id

	private fun view(filters: Map<String, Any?>, name: String = "Sync debt-${UUID.randomUUID()}") =
		views.create(
			actor = admin,
			teamId = team.id,
			name = name,
			shared = true,
			filters = filters,
			groupBy = ViewGroupBy.STATUS,
			sortBy = ViewSortBy.PRIORITY,
		)

	@Test
	fun `a view stores the question and answers it fresh every time`() {
		val open = view(mapOf("statusNot" to listOf("done")))
		val moving = ticket("Echo suppression drops our own writes")

		assertEquals(1, views.tickets(open.id).size)
		tickets.patch(admin, moving, TicketPatch(status = TicketStatus.DONE))

		assertTrue(
			views.tickets(open.id).isEmpty(),
			"a saved view that cached its ids would still be showing a done ticket",
		)
	}

	@Test
	fun `the drawing's three chips each narrow the list on their own`() {
		val urgent = ticket("urgent one", TicketStatus.IN_PROGRESS, TicketPriority.URGENT)
		ticket("done one", TicketStatus.DONE, TicketPriority.URGENT)
		ticket("low one", TicketStatus.IN_PROGRESS, TicketPriority.LOW)

		val chips = view(
			mapOf("statusNot" to listOf("done"), "priority" to listOf("urgent")),
		)

		assertEquals(listOf(urgent), views.tickets(chips.id).map { it.ticket.id })
	}

	@Test
	fun `removing a chip is a write to the filters and widens the answer`() {
		val urgent = ticket("urgent one", TicketStatus.IN_PROGRESS, TicketPriority.URGENT)
		val low = ticket("low one", TicketStatus.IN_PROGRESS, TicketPriority.LOW)
		val narrow = view(mapOf("priority" to listOf("urgent")))

		views.update(admin, narrow.id, filters = emptyMap())

		assertEquals(
			setOf(urgent, low),
			views.tickets(narrow.id).map { it.ticket.id }.toSet(),
			"the × on a chip removes a key from `filters`; nothing else about the view changes",
		)
	}

	@Test
	fun `an unassigned filter is a real question, not an empty one`() {
		val mine = user(InstanceRole.MEMBER)
		ticket("has an owner", assignees = listOf(mine.id))
		val orphan = ticket("nobody on it")

		val unassigned = view(mapOf("unassigned" to true))

		assertEquals(listOf(orphan), views.tickets(unassigned.id).map { it.ticket.id })
	}

	@Test
	fun `a filter key nobody serves is refused when the view is written, not silently ignored`() {
		val error = assertFailsWith<BadRequestException> {
			view(mapOf("label" to listOf("sync")))
		}

		assertTrue(
			error.message!!.contains("label"),
			"a chip that displays and does not filter is worse than a chip that was refused",
		)
	}

	@Test
	fun `a view never reaches outside its own team`() {
		val elsewhere = teams.create(admin, "Elsewhere", key(), null)
		ticket("theirs", teamId = elsewhere.id)
		val mine = ticket("ours")

		val everything = view(emptyMap())

		assertEquals(
			listOf(mine),
			views.tickets(everything.id).map { it.ticket.id },
			"a team's saved view is scoped to the team it was saved in, filters or no filters",
		)
	}

	@Test
	fun `the sidebar count is the number of rows the view would show`() {
		ticket("one", TicketStatus.IN_PROGRESS)
		ticket("two", TicketStatus.IN_PROGRESS)
		ticket("three", TicketStatus.DONE)
		val open = view(mapOf("statusNot" to listOf("done")))

		assertEquals(2, views.list(team.id).single { it.view.id == open.id }.count)
	}

	@Test
	fun `two views in one team cannot share a name`() {
		view(emptyMap(), name = "Blocked for 3 days")

		assertFailsWith<ConflictException> { view(emptyMap(), name = "Blocked for 3 days") }
	}

	@Test
	fun `saving a view needs write access to the team it is saved in`() {
		val owned = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teams.addMember(admin, owned.id, member.id, MemberRole.MEMBER)
		val outsider = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> {
			views.create(
				actor = outsider,
				teamId = owned.id,
				name = "Not mine",
				shared = true,
				filters = emptyMap(),
				groupBy = ViewGroupBy.STATUS,
				sortBy = ViewSortBy.PRIORITY,
			)
		}
	}

	// --- the bulk strip ------------------------------------------------------

	@Test
	fun `one strip action moves every selected row and leaves the rest alone`() {
		val selected = listOf(ticket("a"), ticket("b"), ticket("c"))
		val untouched = ticket("d")

		val changed = bulk.apply(admin, BulkEdit(ticketIds = selected, status = TicketStatus.IN_REVIEW))

		assertEquals(3, changed)
		assertTrue(selected.all { tickets.get(it).ticket.status == TicketStatus.IN_REVIEW })
		assertEquals(TicketStatus.TODO, tickets.get(untouched).ticket.status)
	}

	@Test
	fun `a selection containing one forbidden row changes nothing at all`() {
		val outsider = user(InstanceRole.MEMBER)
		val ours = teams.create(admin, "Ours", key(), null)
		teams.addMember(admin, ours.id, outsider.id, MemberRole.MEMBER)
		val theirs = teams.create(admin, "Theirs", key(), null)
		teams.addMember(admin, theirs.id, user(InstanceRole.MEMBER).id, MemberRole.MEMBER)
		val mine = ticket("mine", teamId = ours.id)
		val notMine = ticket("not mine", teamId = theirs.id)

		assertFailsWith<AccessDeniedException> {
			bulk.apply(outsider, BulkEdit(ticketIds = listOf(mine, notMine), status = TicketStatus.DONE))
		}

		// Observable only because the refusal happens before the first write. Asserted on
		// the writable row, not the forbidden one: this is the row that proves the strip
		// did not half-apply, and a rollback-based implementation could not be tested for
		// it at all in a suite that never commits.
		assertEquals(
			TicketStatus.TODO,
			tickets.get(mine).ticket.status,
			"a strip that half-applies leaves nobody able to say what the selection now is",
		)
	}

	@Test
	fun `the strip's cycle control is the same move the slip list makes`() {
		val cycle = cycles.create(
			actor = admin,
			teamId = team.id,
			number = 25,
			startsOn = java.time.LocalDate.now(),
			endsOn = java.time.LocalDate.now().plusDays(13),
			state = CycleState.ACTIVE,
		)
		val selected = listOf(ticket("a"), ticket("b"))

		bulk.apply(admin, BulkEdit(ticketIds = selected, cycleId = cycle.id))

		assertEquals(selected.toSet(), cycles.report(cycle.id).tickets.map { it.ticket.id }.toSet())
	}

	@Test
	fun `an empty selection is refused rather than reported as a successful no-op`() {
		assertFailsWith<BadRequestException> {
			bulk.apply(admin, BulkEdit(ticketIds = emptyList(), status = TicketStatus.DONE))
		}
	}

	@Test
	fun `a strip action that names no change is refused`() {
		val selected = listOf(ticket("a"))

		assertFailsWith<BadRequestException> { bulk.apply(admin, BulkEdit(ticketIds = selected)) }
	}

	@Test
	fun `the strip can delete the selection`() {
		val selected = listOf(ticket("a"), ticket("b"))

		assertEquals(2, bulk.delete(admin, selected))
		assertFailsWith<NotFoundException> { tickets.get(selected.first()) }
	}
}

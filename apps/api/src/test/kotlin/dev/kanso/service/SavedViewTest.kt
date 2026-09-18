package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
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
	@Autowired lateinit var labels: LabelService
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
		status: String = "todo",
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

	/**
	 * A private view is its author's, which is what `V10` says `shared` means and what
	 * nothing enforced until now: *"False means it is the author's own, which is why
	 * `created_by` is not nullable-by-accident."* The column was stored, returned and drawn,
	 * and appeared in no predicate anywhere — so a colleague on the same team could list it,
	 * read its rows, publish it by patching `shared` to true, rewrite the question it asked,
	 * and bin it. Team write access was the only check, and on a fresh instance every member
	 * has that.
	 *
	 * Asserted through a second member of the *same team*, because a stranger was never the
	 * threat: the point of the flag is a boundary inside a team someone already belongs to.
	 *
	 * `NotFoundException` rather than a refusal, deliberately — the same answer
	 * `TicketAccess.requireReadable` gives a private draft, because a 403 tells somebody
	 * walking ids that the row is there, and that it exists is the fact being kept.
	 */
	@Test
	fun `an unshared view is invisible to a colleague on the same team`() {
		val mine = views.create(
			actor = admin,
			teamId = team.id,
			name = "Mine-${UUID.randomUUID()}",
			shared = false,
			filters = emptyMap(),
			groupBy = ViewGroupBy.STATUS,
			sortBy = ViewSortBy.PRIORITY,
		)
		val colleague = user(InstanceRole.MEMBER)

		assertTrue(
			views.list(colleague, team.id).none { it.view.id == mine.id },
			"the rail listed every live row of the team, shared or not",
		)
		assertFailsWith<NotFoundException> { views.get(colleague, mine.id) }
		assertFailsWith<NotFoundException> { views.grouped(colleague, mine.id) }
		assertFailsWith<NotFoundException> { views.count(colleague, mine.id) }
		assertFailsWith<NotFoundException> {
			views.update(actor = colleague, id = mine.id, shared = true)
		}
		assertFailsWith<NotFoundException> { views.delete(colleague, mine.id) }

		assertEquals(mine.id, views.get(admin, mine.id).id, "its author still has it")
	}

	/**
	 * The other half, and the one a too-eager fix breaks: sharing a view is what makes it
	 * the team's, so a colleague reads it, edits it and can delete it. The rule is about the
	 * views that were *not* shared.
	 */
	@Test
	fun `a shared view stays the team's to read and to change`() {
		val ours = view(emptyMap())
		val colleague = user(InstanceRole.MEMBER)

		assertEquals(ours.id, views.get(colleague, ours.id).id)
		assertEquals("Renamed", views.update(colleague, ours.id, name = "Renamed").name)
	}

	@Test
	fun `a view stores the question and answers it fresh every time`() {
		val open = view(mapOf("statusNot" to listOf("done")))
		val moving = ticket("Echo suppression drops our own writes")

		assertEquals(1, views.rows(admin, open.id).size)
		tickets.patch(admin, moving, TicketPatch(status = "done"))

		assertTrue(
			views.rows(admin, open.id).isEmpty(),
			"a saved view that cached its ids would still be showing a done ticket",
		)
	}

	@Test
	fun `the drawing's three chips each narrow the list on their own`() {
		val urgent = ticket("urgent one", "in_progress", TicketPriority.URGENT)
		ticket("done one", "done", TicketPriority.URGENT)
		ticket("low one", "in_progress", TicketPriority.LOW)

		val chips = view(
			mapOf("statusNot" to listOf("done"), "priority" to listOf("urgent")),
		)

		assertEquals(listOf(urgent), views.rows(admin, chips.id).map { it.ticket.id })
	}

	@Test
	fun `removing a chip is a write to the filters and widens the answer`() {
		val urgent = ticket("urgent one", "in_progress", TicketPriority.URGENT)
		val low = ticket("low one", "in_progress", TicketPriority.LOW)
		val narrow = view(mapOf("priority" to listOf("urgent")))

		views.update(admin, narrow.id, filters = emptyMap())

		assertEquals(
			setOf(urgent, low),
			views.rows(admin, narrow.id).map { it.ticket.id }.toSet(),
			"the × on a chip removes a key from `filters`; nothing else about the view changes",
		)
	}

	@Test
	fun `an unassigned filter is a real question, not an empty one`() {
		val mine = user(InstanceRole.MEMBER)
		ticket("has an owner", assignees = listOf(mine.id))
		val orphan = ticket("nobody on it")

		val unassigned = view(mapOf("unassigned" to true))

		assertEquals(listOf(orphan), views.rows(admin, unassigned.id).map { it.ticket.id })
	}

	/**
	 * This test used to assert the opposite: `label` was refused, because `V8` had not
	 * landed and a chip that stored and drew but never filtered is worse than one that was
	 * refused. `V8` landed, so the refusal became the lie and the chip is now honoured.
	 *
	 * The stored value is the label's id, like `project`, `assignee` and `cycle` beside it,
	 * and not its name: labels are team-scoped, a view reaches into descendant teams, and
	 * two of those teams may both own the name `sync`. A name would quietly answer with
	 * somebody else's work, and would stop answering at all the day the label is renamed.
	 */
	@Test
	fun `the drawing's third chip narrows to the tickets wearing that label`() {
		val sync = labels.create(admin, team.id, "sync", "indigo")
		val wearing = ticket("Echo suppression drops our own writes")
		ticket("Nothing to do with synchronisation")
		labels.attach(admin, wearing, sync.id)

		val chip = view(mapOf("label" to listOf(sync.id.toString())))

		assertEquals(listOf(wearing), views.rows(admin, chip.id).map { it.ticket.id })

		// And the `×` widens it back, like every other chip's does.
		views.update(admin, chip.id, filters = emptyMap())
		assertEquals(2, views.rows(admin, chip.id).size)
	}

	@Test
	fun `a filter key nobody serves is refused when the view is written, not silently ignored`() {
		val error = assertFailsWith<BadRequestException> {
			view(mapOf("labelColour" to listOf("indigo")))
		}

		assertTrue(
			error.message!!.contains("labelColour"),
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
			views.rows(admin, everything.id).map { it.ticket.id },
			"a team's saved view is scoped to the team it was saved in, filters or no filters",
		)
	}

	@Test
	fun `the sidebar count is the number of rows the view would show`() {
		ticket("one", "in_progress")
		ticket("two", "in_progress")
		ticket("three", "done")
		val open = view(mapOf("statusNot" to listOf("done")))

		assertEquals(2, views.list(admin, team.id).single { it.view.id == open.id }.count)
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

		val changed = bulk.apply(admin, BulkEdit(ticketIds = selected, status = "in_review"))

		assertEquals(3, changed)
		assertTrue(selected.all { tickets.get(it).ticket.status == "in_review" })
		assertEquals("todo", tickets.get(untouched).ticket.status)
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
			bulk.apply(outsider, BulkEdit(ticketIds = listOf(mine, notMine), status = "done"))
		}

		// Observable only because the refusal happens before the first write. Asserted on
		// the writable row, not the forbidden one: this is the row that proves the strip
		// did not half-apply, and a rollback-based implementation could not be tested for
		// it at all in a suite that never commits.
		assertEquals(
			"todo",
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

	/**
	 * The drawing's sixth button. It *adds* a label rather than replacing the set, unlike
	 * `PUT /api/tickets/{id}/labels`: the strip acts on rows a reader cannot see the labels
	 * of, and a replace would silently strip whatever each of them already wore.
	 */
	@Test
	fun `the strip's label button puts one label on the whole selection`() {
		val sync = labels.create(admin, team.id, "sync", "indigo")
		val selected = listOf(ticket("a"), ticket("b"))
		val untouched = ticket("c")

		assertEquals(2, bulk.apply(admin, BulkEdit(ticketIds = selected, labelId = sync.id)))

		assertTrue(selected.all { labels.forTicket(it).map { label -> label.id } == listOf(sync.id) })
		assertEquals(emptyList(), labels.forTicket(untouched))
	}

	@Test
	fun `a label another team owns is refused before the first row is written`() {
		val elsewhere = teams.create(admin, "Elsewhere labels", key(), null)
		val foreign = labels.create(admin, elsewhere.id, "sync", "amber")
		val selected = listOf(ticket("a"), ticket("b"))

		assertFailsWith<ConflictException> {
			bulk.apply(admin, BulkEdit(ticketIds = selected, labelId = foreign.id))
		}

		assertTrue(
			selected.all { labels.forTicket(it).isEmpty() },
			"the whole selection is checked first, so no row wears a label the rest were refused",
		)
	}

	@Test
	fun `an empty selection is refused rather than reported as a successful no-op`() {
		assertFailsWith<BadRequestException> {
			bulk.apply(admin, BulkEdit(ticketIds = emptyList(), status = "done"))
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

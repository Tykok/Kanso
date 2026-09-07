package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Teams
import dev.kanso.db.Tickets
import dev.kanso.domain.EffortPoints
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.sync.notion.NotionProps
import dev.kanso.sync.notion.NotionSchema
import dev.kanso.sync.outbound.NotionMapper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Effort in points: the column, the scale, and the four readers that sum it.
 *
 * Two facts are asserted over and over here because everything computed later stands on
 * them. **Null is not zero**: a ticket nobody has estimated is missing from every sum
 * rather than dragging it down, and every screen that prints a sum also prints how many
 * tickets it could not speak for. **The scale is closed**: 1, 2, 3, 5, 8, 13 and nothing
 * between, refused by Kotlin and refused again by `tickets_estimate_chk`, so a writer
 * that never came through a service is refused too.
 */
@Transactional
class EstimateTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var workload: WorkloadService
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var repository: TicketRepository
	@Autowired lateinit var mapper: NotionMapper
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String, role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "points-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { person("Points admin", InstanceRole.ADMIN) }

	private val team by lazy {
		teams.create(admin, "Estimating", "P${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(
		title: String,
		estimate: Int? = null,
		status: DefaultStatus = DefaultStatus.TODO,
		assignees: List<UUID> = emptyList(),
	) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	).ticket.id

	/**
	 * Backdates a closure into the cycle's own window. `completed_at` is `now()` on the
	 * write, and the reports here are asked at a fixed past [today], so without this every
	 * closure lands after the last bar and the burn-down never descends. Written with the
	 * DSL, like `WorkloadTest`'s own `age`: restamping a completion is a thing only a test
	 * wants, and a production method for it would be an invitation.
	 */
	private fun closedOn(ticketId: UUID, day: LocalDate) {
		Tickets.update({ Tickets.id eq ticketId }) {
			it[completedAt] = day.atStartOfDay().atOffset(ZoneOffset.UTC)
		}
	}

	/** The drawing's cycle: fourteen days, eight of them gone. Same dates as screen 19's. */
	private val today: LocalDate = LocalDate.of(2026, 8, 12)

	private fun cycle(number: Int = 24) = cycles.create(
		actor = admin,
		teamId = team.id,
		number = number,
		startsOn = LocalDate.of(2026, 8, 4),
		endsOn = LocalDate.of(2026, 8, 18),
		state = CycleState.ACTIVE,
	)

	// --- the column and its scale --------------------------------------------

	@Test
	fun `an unestimated ticket is null, not zero, and stays that way through a round trip`() {
		val unestimated = ticket("nobody has sized this")

		assertNull(
			tickets.get(unestimated).ticket.estimate,
			"'not estimated yet' and 'estimated at zero' are different states; a default of 0" +
				" would poison every average computed out of this column",
		)
	}

	@Test
	fun `an estimate is written, read back and cleared by naming it in unset`() {
		val sized = ticket("split the poller", estimate = 5)

		assertEquals(5, tickets.get(sized).ticket.estimate)

		tickets.patch(admin, sized, TicketPatch(estimate = 8))
		assertEquals(8, tickets.get(sized).ticket.estimate)

		// An absent field means "leave unchanged", so a re-estimate that says nothing about
		// the points must not silently drop them.
		tickets.patch(admin, sized, TicketPatch(title = "split the poller, again"))
		assertEquals(8, tickets.get(sized).ticket.estimate)

		tickets.patch(admin, sized, TicketPatch(unset = setOf("estimate")))
		assertNull(
			tickets.get(sized).ticket.estimate,
			"un-estimating is a real edit — the only way back to 'not sized yet' is to name it",
		)
	}

	@Test
	fun `a value off the scale is refused by Kotlin, with the scale in the sentence`() {
		val error = assertFailsWith<IllegalArgumentException> { EffortPoints.from(7) }

		assertTrue(
			error.message!!.contains("1, 2, 3, 5, 8, 13"),
			"the refusal has to name the vocabulary, or the writer has nowhere to look: ${error.message}",
		)
		assertFailsWith<IllegalArgumentException> { ticket("a seven", estimate = 7) }
		assertFailsWith<IllegalArgumentException> {
			tickets.patch(admin, ticket("sized"), TicketPatch(estimate = 4))
		}
	}

	/**
	 * Last in its test on purpose: a constraint violation aborts the transaction, so
	 * nothing can be read after it. That is also why this is its own test rather than a
	 * second assertion in the one above.
	 */
	@Test
	fun `a value off the scale is refused by the database as well, not only by Kotlin`() {
		val id = ticket("sized elsewhere", estimate = 5)

		assertFailsWith<ExposedSQLException> {
			// Straight at the column, the way an importer, a migration or a psql session
			// would reach it. A closed vocabulary the database does not refuse is a
			// vocabulary that drifts — the argument `tickets_status_chk` already makes.
			Tickets.update({ Tickets.id eq id }) { it[estimate] = 7.toShort() }
		}
	}

	// --- the cycle -----------------------------------------------------------

	@Test
	fun `the cycle's progress is a sum of points, and what has no estimate is counted apart`() {
		val cycle = cycle()
		cycles.addTickets(
			admin,
			cycle.id,
			listOf(
				ticket("shipped", estimate = 5, status = DefaultStatus.DONE),
				ticket("also shipped", estimate = 3, status = DefaultStatus.DONE),
				ticket("in flight", estimate = 8),
				ticket("nobody sized this"),
			),
		)

		val report = cycles.report(cycle.id, today)

		assertEquals(16, report.points.total, "5 + 3 + 8; the unestimated one adds nothing at all")
		assertEquals(8, report.points.done)
		assertEquals(50, report.points.percent)
		assertEquals(
			1,
			report.points.unestimated,
			"a burn-down that silently ignores a quarter of the work is worse than one that" +
				" counts rows; the number it cannot speak for travels with the sum",
		)
		// The rows are still counted beside the points, and the two do not agree — 2 of 4
		// tickets is half the list, 8 of 16 points is half the work, and only by accident
		// here. Both are printed because a team that has not estimated everything needs the
		// count, and one that has needs the points.
		assertEquals(4, report.total)
		assertEquals(2, report.done)
	}

	@Test
	fun `the burn-down carries both units so a day's bar can be drawn in either`() {
		val cycle = cycle()
		val shipped = ticket("shipped", estimate = 5, status = DefaultStatus.DONE)
		cycles.addTickets(
			admin,
			cycle.id,
			listOf(shipped, ticket("in flight", estimate = 8), ticket("nobody sized this")),
		)
		closedOn(shipped, LocalDate.of(2026, 8, 6))

		val report = cycles.report(cycle.id, today)
		val measured = report.remaining.filter { !it.projected }

		assertEquals(13, measured.first().openPoints, "day one carries everything that was committed")
		assertEquals(
			listOf(13, 13, 8),
			measured.take(3).map { it.openPoints },
			"the descent is read off `completed_at`, day by day, in points",
		)
		assertEquals(listOf(3, 3, 2), measured.take(3).map { it.open }, "the same descent, in rows")
		assertEquals(today, measured.last().day)
		assertEquals(8, measured.last().openPoints, "13 committed, 5 closed — the unsized one weighs nothing")
		assertTrue(
			report.remaining.filter { it.projected }.all { it.openPoints <= 8 },
			"the projection descends from where today left off; it never climbs",
		)
	}

	@Test
	fun `a cycle nobody has estimated reports no points rather than a zero it invented`() {
		val cycle = cycle()
		cycles.addTickets(admin, cycle.id, listOf(ticket("one"), ticket("two"), ticket("three")))

		val report = cycles.report(cycle.id, today)

		assertEquals(0, report.points.total)
		assertEquals(0, report.points.percent, "0 of 0 points is not a division, and not a failed cycle")
		assertEquals(
			3,
			report.points.unestimated,
			"the whole cycle is what the points cannot speak for, and the screen has to be able to say so",
		)
		assertEquals(3, report.total, "the row count still answers, which is why it was kept")
	}

	// --- the workload --------------------------------------------------------

	@Test
	fun `a person's load is the sum of their points, with the unsized counted beside it`() {
		val rey = person("M. Rey")
		ticket("a", estimate = 5, status = DefaultStatus.IN_PROGRESS, assignees = listOf(rey.id))
		ticket("b", estimate = 8, assignees = listOf(rey.id))
		ticket("c", assignees = listOf(rey.id))

		val row = workload.forTeam(team.id).rows.single { it.person?.id == rey.id }

		assertEquals(13, row.points)
		assertEquals(1, row.unestimated, "an unsized ticket is still on their plate; it is just not in the sum")
		assertEquals(3, row.total, "and the count stays, because a team that estimates nothing still has a load")
	}

	@Test
	fun `a done ticket's points leave the load, like its row does`() {
		val rey = person("M. Rey")
		ticket("open", estimate = 3, assignees = listOf(rey.id))
		ticket("finished", estimate = 13, status = DefaultStatus.DONE, assignees = listOf(rey.id))

		assertEquals(3, workload.forTeam(team.id).rows.single { it.person?.id == rey.id }.points)
	}

	// --- the saved views -----------------------------------------------------

	private fun view(filters: Map<String, Any?>) = views.create(
		actor = admin,
		teamId = team.id,
		name = "Estimate chip ${UUID.randomUUID()}",
		shared = true,
		filters = filters,
		groupBy = ViewGroupBy.NONE,
		sortBy = ViewSortBy.CREATED,
	)

	@Test
	fun `the unestimated chip finds exactly the tickets nobody has sized`() {
		val unsized = ticket("nobody sized this")
		ticket("sized", estimate = 3)

		assertEquals(listOf(unsized), views.rows(view(mapOf("unestimated" to true)).id).map { it.ticket.id })
	}

	@Test
	fun `a bound narrows to the tickets on that part of the scale, and never to an unsized one`() {
		val small = ticket("small", estimate = 1)
		val middling = ticket("middling", estimate = 5)
		val large = ticket("large", estimate = 13)
		ticket("unsized")

		assertEquals(
			setOf(middling, large),
			views.rows(view(mapOf("estimateMin" to 5)).id).map { it.ticket.id }.toSet(),
		)
		assertEquals(
			setOf(small, middling),
			views.rows(view(mapOf("estimateMax" to 5)).id).map { it.ticket.id }.toSet(),
			"an unsized ticket is not known to be small; a bound cannot claim it either way",
		)
		assertEquals(
			listOf(middling),
			views.rows(view(mapOf("estimateMin" to 2, "estimateMax" to 8)).id).map { it.ticket.id },
		)
	}

	@Test
	fun `a filter key nobody serves is still refused, estimate or not`() {
		assertFailsWith<BadRequestException> { view(mapOf("estimatedBy" to "Rey")) }
	}

	// --- the mirror ----------------------------------------------------------

	@Test
	fun `the mirror carries the points, and an unestimated ticket clears the property`() {
		// The mapper refuses to write a ticket whose team has no page yet, so the team gets
		// one. Reached with the DSL rather than through the sync engine: a push needs a
		// Notion this suite has no business talking to.
		Teams.update({ Teams.id eq team.id }) { it[notionPageId] = "team-page" }
		val sized = repository.findById(ticket("sized", estimate = 8))!!
		val unsized = repository.findById(ticket("unsized"))!!

		assertEquals(mapOf("number" to 8), mapper.ticketProperties(sized)[NotionProps.ESTIMATE])
		assertEquals(
			mapOf("number" to null),
			mapper.ticketProperties(unsized)[NotionProps.ESTIMATE],
			"an explicit null clears the property; leaving it out would let the mirror keep a" +
				" number the ticket no longer carries",
		)
		assertTrue(
			NotionProps.ESTIMATE in NotionSchema.tickets(),
			"the bootstrap has to create the property, or every push writes into nothing",
		)
	}
}

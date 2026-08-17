package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Screen 19: a cycle's progress, its burn-down, and which tickets will not fit.
 *
 * Every number the screen shows is derived here on read. The tests assert that and
 * nothing about storage, because storing a rate is the mistake this design refuses.
 */
@Transactional
class CycleReportTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "cycle-${UUID.randomUUID()}@kanso.test",
		displayName = "Cycle ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "C${UUID.randomUUID().toString().take(4).uppercase()}"

	private val team by lazy { teams.create(admin, "Cycling", key(), null) }

	/** Fourteen days, eight of them already gone — the drawing's "6 j" remaining. */
	private val today: LocalDate = LocalDate.of(2026, 8, 12)

	private fun cycle(number: Int, state: CycleState = CycleState.ACTIVE) = cycles.create(
		actor = admin,
		teamId = team.id,
		number = number,
		startsOn = LocalDate.of(2026, 8, 4),
		endsOn = LocalDate.of(2026, 8, 18),
		state = state,
	)

	private fun ticket(title: String, status: TicketStatus, priority: TicketPriority = TicketPriority.NONE) =
		tickets.create(
			actor = admin,
			teamId = team.id,
			title = title,
			description = null,
			status = status,
			priority = priority,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

	@Test
	fun `progress is the same fact twice — a count and the percentage of it`() {
		val cycle = cycle(24)
		repeat(3) { cycles.addTickets(admin, cycle.id, listOf(ticket("done $it", TicketStatus.DONE))) }
		repeat(5) { cycles.addTickets(admin, cycle.id, listOf(ticket("open $it", TicketStatus.TODO))) }

		val report = cycles.report(cycle.id, today)

		assertEquals(8, report.total)
		assertEquals(3, report.done)
		// 3/8 is 37.5, and the header prints one number for both halves of the sentence.
		// The drawing's own "58 % · 14 tickets sur 24" disagrees with its own breakdown;
		// two numbers that can contradict each other is the bug this asserts against.
		assertEquals(38, report.percent)
	}

	@Test
	fun `the status breakdown accounts for every ticket in the cycle and no other`() {
		val cycle = cycle(24)
		cycles.addTickets(admin, cycle.id, listOf(ticket("a", TicketStatus.IN_PROGRESS)))
		cycles.addTickets(admin, cycle.id, listOf(ticket("b", TicketStatus.IN_REVIEW)))
		cycles.addTickets(admin, cycle.id, listOf(ticket("c", TicketStatus.TODO)))
		ticket("outside the cycle", TicketStatus.TODO)

		val report = cycles.report(cycle.id, today)

		assertEquals(3, report.byStatus.values.sum(), "a breakdown that does not sum to the total explains nothing")
		assertEquals(1, report.byStatus[TicketStatus.IN_PROGRESS])
		assertEquals(0, report.byStatus[TicketStatus.BACKLOG], "every status is named, including the empty ones")
	}

	@Test
	fun `the projection is hatched exactly over the days that have not happened`() {
		val cycle = cycle(24)
		repeat(4) { cycles.addTickets(admin, cycle.id, listOf(ticket("open $it", TicketStatus.TODO))) }

		val report = cycles.report(cycle.id, today)

		assertEquals(15, report.remaining.size, "one bar per day, 4 August to 18 August inclusive")
		assertEquals(
			listOf(false, true),
			report.remaining.map { it.projected }.distinct(),
			"measured days first, then the projection — never interleaved",
		)
		assertEquals(
			today,
			report.remaining.last { !it.projected }.day,
			"today is the last day anybody has measured",
		)
	}

	@Test
	fun `nothing slips when the rate clears the remaining work in the days left`() {
		val cycle = cycle(24)
		// Nine days measured, six closed: two thirds of a ticket a day, and two left.
		repeat(6) { cycles.addTickets(admin, cycle.id, listOf(ticket("done $it", TicketStatus.DONE))) }
		repeat(2) { cycles.addTickets(admin, cycle.id, listOf(ticket("open $it", TicketStatus.TODO))) }

		val report = cycles.report(cycle.id, today)

		assertTrue(report.slipping.isEmpty(), "at two thirds a day, six days clears two tickets over twice")
	}

	@Test
	fun `what slips is the tail of the order the team will actually work in`() {
		val cycle = cycle(24)
		repeat(2) { cycles.addTickets(admin, cycle.id, listOf(ticket("closed $it", TicketStatus.DONE))) }
		val urgent = ticket("urgent", TicketStatus.TODO, TicketPriority.URGENT)
		val low = ticket("low", TicketStatus.TODO, TicketPriority.LOW)
		val none = ticket("unranked", TicketStatus.TODO, TicketPriority.NONE)
		cycles.addTickets(admin, cycle.id, listOf(urgent, low, none))

		val report = cycles.report(cycle.id, today)

		// Two closed over nine measured days is 2/9 a day; six days buys one more ticket
		// and not two, so two of the three open ones do not fit. The urgent one is not
		// among them: a projection that lets the top of the list slip describes a
		// different team than the one whose list this is.
		assertEquals(2, report.slipping.size)
		assertEquals(
			listOf(low, none),
			report.slipping.map { it.ticket.id },
			"lowest priority slips first, and the order is the order the list is worked",
		)
	}

	@Test
	fun `a cycle that has not started claims nothing about what will slip`() {
		val cycle = cycle(25, CycleState.UPCOMING)
		repeat(3) { cycles.addTickets(admin, cycle.id, listOf(ticket("planned $it", TicketStatus.TODO))) }

		val report = cycles.report(cycle.id, LocalDate.of(2026, 8, 1))

		assertTrue(
			report.slipping.isEmpty(),
			"no days have been measured, so there is no rate — and a rate of zero would" +
				" declare the whole cycle doomed before it began",
		)
	}

	@Test
	fun `a closed cycle projects nothing because there are no days left to project into`() {
		val cycle = cycle(23, CycleState.CLOSED)
		repeat(2) { cycles.addTickets(admin, cycle.id, listOf(ticket("open $it", TicketStatus.TODO))) }

		val report = cycles.report(cycle.id, LocalDate.of(2026, 8, 25))

		assertEquals(0, report.daysLeft)
		assertTrue(report.remaining.none { it.projected }, "a cycle that is over has no future to hatch")
		assertEquals(2, report.slipping.size, "work still open when the cycle ended did not fit, definitionally")
	}

	@Test
	fun `moving a ticket to the next cycle takes it out of the one it was in`() {
		val current = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		val slipping = ticket("will not fit", TicketStatus.TODO)
		cycles.addTickets(admin, current.id, listOf(slipping))

		cycles.addTickets(admin, next.id, listOf(slipping))

		assertEquals(0, cycles.report(current.id, today).total, "a ticket in two cycles has no honest burn-down")
		assertEquals(1, cycles.report(next.id, today).total)
	}

	@Test
	fun `a second active cycle is refused rather than left for the current route to guess`() {
		cycle(24)

		val error = assertFailsWith<ConflictException> { cycle(25) }

		assertTrue(
			error.message!!.contains("active"),
			"'/cycles/current' has to resolve to one row; the refusal has to say which rule it broke",
		)
	}

	@Test
	fun `only a team you may write to gets a cycle`() {
		val outsider = user(InstanceRole.MEMBER)
		val owned = teams.create(admin, "Theirs", key(), null)
		// A team with a member is a claimed team, which is what closes the open-chain door.
		val member = user(InstanceRole.MEMBER)
		teams.addMember(admin, owned.id, member.id, dev.kanso.domain.MemberRole.MEMBER)

		assertFailsWith<AccessDeniedException> {
			cycles.create(
				actor = outsider,
				teamId = owned.id,
				number = 1,
				startsOn = LocalDate.of(2026, 8, 4),
				endsOn = LocalDate.of(2026, 8, 18),
				state = CycleState.UPCOMING,
			)
		}
	}
}

package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a person actually delivered, in points per working day.
 *
 * Every number here is derived on read from `tickets.completed_at`, `ticket_cycles` and
 * the cycle's own two dates — the rule `V10__cycles_and_views.sql` states outright. The
 * tests assert the arithmetic and the four refusals that make it honest: points are split
 * between assignees, unsized work is left out rather than counted as zero, work closed
 * outside a cycle is nobody's, and a person the closed cycles cannot measure has no
 * velocity rather than a velocity of zero.
 *
 * The dates are all real weekdays of August 2026, chosen so that every cycle below is a
 * whole number of working weeks — the arithmetic in each test should be readable without
 * a calendar. 3 August is a Monday.
 */
@Transactional
class VelocityTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var velocity: VelocityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "velocity-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "velocity-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Velocity admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Delivering", "V${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** Five working days, Monday to Friday, unless the caller says otherwise. */
	private fun cycle(
		number: Int,
		startsOn: LocalDate,
		endsOn: LocalDate = startsOn.plusDays(4),
		state: CycleState = CycleState.CLOSED,
	) = cycles.create(admin, team.id, number, startsOn, endsOn, state)

	/**
	 * A ticket that reached done on [on], placed in [cycleId] if there is one.
	 *
	 * The completion date is written here rather than left to the insert, which stamps
	 * `now()`: a test whose cycles are in August 2026 and whose tickets closed today would
	 * measure nothing, and would start measuring something the day the clock caught up.
	 */
	private fun delivered(
		cycleId: UUID?,
		on: LocalDate,
		estimate: Int?,
		assignees: List<UUID>,
	): UUID {
		val id = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "delivered $estimate",
			description = null,
			status = DefaultStatus.DONE,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = assignees,
			docIds = emptyList(),
			estimate = estimate,
		).ticket.id
		Tickets.update({ Tickets.id eq id }) {
			it[completedAt] = on.atTime(10, 0).atOffset(ZoneOffset.UTC)
		}
		cycleId?.let { cycles.addTickets(admin, it, listOf(id)) }
		return id
	}

	@Test
	fun `an eight-point ticket carried by two people is four points each, not eight each`() {
		val ana = person("Ana")
		val bo = person("Bo")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(c.id, LocalDate.of(2026, 8, 5), estimate = 8, assignees = listOf(ana.id, bo.id))

		val hers = assertNotNull(velocity.forPerson(ana, team.id).perWorkingDay)
		val his = assertNotNull(velocity.forPerson(bo, team.id).perWorkingDay)

		// 4 points over the cycle's five working days, each.
		assertEquals(0.8, hers, 1e-9)
		assertEquals(0.8, his, 1e-9)
		assertEquals(
			8.0 / 5,
			hers + his,
			1e-9,
			"a pair's two velocities have to add up to the ticket's own rate; whole points on" +
				" both plates would report the work twice",
		)
	}

	@Test
	fun `a ticket nobody sized changes neither half of the fraction, and is counted instead`() {
		val ana = person("Ana")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(c.id, LocalDate.of(2026, 8, 5), estimate = 8, assignees = listOf(ana.id))

		val sizedOnly = assertNotNull(velocity.forPerson(ana, team.id).perWorkingDay)
		delivered(c.id, LocalDate.of(2026, 8, 6), estimate = null, assignees = listOf(ana.id))
		val withUnsized = velocity.forPerson(ana, team.id)

		assertEquals(
			sizedOnly,
			assertNotNull(withUnsized.perWorkingDay),
			1e-9,
			"an unsized ticket is not a zero-point ticket; adding one must not dilute a velocity" +
				" and so punish whoever forgot to size it",
		)
		assertEquals(1, withUnsized.unestimated, "what the number cannot speak for travels with it")
	}

	@Test
	fun `work closed outside every cycle belongs to no velocity`() {
		val ana = person("Ana")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(c.id, LocalDate.of(2026, 8, 5), estimate = 8, assignees = listOf(ana.id))
		delivered(null, LocalDate.of(2026, 8, 6), estimate = 13, assignees = listOf(ana.id))

		assertEquals(
			8.0 / 5,
			assertNotNull(velocity.forPerson(ana, team.id).perWorkingDay),
			1e-9,
			"a rate needs a span to be a rate; work committed to no cycle has no denominator",
		)
	}

	@Test
	fun `the running cycle is not measured, however much has closed in it`() {
		val ana = person("Ana")
		val closed = cycle(21, LocalDate.of(2026, 8, 3))
		val running = cycle(22, LocalDate.of(2026, 8, 10), state = CycleState.ACTIVE)
		delivered(closed.id, LocalDate.of(2026, 8, 5), estimate = 5, assignees = listOf(ana.id))
		delivered(running.id, LocalDate.of(2026, 8, 11), estimate = 13, assignees = listOf(ana.id))

		val hers = velocity.forPerson(ana, team.id)

		assertEquals(1, hers.cycles.size, "one closed cycle, one measurement")
		assertEquals(
			1.0,
			assertNotNull(hers.perWorkingDay),
			1e-9,
			"a partial cycle is a mean that falls every morning; a velocity that moves during" +
				" the day is not a velocity",
		)
	}

	@Test
	fun `the velocity is the mean of the last three closed cycles, and the fourth is history`() {
		val ana = person("Ana")
		val older = cycle(20, LocalDate.of(2026, 7, 27))
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		val three = cycle(23, LocalDate.of(2026, 8, 17))
		// Five, ten and fifteen points over five working days each: 1, 2 and 3 a day.
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 11), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))
		delivered(three.id, LocalDate.of(2026, 8, 18), 13, listOf(ana.id))
		delivered(three.id, LocalDate.of(2026, 8, 19), 2, listOf(ana.id))
		// A fortnight nobody should still be judged on.
		delivered(older.id, LocalDate.of(2026, 7, 28), 13, listOf(ana.id))

		val hers = velocity.forPerson(ana, team.id)

		assertEquals(3, hers.cycles.size, "three by default, newest first")
		assertEquals(listOf(23, 22, 21), hers.cycles.map { it.cycle.number })
		assertEquals(2.0, assertNotNull(hers.perWorkingDay), 1e-9)
	}

	@Test
	fun `a closed cycle they delivered nothing in is a zero in the mean, not an absence`() {
		val ana = person("Ana")
		val quiet = cycle(21, LocalDate.of(2026, 8, 3))
		val busy = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(busy.id, LocalDate.of(2026, 8, 11), 5, listOf(ana.id))
		delivered(busy.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val hers = velocity.forPerson(ana, team.id)

		assertEquals(
			1.0,
			assertNotNull(hers.perWorkingDay),
			1e-9,
			"two a day and none a day average one a day; dropping the empty cycle would measure" +
				" only their good fortnights",
		)
		assertEquals(0.0, hers.cycles.single { it.cycle.id == quiet.id }.perWorkingDay, 1e-9)
	}

	@Test
	fun `a person no closed cycle can measure has no velocity, and null is not zero`() {
		val ana = person("Ana")
		val running = cycle(21, LocalDate.of(2026, 8, 3), state = CycleState.ACTIVE)
		delivered(running.id, LocalDate.of(2026, 8, 5), 13, listOf(ana.id))

		val hers = velocity.forPerson(ana, team.id)

		assertNull(
			hers.perWorkingDay,
			"a zero would read as 'delivers nothing' about somebody nothing is known about," +
				" and the declared velocity that seeds this has to be able to tell the two apart",
		)
		assertTrue(hers.cycles.isEmpty())
		assertTrue(velocity.forTeam(team.id).rows.isEmpty(), "nobody is listed with a velocity nobody has")
	}

	@Test
	fun `a weekend is not a working day, so a fortnight divides by ten`() {
		val ana = person("Ana")
		// Monday 3 August to Friday 14 August: twelve calendar days, ten working ones.
		val fortnight = cycle(21, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 14))
		delivered(fortnight.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(fortnight.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val measured = velocity.forPerson(ana, team.id).cycles.single()

		assertEquals(10, measured.workingDays, "the four weekend days are not days anybody worked")
		assertEquals(
			1.0,
			measured.perWorkingDay,
			1e-9,
			"dividing by twelve would make the same week of work read slower for having a" +
				" weekend in the middle of it",
		)
	}

	@Test
	fun `two cycles of different lengths are comparable once both are per day`() {
		val ana = person("Ana")
		val week = cycle(21, LocalDate.of(2026, 8, 3))
		val fortnight = cycle(22, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 21))
		delivered(week.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(fortnight.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))
		delivered(fortnight.id, LocalDate.of(2026, 8, 19), 5, listOf(ana.id))

		val hers = velocity.forPerson(ana, team.id)

		// One a day for the week, one a day for the fortnight. Per cycle they would be 5 and
		// 10, and their mean 7.5 would describe neither fortnight anybody worked.
		assertEquals(1.0, assertNotNull(hers.perWorkingDay), 1e-9)
	}

	@Test
	fun `the velocity is read, never stored — resizing yesterday's ticket moves it today`() {
		val ana = person("Ana")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		val ticket = delivered(c.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))

		assertEquals(1.0, assertNotNull(velocity.forPerson(ana, team.id).perWorkingDay), 1e-9)
		Tickets.update({ Tickets.id eq ticket }) { it[estimate] = 13 }

		assertEquals(
			13.0 / 5,
			assertNotNull(velocity.forPerson(ana, team.id).perWorkingDay),
			1e-9,
			"a stored rate is wrong from the moment anything underneath it changes",
		)
	}

	@Test
	fun `the team's list holds one row per person the closed cycles saw deliver`() {
		val ana = person("Ana")
		val bo = person("Bo")
		person("Never assigned anything")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(c.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(c.id, LocalDate.of(2026, 8, 6), 13, listOf(bo.id))

		val report = velocity.forTeam(team.id)

		assertEquals(
			listOf(ana.id, bo.id),
			report.rows.map { it.person.id },
			"by name, because a list of people ordered by their output is a ranking, and this" +
				" is not one",
		)
		assertEquals(listOf(c.id), report.cycles.map { it.id })
	}
}

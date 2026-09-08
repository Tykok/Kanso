package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.settings.PreferencesPatch
import dev.kanso.settings.PreferencesService
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screen 40 — what one person delivered, at what pace, and what they are still holding.
 *
 * Two halves are worth testing here and the arithmetic is only one of them. The other is
 * the set of *degenerate* shapes the screen has to survive, because they are what a real
 * instance mostly looks like: nobody has closed a cycle yet, exactly one has closed, the
 * person has an empty plate, the person has no velocity at all. Each of those is a
 * separate test below, and each asserts an absence rather than a zero — a page that draws
 * `0 pts/day` for somebody Kanso has never measured is worse than one that says so.
 *
 * The dates are real weekdays of 2026, as [VelocityTest] and [EffectiveVelocityTest] have
 * them and for the same reason: every cycle here is one working week, so the arithmetic
 * reads without a calendar. 3 August 2026 is a Monday.
 */
@Transactional
class ProgressTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var progress: ProgressService
	@Autowired lateinit var preferences: PreferencesService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "progress-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "progress-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Progress admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Advancing", "P${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** Five working days, Monday to Friday. */
	private fun cycle(number: Int, startsOn: LocalDate, state: CycleState = CycleState.CLOSED) =
		cycles.create(admin, team.id, number, startsOn, startsOn.plusDays(4), state)

	/**
	 * A ticket that reached done on [on], in [cycleId].
	 *
	 * The completion date is written rather than left to the insert, which stamps `now()`:
	 * a test whose cycles are in August 2026 and whose tickets closed today would measure
	 * nothing at all — and would start measuring something the day the clock caught up.
	 */
	private fun delivered(cycleId: UUID, on: LocalDate, estimate: Int?, assignees: List<UUID>) {
		val id = open(estimate, "done", assignees)
		Tickets.update({ Tickets.id eq id }) {
			it[completedAt] = on.atTime(10, 0).atOffset(ZoneOffset.UTC)
		}
		cycles.addTickets(admin, cycleId, listOf(id))
	}

	private fun open(
		estimate: Int?,
		status: String = "todo",
		assignees: List<UUID>,
		projectId: UUID? = null,
	): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = "carrying $estimate",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	).ticket.id

	private fun project(name: String) = projects.create(
		actor = admin,
		name = name,
		status = ProjectStatus.IN_PROGRESS,
		start = null,
		end = null,
		leadUserId = null,
		teamId = team.id,
		docIds = emptyList(),
	).project

	// --- the bars --------------------------------------------------------------

	@Test
	fun `the bars come out oldest first, whatever order the measurement was taken in`() {
		val ana = person("Ana")
		cycle(20, LocalDate.of(2026, 7, 27)).also { delivered(it.id, LocalDate.of(2026, 7, 29), 3, listOf(ana.id)) }
		cycle(21, LocalDate.of(2026, 8, 3)).also { delivered(it.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id)) }
		cycle(22, LocalDate.of(2026, 8, 10)).also { delivered(it.id, LocalDate.of(2026, 8, 11), 8, listOf(ana.id)) }

		val bars = progress.forPerson(ana, team.id).delivered

		assertEquals(
			listOf(20, 21, 22),
			bars.map { it.cycle.number },
			"a trend is read left to right in time; `VelocityService` answers newest first and" +
				" a client left to reverse it is a client that will one day draw a rise as a fall",
		)
		assertEquals(listOf(3.0, 5.0, 8.0), bars.map { it.points })
	}

	@Test
	fun `a bar says whether the number above it was measured over that cycle`() {
		val ana = person("Ana")
		// Four closed cycles: the chart draws all four, the mean stands on the newest three.
		val weeks = listOf(
			LocalDate.of(2026, 7, 20),
			LocalDate.of(2026, 7, 27),
			LocalDate.of(2026, 8, 3),
			LocalDate.of(2026, 8, 10),
		)
		weeks.forEachIndexed { at, monday ->
			cycle(20 + at, monday).also { delivered(it.id, monday.plusDays(1), 5, listOf(ana.id)) }
		}

		val bars = progress.forPerson(ana, team.id).delivered

		assertEquals(4, bars.size, "four closed cycles, four bars")
		assertEquals(
			listOf(false, true, true, true),
			bars.map { it.countedTowardsVelocity },
			"the oldest bar is history the headline number is not standing on, and a chart that" +
				" did not say so would invite the reader to average it by eye and disagree",
		)
	}

	@Test
	fun `the chart window is six closed cycles, and the seventh is off the left edge`() {
		val ana = person("Ana")
		// Seven consecutive working weeks, all closed, all with work in them.
		repeat(7) { at ->
			val monday = LocalDate.of(2026, 7, 6).plusWeeks(at.toLong())
			cycle(30 + at, monday).also { delivered(it.id, monday.plusDays(1), 5, listOf(ana.id)) }
		}

		val bars = progress.forPerson(ana, team.id).delivered

		assertEquals(ProgressService.CHART_CYCLES, bars.size)
		assertEquals(
			31,
			bars.first().cycle.number,
			"the window drops the oldest, not the newest: a trend that ends a fortnight ago is" +
				" not the trend anybody opened this page to see",
		)
	}

	@Test
	fun `a declared velocity in force means no bar claims to have been measured`() {
		val ana = person("Ana")
		preferences.save(ana.id, PreferencesPatch(declaredVelocity = 2.0))
		// One closed cycle is under the threshold, so the declaration stays in force.
		cycle(21, LocalDate.of(2026, 8, 3)).also { delivered(it.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id)) }

		val mine = progress.forPerson(ana, team.id)

		assertEquals(VelocitySource.DECLARED, mine.velocity.source)
		assertTrue(
			mine.delivered.none { it.countedTowardsVelocity },
			"the number in force was measured over nothing, so no bar may claim to be its basis",
		)
		assertEquals(1, mine.delivered.size, "the cycle still happened and is still drawn")
	}

	@Test
	fun `work nobody sized is tallied beside the bar rather than shortening it`() {
		val ana = person("Ana")
		val c = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(c.id, LocalDate.of(2026, 8, 5), 8, listOf(ana.id))
		delivered(c.id, LocalDate.of(2026, 8, 6), null, listOf(ana.id))

		val bar = progress.forPerson(ana, team.id).delivered.single()

		assertEquals(8.0, bar.points, 1e-9, "an unsized ticket is not a zero-point ticket")
		assertEquals(1, bar.unestimated, "what the bar cannot speak for travels with it")
	}

	// --- the plate -------------------------------------------------------------

	@Test
	fun `the load is the open points assigned to this person, weighed against their pace`() {
		val ana = person("Ana")
		val bo = person("Bo")
		// 2 points a working day: ten points over the cycle's five days.
		cycle(21, LocalDate.of(2026, 8, 3)).also { c ->
			delivered(c.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
			delivered(c.id, LocalDate.of(2026, 8, 6), 5, listOf(ana.id))
		}
		open(8, "in_progress", listOf(ana.id))
		open(5, "todo", listOf(ana.id))
		open(13, "todo", listOf(bo.id))
		open(3, "done", listOf(ana.id))

		val mine = progress.forPerson(ana, team.id)

		assertEquals(2, mine.load.load.tickets, "somebody else's plate and a finished ticket are not a load")
		assertEquals(13, mine.load.load.points)
		assertEquals(
			6.5,
			assertNotNull(mine.load.workingDays),
			1e-9,
			"thirteen points at two a day is six and a half days — the sentence the screen exists" +
				" to print, and the only place the two halves of this page meet",
		)
	}

	@Test
	fun `the cut holds the buckets the plate has, and nothing settled`() {
		val ana = person("Ana")
		open(8, "in_progress", listOf(ana.id))

		val byStatus = progress.forPerson(ana, team.id).load.byStatus

		// This used to assert every open status was present with its zeros, so the chart's
		// legend was the same list on every page load. `KAN-90` moved that job: "every open
		// status" has a per-team answer now, and the client already holds it — the team's
		// own ordered list for one team, `CATEGORY_ORDER` for a wider scope. What the
		// server owes is the numbers, and it owes them for the buckets it found.
		assertEquals(setOf("in_progress"), byStatus.keys)
		assertEquals(8, byStatus.getValue("in_progress").points)
		assertTrue(
			byStatus.keys.none { it == "done" || it == "canceled" },
			"a settled ticket is not part of a load, and putting it in this cut would make the" +
				" segments add up to something the days above were not divided from",
		)
	}

	@Test
	fun `the projects are heaviest first and the unfiled pile is last however big it is`() {
		val ana = person("Ana")
		val light = project("Light")
		val heavy = project("Heavy")
		open(3, "todo", listOf(ana.id), light.id)
		open(13, "todo", listOf(ana.id), heavy.id)
		open(2, "todo", listOf(ana.id))
		open(2, "todo", listOf(ana.id))
		open(2, "todo", listOf(ana.id))

		val byProject = progress.forPerson(ana, team.id).load.byProject

		assertEquals(
			listOf("Heavy", "Light", null),
			byProject.map { it.project?.name },
			"the question is which piece of work this plate is mostly made of; three unfiled" +
				" tickets outnumbering one thirteen-pointer is not an answer to it",
		)
		assertEquals(6, byProject.last().load.points)
	}

	@Test
	fun `points a plate has no estimate for are counted, never added to the sum as zeroes`() {
		val ana = person("Ana")
		preferences.save(ana.id, PreferencesPatch(declaredVelocity = 1.0))
		open(5, "todo", listOf(ana.id))
		open(null, "todo", listOf(ana.id))
		open(null, "todo", listOf(ana.id))

		val load = progress.forPerson(ana, team.id).load

		assertEquals(3, load.load.tickets)
		assertEquals(5, load.load.points)
		assertEquals(
			2,
			load.load.unestimated,
			"five days is what this plate weighs, and it weighs it over three tickets; a sum that" +
				" quietly left out two of them would read as the whole plate",
		)
		assertEquals(5.0, assertNotNull(load.workingDays), 1e-9)
	}

	// --- the shapes a real instance mostly has ---------------------------------

	@Test
	fun `a person on an instance that has closed no cycle gets an absence, not zeroes`() {
		val ana = person("Ana")
		cycle(21, LocalDate.of(2026, 8, 3), state = CycleState.ACTIVE)
		open(8, "todo", listOf(ana.id))

		val mine = progress.forPerson(ana, team.id)

		assertEquals(emptyList(), mine.delivered, "nothing has closed, so there is nothing to draw")
		assertEquals(VelocitySource.NONE, mine.velocity.source)
		assertNull(
			mine.velocity.perWorkingDay,
			"null is the absence of a measurement and 0 is a measurement; a fresh instance full" +
				" of people who deliver nothing is the wrong thing to show anybody",
		)
		assertNull(
			mine.load.workingDays,
			"there is no pace to divide by, and dividing by zero would send an infinity to a" +
				" screen that would print it",
		)
		assertEquals(8, mine.load.load.points, "the plate is still real and still readable")
	}

	@Test
	fun `exactly one closed cycle is one bar, and the screen is told it is only one`() {
		val ana = person("Ana")
		cycle(21, LocalDate.of(2026, 8, 3)).also { delivered(it.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id)) }

		val mine = progress.forPerson(ana, team.id)

		// The server sends the bar and does not decide whether to draw it: one cycle is a
		// measurement, and the settings screen prints it beside "one cycle is thin". Refusing
		// to send it would leave the page unable to say what it is waiting for. Whether a
		// chart is drawn on it is `enoughForATrend` on the client, tested there.
		assertEquals(1, mine.delivered.size)
		assertEquals(
			1,
			mine.velocity.measuredCycles,
			"how much history the number stands on is what lets the page say `one more cycle`" +
				" instead of drawing a single bar and calling it a trend",
		)
		assertEquals(VelocitySource.MEASURED, mine.velocity.source)
	}

	@Test
	fun `an empty plate is nought days rather than an unanswerable one`() {
		val ana = person("Ana")
		cycle(21, LocalDate.of(2026, 8, 3)).also { delivered(it.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id)) }

		val load = progress.forPerson(ana, team.id).load

		assertEquals(0, load.load.tickets)
		assertEquals(
			0.0,
			assertNotNull(load.workingDays),
			1e-9,
			"an empty plate genuinely is nought days of work — this is the one zero on the page" +
				" that is a measurement, and nulling it would read as `cannot say`",
		)
		assertEquals(emptyList(), load.byProject, "no tickets, no projects, and no empty rows")
	}

	@Test
	fun `no pace that reaches a plate can be zero, so no plate divides into an infinity`() {
		val ana = person("Ana")

		// The two ways a rate reaches this page, and both refuse a zero *before* the
		// division. This is asserted rather than assumed because the division in
		// `ProgressService.load` is null-safe and not zero-safe by accident: it guards, but
		// the guard would be the only thing between a loosened vocabulary and an `Infinity`
		// printed on a screen. Writing 0 into the field is the case somebody would reach for.
		assertFailsWith<BadRequestException>(
			"a person who delivers nothing at all is not a rate anything can be planned with",
		) { preferences.save(ana.id, PreferencesPatch(declaredVelocity = 0.0)) }

		// And the measured half, which is not a validation but an arbitration: a person the
		// closed cycles saw finish nothing is an absence rather than a rate of zero.
		cycle(21, LocalDate.of(2026, 8, 3)).also { delivered(it.id, LocalDate.of(2026, 8, 5), 5, listOf(person("Bo").id)) }
		open(8, "todo", listOf(ana.id))

		val mine = progress.forPerson(ana, team.id)

		assertEquals(VelocitySource.NONE, mine.velocity.source)
		assertNull(mine.load.workingDays, "an infinity is not a number a screen can print")
		assertEquals(8, mine.load.load.points, "and the plate is still readable without a pace")
	}

	@Test
	fun `a cycle this person delivered nothing in is a bar of nothing, not a missing bar`() {
		val ana = person("Ana")
		val bo = person("Bo")
		val quiet = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(quiet.id, LocalDate.of(2026, 8, 5), 5, listOf(bo.id))
		cycle(22, LocalDate.of(2026, 8, 10)).also { delivered(it.id, LocalDate.of(2026, 8, 11), 5, listOf(ana.id)) }

		val bars = progress.forPerson(ana, team.id).delivered

		assertEquals(
			listOf(0.0, 5.0),
			bars.map { it.points },
			"a fortnight somebody shipped nothing is part of their own trend; dropping the bar" +
				" would compress the gap out of the chart and flatter the line",
		)
	}

	@Test
	fun `the subject is an argument, so the same read answers for somebody else`() {
		val ana = person("Ana")
		val bo = person("Bo")
		cycle(21, LocalDate.of(2026, 8, 3)).also { c ->
			delivered(c.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
			delivered(c.id, LocalDate.of(2026, 8, 6), 8, listOf(bo.id))
		}
		open(8, "todo", listOf(bo.id))

		// This is the seam the other-person view will use, and it is asserted here so that
		// ticket finds a service that already answers rather than one it has to widen.
		// Nothing about who may *ask* is decided in this file; that is the other ticket.
		val his = progress.forPerson(bo, team.id)

		assertEquals(bo.id, his.person.id)
		assertEquals(8.0, his.delivered.single().points, 1e-9)
		assertEquals(8, his.load.load.points)
		assertEquals(
			5.0,
			progress.forPerson(ana, team.id).delivered.single().points,
			1e-9,
			"and the two subjects do not bleed into one another",
		)
	}
}

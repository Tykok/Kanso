package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `/api/me/stats`: the four numbers the personal strip prints, the twelve weeks under
 * them, and the commitment that can be read while a cycle is still running.
 *
 * Every date here is fixed. [NOW] is Wednesday 2 September 2026, so the twelve ISO weeks
 * the service answers with are the Mondays 15 June to 31 August 2026 — no year boundary
 * in the window, which is the one thing that would make the numbers below unreadable
 * without a calendar beside them. A test anchored on the clock instead would measure
 * nothing in a fortnight and something different again next quarter, which is the same
 * trap `VelocityTest` writes its completion dates by hand to avoid.
 *
 * What the assertions are guarding is mostly *absence*: a week nobody closed anything in
 * has to be a zero rather than a missing bucket, an unsized ticket has to be counted
 * beside the points it is not in, and a shared ticket's points have to be halved on both
 * plates rather than doubled across them.
 */
@Transactional
class MyStatsTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var dependencies: DependencyRepository
	@Autowired lateinit var stats: MyStatsService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String): User {
		val user = users.createLocalUser(
			email = "stats-${UUID.randomUUID()}@kanso.test",
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
		teamRows.addMember(team.id, user.id, MemberRole.MEMBER)
		return user
	}

	private val admin: User by lazy {
		users.createLocalUser(
			email = "stats-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Stats admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Reporting", "S${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** An open ticket on somebody's plate. */
	private fun open(
		assignees: List<UUID>,
		estimate: Int? = null,
		status: DefaultStatus = DefaultStatus.IN_PROGRESS,
		due: KansoInstant? = null,
	): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = "open $estimate",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = due,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	).ticket.id

	/**
	 * A finished ticket, closed on [on]. The completion date is written here rather than
	 * left to the insert, which stamps `now()` — the same reason `VelocityTest` does it:
	 * a fixture whose weeks are in the summer of 2026 and whose tickets closed today
	 * measures nothing at all.
	 */
	private fun finished(assignees: List<UUID>, on: OffsetDateTime, estimate: Int? = null): UUID {
		val id = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "finished $estimate",
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
		Tickets.update({ Tickets.id eq id }) { it[completedAt] = on }
		return id
	}

	private fun cycle(number: Int, startsOn: LocalDate, endsOn: LocalDate, state: CycleState) =
		cycles.create(admin, team.id, number, startsOn, endsOn, state)

	// --- the twelve weeks ------------------------------------------------------

	@Test
	fun `twelve weeks are twelve buckets, and a week nothing closed in is a zero`() {
		val ana = person("Ana")
		finished(listOf(ana.id), NOW.minusWeeks(2), estimate = 5)
		finished(listOf(ana.id), NOW, estimate = 3)

		val weeks = stats.forPerson(ana, NOW).weeks

		assertEquals(12, weeks.size, "twelve weeks, whatever happened in them")
		assertEquals(
			LocalDate.of(2026, 6, 15),
			weeks.first().startsOn,
			"oldest first, and each bucket is named by the Monday its ISO week begins on",
		)
		assertEquals(LocalDate.of(2026, 8, 31), weeks.last().startsOn)
		assertEquals(
			listOf(25, 36),
			listOf(weeks.first().isoWeek, weeks.last().isoWeek),
			"ISO weeks, decided here rather than in the browser",
		)

		val quiet = weeks.single { it.startsOn == LocalDate.of(2026, 8, 24) }
		assertEquals(0, quiet.finished, "a week between two busy ones is a zero, not a gap")
		assertEquals(0.0, quiet.points, 1e-9)
		assertEquals(
			listOf(0, 5, 0, 3),
			weeks.takeLast(4).map { it.points.toInt() },
			"the bars land in the weeks the work actually closed in",
		)
	}

	@Test
	fun `work closed before the window is out of it, and out of the recent list too`() {
		val ana = person("Ana")
		finished(listOf(ana.id), NOW.minusWeeks(20), estimate = 8)

		val mine = stats.forPerson(ana, NOW)

		assertEquals(0.0, mine.weeks.sumOf { it.points }, 1e-9)
		assertTrue(
			mine.recentlyFinished.isEmpty(),
			"the list is the window's own tickets; a chart of twelve weeks beside a list" +
				" reaching back five months would be two answers to one question",
		)
	}

	@Test
	fun `an eight-point ticket carried by two people is four points each, not eight each`() {
		val ana = person("Ana")
		val bo = person("Bo")
		finished(listOf(ana.id, bo.id), NOW, estimate = 8)

		val hers = stats.forPerson(ana, NOW).weeks.last()
		val his = stats.forPerson(bo, NOW).weeks.last()

		assertEquals(4.0, hers.points, 1e-9)
		assertEquals(4.0, his.points, 1e-9)
		assertEquals(
			8.0,
			hers.points + his.points,
			1e-9,
			"a pair's two bars have to add up to the ticket, or the two charts report work" +
				" the team never shipped",
		)
		assertEquals(
			1,
			hers.finished,
			"the row is counted whole on both plates: it is one thing she finished, and half" +
				" a ticket is not a thing anybody finished",
		)
	}

	@Test
	fun `a ticket nobody sized is counted in its week rather than weighed as a zero`() {
		val ana = person("Ana")
		finished(listOf(ana.id), NOW, estimate = 5)
		finished(listOf(ana.id), NOW, estimate = null)

		val week = stats.forPerson(ana, NOW).weeks.last()

		assertEquals(2, week.finished)
		assertEquals(
			5.0,
			week.points,
			1e-9,
			"an unsized ticket is not a zero-point ticket; adding it to the sum would shrink" +
				" the bar for the week somebody forgot to estimate",
		)
		assertEquals(1, week.unestimated, "what the bar cannot speak for travels with it")
	}

	// --- the strip -------------------------------------------------------------

	@Test
	fun `open counts my unfinished tickets, and finished this week is the last bucket`() {
		val ana = person("Ana")
		val bo = person("Bo")
		open(listOf(ana.id))
		open(listOf(ana.id, bo.id))
		open(listOf(bo.id))
		finished(listOf(ana.id), NOW, estimate = 2)
		finished(listOf(ana.id), NOW.minusWeeks(3), estimate = 2)

		val mine = stats.forPerson(ana, NOW)

		assertEquals(2, mine.strip.open, "hers, including the one she shares; not Bo's own")
		assertEquals(
			mine.weeks.last().finished,
			mine.strip.finishedThisWeek,
			"the strip's fourth number is the chart's last bar, read off the same buckets:" +
				" two counts of one thing can disagree, and one cannot",
		)
		assertEquals(1, mine.strip.finishedThisWeek)
	}

	@Test
	fun `a due date is overdue once its day is over, and today is not over`() {
		val ana = person("Ana")
		open(listOf(ana.id), due = day(NOW.minusDays(1)))
		open(listOf(ana.id), due = day(NOW))
		open(listOf(ana.id), due = day(NOW.plusDays(3)))
		open(listOf(ana.id))

		val strip = stats.forPerson(ana, NOW).strip

		assertEquals(
			1,
			strip.overdue,
			"a ticket due today has the rest of today; counting it would put an alarm on the" +
				" screen of somebody who is not late yet",
		)
		assertEquals(4, strip.open)
	}

	@Test
	fun `an hour that has passed is overdue on the same day it was due`() {
		val ana = person("Ana")
		open(listOf(ana.id), due = KansoInstant(NOW.minusHours(2), hasTime = true))
		open(listOf(ana.id), due = KansoInstant(NOW.plusHours(2), hasTime = true))

		assertEquals(
			1,
			stats.forPerson(ana, NOW).strip.overdue,
			"a deadline with a time on it is a deadline at that time; rounding it to the end" +
				" of the day would forgive a whole afternoon",
		)
	}

	@Test
	fun `blocked is an open ticket whose predecessor has not finished`() {
		val ana = person("Ana")
		val waitingOnOpen = open(listOf(ana.id))
		val waitingOnDone = open(listOf(ana.id))
		dependencies.insert(predecessorId = open(listOf(ana.id)), successorId = waitingOnOpen)
		dependencies.insert(
			predecessorId = finished(listOf(ana.id), NOW.minusWeeks(1)),
			successorId = waitingOnDone,
		)

		val strip = stats.forPerson(ana, NOW).strip

		assertEquals(
			1,
			strip.blocked,
			"finish-to-start: the arrow only holds while the thing upstream is unfinished," +
				" and a closed predecessor blocks nobody",
		)
	}

	@Test
	fun `a cancelled predecessor blocks nothing, because nobody is waiting for it`() {
		val ana = person("Ana")
		val successor = open(listOf(ana.id))
		val abandoned = open(listOf(ana.id), status = DefaultStatus.CANCELED)
		dependencies.insert(predecessorId = abandoned, successorId = successor)

		assertEquals(
			0,
			stats.forPerson(ana, NOW).strip.blocked,
			"a cancelled predecessor is a decision not to do the work; reading it as a block" +
				" would leave the successor waiting for something nobody will ever finish",
		)
	}

	@Test
	fun `only my side of an arrow counts — a ticket I am waiting on is not one I am blocked on`() {
		val ana = person("Ana")
		val bo = person("Bo")
		val mine = open(listOf(ana.id))
		dependencies.insert(predecessorId = mine, successorId = open(listOf(bo.id)))

		assertEquals(
			0,
			stats.forPerson(ana, NOW).strip.blocked,
			"the edge is read in one direction; being somebody else's predecessor is not being" +
				" blocked, it is being waited on",
		)
	}

	// --- estimate hygiene ------------------------------------------------------

	@Test
	fun `the unsized count is about open work, which is the only work still sizeable`() {
		val ana = person("Ana")
		open(listOf(ana.id), estimate = 5)
		open(listOf(ana.id), estimate = null)
		open(listOf(ana.id), estimate = null)
		finished(listOf(ana.id), NOW, estimate = null)

		assertEquals(
			2,
			stats.forPerson(ana, NOW).openUnestimated,
			"the number sits beside a velocity to explain why it understates; a finished" +
				" ticket nobody sized is reported by the week it closed in, not here",
		)
	}

	// --- the commitment --------------------------------------------------------

	@Test
	fun `the active cycle's commitment is my points in it, committed against finished`() {
		val ana = person("Ana")
		val running = cycle(24, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 11), CycleState.ACTIVE)
		cycles.addTickets(
			admin,
			running.id,
			listOf(
				finished(listOf(ana.id), NOW, estimate = 5),
				open(listOf(ana.id), estimate = 8),
				open(listOf(ana.id), estimate = null),
			),
		)

		val commitment = stats.forPerson(ana, NOW).commitments.single()

		assertEquals(24, commitment.cycleNumber)
		assertEquals(team.id, commitment.teamId)
		assertEquals(3, commitment.committed)
		assertEquals(1, commitment.finished)
		assertEquals(13.0, commitment.committedPoints, 1e-9)
		assertEquals(5.0, commitment.finishedPoints, 1e-9)
		assertEquals(
			1,
			commitment.unestimated,
			"the unsized ticket is in the commitment and out of both point totals, so the" +
				" fraction can say what it is not describing",
		)
	}

	@Test
	fun `a shared commitment is halved on both sides of the fraction`() {
		val ana = person("Ana")
		val bo = person("Bo")
		val running = cycle(24, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 11), CycleState.ACTIVE)
		cycles.addTickets(
			admin,
			running.id,
			listOf(
				finished(listOf(ana.id, bo.id), NOW, estimate = 8),
				open(listOf(ana.id, bo.id), estimate = 8),
			),
		)

		val hers = stats.forPerson(ana, NOW).commitments.single()

		assertEquals(8.0, hers.committedPoints, 1e-9)
		assertEquals(
			4.0,
			hers.finishedPoints,
			1e-9,
			"both halves are split by the same rule, or the ratio between them is a number" +
				" about two different people",
		)
	}

	@Test
	fun `a cycle nobody has started and a cycle already closed are no commitment at all`() {
		val ana = person("Ana")
		val closed = cycle(23, LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 28), CycleState.CLOSED)
		val planned = cycle(25, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 25), CycleState.UPCOMING)
		cycles.addTickets(admin, closed.id, listOf(finished(listOf(ana.id), NOW.minusWeeks(1), estimate = 5)))
		cycles.addTickets(admin, planned.id, listOf(open(listOf(ana.id), estimate = 8)))

		assertTrue(
			stats.forPerson(ana, NOW).commitments.isEmpty(),
			"a commitment is what is in force: the closed cycle is history and velocity's" +
				" subject, the upcoming one is a plan nobody has signed yet",
		)
	}

	@Test
	fun `a cycle I hold nothing in is not my commitment`() {
		val ana = person("Ana")
		val bo = person("Bo")
		val running = cycle(24, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 11), CycleState.ACTIVE)
		cycles.addTickets(admin, running.id, listOf(open(listOf(bo.id), estimate = 8)))

		assertTrue(
			stats.forPerson(ana, NOW).commitments.isEmpty(),
			"an empty commitment would read as somebody who committed to nothing, which is" +
				" not what a cycle they have no work in says about them",
		)
	}

	// --- the recent list -------------------------------------------------------

	@Test
	fun `the recent list carries the completion date the shared ticket DTO does not`() {
		val ana = person("Ana")
		val older = finished(listOf(ana.id), NOW.minusWeeks(4), estimate = 3)
		val newest = finished(listOf(ana.id), NOW, estimate = 5)

		val recent = stats.forPerson(ana, NOW).recentlyFinished

		assertEquals(listOf(newest, older), recent.map { it.id }, "newest completion first")
		val head = recent.first()
		assertEquals(NOW.toInstant(), head.completedAt.toInstant())
		assertEquals(5, head.estimate)
		assertTrue(
			assertNotNull(head.identifier).startsWith("${team.key}-"),
			"the list is read as `KAN-142 · title`, so the identifier is resolved here rather" +
				" than left as a pair of columns the screen has to join",
		)
	}

	companion object {
		/**
		 * Wednesday 2 September 2026, 10:00 UTC. Its ISO week is 36 and its Monday is
		 * 31 August, so the window this fixture reads is weeks 25 to 36 of one year.
		 */
		private val NOW: OffsetDateTime = OffsetDateTime.of(2026, 9, 2, 10, 0, 0, 0, ZoneOffset.UTC)

		/** A due date with no time on it — the granularity most dates in Kanso have. */
		private fun day(at: OffsetDateTime) = KansoInstant(at, hasTime = false)
	}
}

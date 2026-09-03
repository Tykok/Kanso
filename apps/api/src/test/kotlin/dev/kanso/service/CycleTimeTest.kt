package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KAN-23 — cycle time and work in flight, read back out of `activity`.
 *
 * Every test below writes its own `status_changed` rows with an explicit `createdAt` rather
 * than driving `TicketService.patch`, and that is the only way this file can say anything:
 * a patch stamps `now()`, so a suite that moved tickets through statuses could assert that
 * some hours had passed and never *which*. The rows written here are the same shape
 * `TicketService` writes — `{"from": …, "to": …}` in wire values — and `ActivityServiceTest`
 * is what guards that shape itself.
 *
 * The dates are real weekdays of 2026, as [ProgressTest] and [VelocityTest] have them and
 * for the same reason: 27 July 2026 is a Monday, every cycle here is one working week, and
 * the arithmetic reads without a calendar.
 *
 * `now` is passed in everywhere it matters. A WIP age measured against the wall clock is a
 * test that asserts a different number every day it runs.
 */
@Transactional
class CycleTimeTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var cycleTime: CycleTimeService
	@Autowired lateinit var activityRows: ActivityRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "cycletime-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "cycletime-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Cycle time admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Flowing", "F${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** Five working days, Monday to Friday, closed. */
	private fun cycle(number: Int, startsOn: LocalDate) =
		cycles.create(admin, team.id, number, startsOn, startsOn.plusDays(4), CycleState.CLOSED)

	private fun at(day: LocalDate, hour: Int): OffsetDateTime =
		day.atTime(hour, 0).atOffset(ZoneOffset.UTC)

	/**
	 * A ticket that reached done at [completedAt], in [cycleId], with no history yet.
	 *
	 * The history is the caller's business — every test in this file differs in exactly
	 * which `status_changed` rows exist, which is the whole subject.
	 */
	private fun delivered(
		cycleId: UUID,
		completedAt: OffsetDateTime,
		assignees: List<UUID>,
		estimate: Int? = 3,
	): UUID {
		val id = ticket(TicketStatus.DONE, assignees, estimate)
		Tickets.update({ Tickets.id eq id }) { it[Tickets.completedAt] = completedAt }
		cycles.addTickets(admin, cycleId, listOf(id))
		return id
	}

	private fun ticket(status: TicketStatus, assignees: List<UUID>, estimate: Int? = 3): UUID =
		tickets.create(
			actor = admin,
			teamId = team.id,
			title = "flowing",
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

	/** One `status_changed`, at an instant this test chose. */
	private fun moved(ticketId: UUID, from: TicketStatus, to: TicketStatus, on: OffsetDateTime) {
		activityRows.insert(
			id = UUID.randomUUID(),
			entity = ActivityEntity.TICKET,
			entityId = ticketId,
			actorId = null,
			kind = ActivityKind.STATUS_CHANGED,
			payload = """{"from":"${from.wire}","to":"${to.wire}"}""",
			createdAt = on,
		)
	}

	// --- where the clock starts and stops --------------------------------------

	@Test
	fun `the clock runs from the first start to the completion, in elapsed hours`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		val id = delivered(closed.id, at(MONDAY.plusDays(2), 9), listOf(ana.id))
		moved(id, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY, 9))

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		// Monday 09:00 to Wednesday 09:00 — two calendar days, and elapsed hours are hours
		// and not working days, so this is 48 and not 16.
		assertEquals(48.0, insights.cycleTime.medianHours, "two days is forty-eight hours")
		assertEquals(1, insights.cycleTime.measured, "one ticket in the sample")
		assertEquals(0, insights.cycleTime.unmeasured, "nothing was dropped")
	}

	@Test
	fun `a ticket parked and picked up again keeps its first start, so churn shows`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		val id = delivered(closed.id, at(MONDAY.plusDays(4), 9), listOf(ana.id))
		moved(id, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY, 9))
		moved(id, TicketStatus.IN_PROGRESS, TicketStatus.TODO, at(MONDAY.plusDays(1), 9))
		moved(id, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY.plusDays(3), 9))

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		// The whole saga: Monday 09:00 to Friday 09:00. Reading the *latest* start instead
		// would answer 24 — the twenty-four hours of the final push — and would mean a
		// ticket bounced four times reported better than one done in a single pass. This
		// number is supposed to get worse when work is churned.
		assertEquals(96.0, insights.cycleTime.medianHours, "measured from the first start, not the last")
	}

	@Test
	fun `a start is any transition into a started status, so a skipped in_progress still counts`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		val id = delivered(closed.id, at(MONDAY.plusDays(1), 9), listOf(ana.id))
		// Straight from `todo` to review, which a small team does constantly. Keyed on the
		// category rather than on `in_progress` by name, so this is a start.
		moved(id, TicketStatus.TODO, TicketStatus.IN_REVIEW, at(MONDAY, 9))

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		assertEquals(24.0, insights.cycleTime.medianHours, "review is work in flight, so it starts the clock")
		assertEquals(0, insights.cycleTime.unmeasured, "a skipped status is not a missing measurement")
	}

	@Test
	fun `a ticket dragged straight to done is unmeasured, never a zero`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		val id = delivered(closed.id, at(MONDAY.plusDays(1), 9), listOf(ana.id))
		// The only row is a transition into a *completed* status. Nothing ever started.
		moved(id, TicketStatus.TODO, TicketStatus.DONE, at(MONDAY.plusDays(1), 9))

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		// Absent rather than `0.0`, which would be a claim that the work was instantaneous —
		// and, pooled with real tickets, would drag the median towards a boast.
		assertNull(insights.cycleTime.medianHours, "no start means no cycle time")
		assertEquals(0, insights.cycleTime.measured, "nothing to stand on")
		assertEquals(1, insights.cycleTime.unmeasured, "and the screen is told how many")
	}

	// --- the median ------------------------------------------------------------

	@Test
	fun `the median ignores an outlier a mean would follow`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		// Three quick tickets and one that sat for a fortnight. The mean of 24, 24, 24 and
		// 720 is 198 hours — a number describing the outlier and claiming to describe the
		// work. The middle of the sample is unmoved.
		listOf(1, 1, 1, 30).forEach { days ->
			val id = delivered(closed.id, at(MONDAY.plusDays(2), 9), listOf(ana.id))
			moved(id, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY.plusDays(2), 9).minusDays(days.toLong()))
		}

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		// Four values: 24, 24, 24, 720. The two middles are both 24.
		assertEquals(24.0, insights.cycleTime.medianHours, "the middle, not the average")
		assertEquals(4, insights.cycleTime.measured, "and the outlier is still in the sample")
	}

	@Test
	fun `an even sample takes the mean of the two middles`() {
		val ana = person("Ana")
		val closed = cycle(20, MONDAY)
		listOf(1L, 2L).forEach { days ->
			val id = delivered(closed.id, at(MONDAY.plusDays(3), 9), listOf(ana.id))
			moved(id, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY.plusDays(3), 9).minusDays(days))
		}

		val insights = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)

		// 24 and 48, so 36 — not 24 and not 48, either of which would be picking a side on
		// an even sample.
		assertEquals(36.0, insights.cycleTime.medianHours, "the mean of the two middles")
	}

	@Test
	fun `a person's median counts their own tickets and nobody else's`() {
		val ana = person("Ana")
		val ben = person("Ben")
		val closed = cycle(20, MONDAY)
		delivered(closed.id, at(MONDAY.plusDays(1), 9), listOf(ana.id)).also {
			moved(it, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY, 9))
		}
		delivered(closed.id, at(MONDAY.plusDays(4), 9), listOf(ben.id)).also {
			moved(it, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY, 9))
		}

		val hers = cycleTime.forCycles(listOf(closed), team.id, ana.id, NOW)
		val teamWide = cycleTime.forCycles(listOf(closed), team.id, assigneeId = null, now = NOW)

		assertEquals(24.0, hers.cycleTime.medianHours, "hers took a day")
		assertEquals(1, hers.cycleTime.measured, "and Ben's is not on her page")
		// The team read has no assignee filter at all, so both are in it: 24 and 96.
		assertEquals(60.0, teamWide.cycleTime.medianHours, "the team's median is over both")
		assertEquals(2, teamWide.cycleTime.measured, "including the ticket nobody's page counted twice")
	}

	// --- the trend -------------------------------------------------------------

	@Test
	fun `the trend is oldest first, and a cycle with nothing delivered has no median at all`() {
		val ana = person("Ana")
		val older = cycle(20, MONDAY)
		val newer = cycle(21, MONDAY.plusDays(7))
		val quiet = cycle(22, MONDAY.plusDays(14))
		delivered(older.id, at(MONDAY.plusDays(1), 9), listOf(ana.id)).also {
			moved(it, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY, 9))
		}
		delivered(newer.id, at(MONDAY.plusDays(8), 9), listOf(ana.id)).also {
			moved(it, TicketStatus.TODO, TicketStatus.IN_PROGRESS, at(MONDAY.plusDays(7), 9))
		}

		// Handed over newest first, the order `CycleService.closed` answers in.
		val insights = cycleTime.forCycles(listOf(quiet, newer, older), team.id, ana.id, NOW)

		assertEquals(
			listOf(20, 21, 22),
			insights.trend.map { it.cycle.number },
			"reversed on the server, so no client can draw a rise as a fall",
		)
		assertEquals(24.0, insights.trend[0].cycleTime.medianHours, "cycle 20 took a day")
		// The quiet cycle is absent rather than zero, which is what the chart hatches: a
		// cycle that delivered nothing has no cycle time, and a short bar would read as fast.
		assertNull(insights.trend[2].cycleTime.medianHours, "nothing delivered is not nought hours")
		assertEquals(0, insights.trend[2].cycleTime.unmeasured, "and nothing was dropped either")
	}

	@Test
	fun `no closed cycle answers an absent median rather than a zero`() {
		val ana = person("Ana")

		val insights = cycleTime.forCycles(emptyList(), team.id, ana.id, NOW)

		assertNull(insights.cycleTime.medianHours, "a fresh instance has nothing to measure")
		assertEquals(0, insights.cycleTime.measured, "and says so")
		assertTrue(insights.trend.isEmpty(), "no cycles, no points")
		assertEquals(0, insights.wip.load.tickets, "and nothing in flight")
		assertNull(insights.wip.oldestAgeHours, "which is an absence, not an age of nought")
	}

	// --- work in flight --------------------------------------------------------

	@Test
	fun `work in flight is the started statuses only, aged from the first start`() {
		val ana = person("Ana")
		val inProgress = ticket(TicketStatus.IN_PROGRESS, listOf(ana.id))
		val inReview = ticket(TicketStatus.IN_REVIEW, listOf(ana.id), estimate = 5)
		// A `todo` ticket is a load somebody will pick up and is not work in progress, so it
		// is out of this count however long it has sat. `WorkloadService.OPEN_STATUSES` is
		// deliberately wider than this.
		ticket(TicketStatus.TODO, listOf(ana.id))
		moved(inProgress, TicketStatus.TODO, TicketStatus.IN_PROGRESS, NOW.minusHours(72))
		moved(inReview, TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW, NOW.minusHours(24))

		val wip = cycleTime.forCycles(emptyList(), team.id, ana.id, NOW).wip

		assertEquals(2, wip.load.tickets, "the todo ticket is not in flight")
		assertEquals(8, wip.load.points, "3 and 5, and the todo ticket's weight is not in it")
		assertEquals(72.0, wip.oldestAgeHours, "the oldest has been in flight three days")
		assertEquals(48.0, wip.medianAgeHours, "the mean of the two middles of 24 and 72")
		assertEquals(0, wip.unmeasured, "both recorded a start")
	}

	@Test
	fun `something in flight with no recorded start is counted but not aged`() {
		val ana = person("Ana")
		val known = ticket(TicketStatus.IN_PROGRESS, listOf(ana.id))
		// Created straight into `in_progress`, so there is no transition into it anywhere —
		// the shape an import leaves, and the shape a ticket someone typed in flight leaves.
		ticket(TicketStatus.IN_PROGRESS, listOf(ana.id))
		moved(known, TicketStatus.TODO, TicketStatus.IN_PROGRESS, NOW.minusHours(10))

		val wip = cycleTime.forCycles(emptyList(), team.id, ana.id, NOW).wip

		assertEquals(2, wip.load.tickets, "both are on the plate")
		assertEquals(10.0, wip.oldestAgeHours, "only one of them can be aged")
		assertEquals(1, wip.unmeasured, "and the screen is told the other cannot be")
	}

	companion object {
		/** 27 July 2026, a Monday. */
		private val MONDAY: LocalDate = LocalDate.of(2026, 7, 27)

		/**
		 * A fixed "now", well past every cycle above.
		 *
		 * Passed into every read rather than left to the clock. A WIP age is measured against
		 * the present by definition, and a test that used the real one would assert a
		 * different number on every run — the one failure mode that looks like flakiness and
		 * is arithmetic.
		 */
		private val NOW: OffsetDateTime = LocalDate.of(2026, 9, 1).atTime(9, 0).atOffset(ZoneOffset.UTC)
	}
}

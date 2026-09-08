package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `points ÷ the assignees' velocity = working days`, and the three refusals.
 *
 * The refusals are the half worth testing hardest. Each has to be *distinguishable* — a
 * screen that cannot tell "nobody sized this" from "nobody is on this" tells the reader to
 * fix the wrong thing, and a caller holding one nullable range cannot tell them apart at
 * all. So every absence below is asserted as its own type, not as an absent number.
 *
 * Velocities are set by declaration rather than by closing cycles wherever the cycle
 * history is not the thing under test: it is the same arbitrated number either way — the
 * declared branch of a rule `EffectiveVelocityTest` already pins — and it keeps the
 * arithmetic in these tests visible on one line.
 */
@Transactional
class TicketDurationTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var durations: TicketDurationService
	@Autowired lateinit var preferences: PreferencesService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String, declares: Double? = null): User {
		val user = users.createLocalUser(
			email = "duration-${UUID.randomUUID()}@kanso.test",
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
		declares?.let { preferences.save(user.id, PreferencesPatch(declaredVelocity = it)) }
		return user
	}

	private val admin: User by lazy {
		users.createLocalUser(
			email = "duration-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Duration admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Dating", "D${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(
		estimate: Int?,
		assignees: List<UUID>,
		teamId: UUID? = team.id,
		state: String = "todo",
	) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = "to be dated",
		description = null,
		status = state,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	)

	private fun estimated(estimate: Int?, assignees: List<UUID>, teamId: UUID? = team.id) =
		assertIs<TicketDuration.Estimated>(durations.of(ticket(estimate, assignees, teamId)))

	// --- the arithmetic ---------------------------------------------------------------

	@Test
	fun `eight points at one a day is a range around eight working days`() {
		val ana = person("Ana", declares = 1.0)

		val duration = estimated(8, listOf(ana.id))

		// A quarter either side of eight, floored and ceilinged outward.
		assertEquals(6, duration.lowWorkingDays)
		assertEquals(10, duration.highWorkingDays)
		assertEquals(1, duration.assignees)
		assertEquals(0, duration.withoutVelocity)
	}

	@Test
	fun `two assignees' velocities add`() {
		val ana = person("Ana", declares = 1.0)
		val bo = person("Bo", declares = 1.0)

		val alone = estimated(8, listOf(ana.id))
		val together = estimated(8, listOf(ana.id, bo.id))

		// Eight points at two a day is four days: "3 to 5", against Ana's own "6 to 10".
		assertEquals(3, together.lowWorkingDays)
		assertEquals(5, together.highWorkingDays)
		assertEquals(2, together.assignees)
		assertTrue(
			together.highWorkingDays < alone.highWorkingDays,
			"strictly, two people on one task do not go twice as fast — but any coordination" +
				" term would be a number Kanso cannot back, arriving inside a range that already" +
				" claims to be uncertain",
		)
	}

	@Test
	fun `unequal velocities add rather than average`() {
		val quick = person("Quick", declares = 3.0)
		val steady = person("Steady", declares = 1.0)

		val duration = estimated(8, listOf(quick.id, steady.id))

		// Four a day between them: two days. Their *mean* would be two a day and four days,
		// which would describe a pair that is slower than one of its own members.
		assertEquals(1, duration.lowWorkingDays)
		assertEquals(3, duration.highWorkingDays)
	}

	@Test
	fun `it is a range and never a single day`() {
		val ana = person("Ana", declares = 1.0)

		val duration = estimated(13, listOf(ana.id))

		assertTrue(
			duration.highWorkingDays > duration.lowWorkingDays,
			"a bare number off a three-cycle mean gets believed to the day, which none of the" +
				" data underneath it supports",
		)
	}

	@Test
	fun `a range never reaches zero days`() {
		val fast = person("Fast", declares = 20.0)

		val duration = estimated(1, listOf(fast.id))

		assertEquals(1, duration.lowWorkingDays, "'zero working days' is not an answer anybody can plan with")
		assertEquals(1, duration.highWorkingDays)
	}

	@Test
	fun `a measured velocity dates a ticket exactly as a declared one does`() {
		val ana = person("Ana")
		val one = cycles.create(admin, team.id, 21, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 7), CycleState.CLOSED)
		val two = cycles.create(admin, team.id, 22, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14), CycleState.CLOSED)
		// Five points over five working days, twice: one a day, measured.
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, ana.id)
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, ana.id)

		val duration = estimated(8, listOf(ana.id))

		assertEquals(6, duration.lowWorkingDays, "the duration divides by whatever the arbitration put in force")
		assertEquals(10, duration.highWorkingDays)
	}

	@Test
	fun `the range moves when the ticket is resized, because nothing is stored`() {
		val ana = person("Ana", declares = 1.0)
		val detail = ticket(8, listOf(ana.id))
		val before = assertIs<TicketDuration.Estimated>(durations.of(detail))

		Tickets.update({ Tickets.id eq detail.ticket.id }) { it[estimate] = 13 }
		val after = assertIs<TicketDuration.Estimated>(durations.forTicket(detail.ticket.id))

		assertEquals(6, before.lowWorkingDays)
		assertEquals(9, after.lowWorkingDays, "a stored duration is wrong from the moment anything under it changes")
	}

	// --- the three refusals, each distinguishable from the others -----------------------

	@Test
	fun `a ticket nobody sized has no numerator, and says so by name`() {
		val ana = person("Ana", declares = 1.0)

		assertEquals(TicketDuration.NoEstimate, durations.of(ticket(estimate = null, assignees = listOf(ana.id))))
	}

	@Test
	fun `a ticket nobody is on has no pace to divide by, and says so by name`() {
		assertEquals(TicketDuration.NoAssignee, durations.of(ticket(estimate = 8, assignees = emptyList())))
	}

	@Test
	fun `an assignee nobody can measure is unknown, not zero and not infinite`() {
		val stranger = person("Never declared, never delivered")

		assertEquals(TicketDuration.NoVelocity, durations.of(ticket(estimate = 8, assignees = listOf(stranger.id))))
	}

	@Test
	fun `the three absences are three different values`() {
		val ana = person("Ana", declares = 1.0)
		val stranger = person("Stranger")

		val absences = listOf(
			durations.of(ticket(estimate = null, assignees = listOf(ana.id))),
			durations.of(ticket(estimate = 8, assignees = emptyList())),
			durations.of(ticket(estimate = 8, assignees = listOf(stranger.id))),
		)

		assertEquals(
			listOf(TicketDuration.NoEstimate, TicketDuration.NoAssignee, TicketDuration.NoVelocity),
			absences,
			"a screen that cannot tell these apart tells the reader to fix the wrong thing," +
				" and an empty field tells them nothing at all",
		)
		assertEquals(3, absences.toSet().size)
	}

	@Test
	fun `a ticket missing both a size and an owner is reported as unsized`() {
		assertEquals(
			TicketDuration.NoEstimate,
			durations.of(ticket(estimate = null, assignees = emptyList())),
			"sizing it is the cheaper of the two gestures and the one that is nobody else's decision",
		)
	}

	// --- the part the range cannot account for -------------------------------------------

	@Test
	fun `an assignee with no velocity is counted rather than silently ignored`() {
		val ana = person("Ana", declares = 1.0)
		val stranger = person("Stranger")

		val duration = estimated(8, listOf(ana.id, stranger.id))

		assertEquals(2, duration.assignees)
		assertEquals(
			1,
			duration.withoutVelocity,
			"they contribute nothing to the sum, so the range is pessimistic by however much" +
				" they would have added — a blind spot that has to travel with the number",
		)
		assertEquals(6, duration.lowWorkingDays, "and the number itself is Ana's alone")
	}

	@Test
	fun `a ticket no team has claimed is dated from declarations alone`() {
		val ana = person("Ana", declares = 2.0)

		val duration = estimated(8, listOf(ana.id), teamId = null)

		// No team means no cycle calendar and so nothing measurable — the first branch of the
		// same rule, not a special case. Four days at two a day.
		assertEquals(3, duration.lowWorkingDays)
		assertEquals(5, duration.highWorkingDays)
	}

	/** Closed on [on], in [cycleId] — the shape `VelocityService` measures. */
	private fun delivered(cycleId: UUID, on: LocalDate, estimate: Int, assignee: UUID) {
		val id = ticket(estimate, listOf(assignee), state = "done").ticket.id
		// Written here rather than left to the insert's `now()`: cycles in August 2026 would
		// otherwise measure nothing until the clock caught up with them.
		Tickets.update({ Tickets.id eq id }) {
			it[completedAt] = on.atTime(10, 0).atOffset(ZoneOffset.UTC)
		}
		cycles.addTickets(admin, cycleId, listOf(id))
	}
}

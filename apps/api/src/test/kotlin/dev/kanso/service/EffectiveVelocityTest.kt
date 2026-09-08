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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The arbitration between a velocity somebody declared and one Kanso measured.
 *
 * The column on `user_preferences` is not what these tests are about. The rule is: which
 * of the two numbers is in force, when it changes hands, and whether the loser survives.
 * A feature that showed both without saying which one a date came from would be a tool
 * that lies, so every test here asserts the verdict — [VelocitySource] — and not only the
 * arithmetic, because a caller re-deriving the verdict is the bug this type exists to stop.
 *
 * Dates are real weekdays of August 2026, as in [VelocityTest] and for the same reason:
 * every cycle below is one working week, so the arithmetic reads without a calendar.
 * 3 August is a Monday.
 */
@Transactional
class EffectiveVelocityTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var effective: EffectiveVelocityService
	@Autowired lateinit var preferences: PreferencesService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "effective-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "effective-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Effective admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Arbitrating", "E${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** Five working days, Monday to Friday. */
	private fun cycle(number: Int, startsOn: LocalDate, state: CycleState = CycleState.CLOSED) =
		cycles.create(admin, team.id, number, startsOn, startsOn.plusDays(4), state)

	private fun delivered(cycleId: UUID, on: LocalDate, estimate: Int?, assignees: List<UUID>): UUID {
		val id = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "delivered $estimate",
			description = null,
			status = "done",
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
		cycles.addTickets(admin, cycleId, listOf(id))
		return id
	}

	private fun declare(person: User, rate: Double?) {
		preferences.save(
			person.id,
			if (rate == null) {
				PreferencesPatch(unset = setOf("declaredVelocity"))
			} else {
				PreferencesPatch(declaredVelocity = rate)
			},
		)
	}

	// --- the rule ------------------------------------------------------------------

	@Test
	fun `under two closed cycles the declared velocity is the one in force`() {
		val ana = person("Ana")
		declare(ana, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))

		val hers = effective.forPerson(ana, team.id)

		assertEquals(VelocitySource.DECLARED, hers.source)
		assertEquals(
			2.0,
			assertNotNull(hers.perWorkingDay),
			1e-9,
			"one cycle is an anecdote; replacing somebody's considered estimate of themselves" +
				" with a single observation is a downgrade dressed as a measurement",
		)
		assertEquals(
			1.0,
			assertNotNull(hers.measured),
			1e-9,
			"the measurement is computed and carried even while it is not in force — it is what" +
				" the screen shows beside the declared one",
		)
		assertEquals(1, hers.measuredCycles)
		assertEquals(1, hers.cyclesUntilMeasured, "the caption needs 'how much longer', not 'not yet'")
	}

	@Test
	fun `from the second closed cycle the measured velocity takes over`() {
		val ana = person("Ana")
		declare(ana, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		// Five points a week each: one a day, twice.
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val hers = effective.forPerson(ana, team.id)

		assertEquals(VelocitySource.MEASURED, hers.source)
		assertEquals(
			1.0,
			assertNotNull(hers.perWorkingDay),
			1e-9,
			"evidence that exists is better than an estimate of oneself, and waiting a third" +
				" cycle would leave the seed in force after it stopped being the best answer",
		)
		assertEquals(0, hers.cyclesUntilMeasured)
	}

	@Test
	fun `the declared velocity survives the measured one appearing, and is still reported`() {
		val ana = person("Ana")
		declare(ana, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val hers = effective.forPerson(ana, team.id)

		assertEquals(VelocitySource.MEASURED, hers.source)
		assertEquals(
			2.0,
			assertNotNull(hers.declared),
			1e-9,
			"a lasting gap between what somebody thought they delivered and what they delivered" +
				" is the most useful thing this feature produces; the loser has to survive the" +
				" arbitration for the screen to show it",
		)
		assertEquals(
			2.0,
			assertNotNull(preferences.get(ana.id).declaredVelocity),
			1e-9,
			"and nothing writes the measurement back over it — a column that agrees with the" +
				" measurement by construction says nothing",
		)
	}

	@Test
	fun `neither declared nor measurable is none, and none is not zero`() {
		val ana = person("Ana")
		val running = cycle(21, LocalDate.of(2026, 8, 3), state = CycleState.ACTIVE)
		delivered(running.id, LocalDate.of(2026, 8, 5), 13, listOf(ana.id))

		val hers = effective.forPerson(ana, team.id)

		assertEquals(VelocitySource.NONE, hers.source)
		assertNull(
			hers.perWorkingDay,
			"a zero divides into an infinite duration and reads as 'delivers nothing' about" +
				" somebody nothing is known about",
		)
		assertNull(hers.declared)
		assertNull(hers.measured)
	}

	@Test
	fun `with nothing declared a single thin cycle is still used, and says how thin it is`() {
		val ana = person("Ana")
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))

		val hers = effective.forPerson(ana, team.id)

		assertEquals(
			VelocitySource.MEASURED,
			hers.source,
			"the threshold decides which of two numbers wins; with one number there is no" +
				" arbitration, and refusing weak evidence would tell somebody with real closed" +
				" work that Kanso knows nothing about them",
		)
		assertEquals(1.0, assertNotNull(hers.perWorkingDay), 1e-9)
		assertEquals(1, hers.measuredCycles, "how much history the number stands on travels with it")
	}

	@Test
	fun `withdrawing the declaration falls back to whatever can be measured`() {
		val ana = person("Ana")
		declare(ana, 3.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		assertEquals(VelocitySource.DECLARED, effective.forPerson(ana, team.id).source)

		declare(ana, null)

		val hers = effective.forPerson(ana, team.id)
		assertEquals(VelocitySource.MEASURED, hers.source)
		assertNull(hers.declared, "unset clears it; an omitted key could never have said so")
	}

	@Test
	fun `the source is reported rather than left for the caller to work out`() {
		val ana = person("Ana")
		declare(ana, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))

		val below = effective.forPerson(ana, team.id)
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))
		val above = effective.forPerson(ana, team.id)

		// Both reads carry both numbers, so nothing about which one won can be inferred from
		// nullity. Only `source` distinguishes them, which is why it exists.
		assertNotNull(below.declared)
		assertNotNull(below.measured)
		assertNotNull(above.declared)
		assertNotNull(above.measured)
		assertEquals(listOf(VelocitySource.DECLARED, VelocitySource.MEASURED), listOf(below.source, above.source))
	}

	@Test
	fun `the declaration is per person and the measurement is per team`() {
		val ana = person("Ana")
		declare(ana, 2.0)
		val other = teams.create(admin, "No history", "N${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		assertEquals(VelocitySource.MEASURED, effective.forPerson(ana, team.id).source)
		assertEquals(
			VelocitySource.DECLARED,
			effective.forPerson(ana, other.id).source,
			"the same seed can be overruled in the team that has history and still stand in the" +
				" team that has none — the team that knows better is the one that knows",
		)
	}

	@Test
	fun `several people are arbitrated the same way in one read`() {
		val ana = person("Ana")
		val bo = person("Bo")
		declare(ana, 2.0)
		declare(bo, 4.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val both = effective.forPeople(listOf(ana, bo), team.id).associateBy { it.person.id }

		assertEquals(VelocitySource.MEASURED, both.getValue(ana.id).source)
		assertEquals(
			VelocitySource.DECLARED,
			both.getValue(bo.id).source,
			"Bo appears in no measured cycle, which is an absence and not a zero, so his seed" +
				" still stands even though the team has two closed cycles",
		)
		assertEquals(4.0, assertNotNull(both.getValue(bo.id).perWorkingDay), 1e-9)
	}

	@Test
	fun `somebody the closed cycles never saw is unmeasured, not measured at zero`() {
		val ana = person("Ana")
		val newcomer = person("Joined last week")
		declare(newcomer, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		val theirs = effective.forPerson(newcomer, team.id)

		assertEquals(
			VelocitySource.DECLARED,
			theirs.source,
			"two closed cycles exist, but neither of them measured this person; a zero here would" +
				" overrule their own estimate with a claim that they deliver nothing",
		)
		assertEquals(2.0, assertNotNull(theirs.perWorkingDay), 1e-9)
		assertNull(theirs.measured, "and there is no measurement to show beside it")
		assertEquals(2, theirs.cyclesUntilMeasured, "the wait is still two cycles that can measure them")
	}

	@Test
	fun `one person gets the same answer whichever way it is asked`() {
		val ana = person("Ana")
		val newcomer = person("Joined last week")
		declare(newcomer, 2.0)
		val one = cycle(21, LocalDate.of(2026, 8, 3))
		val two = cycle(22, LocalDate.of(2026, 8, 10))
		delivered(one.id, LocalDate.of(2026, 8, 5), 5, listOf(ana.id))
		delivered(two.id, LocalDate.of(2026, 8, 12), 5, listOf(ana.id))

		// `forPerson` builds a row for anybody, including a zero one; `forPeople` reads
		// `forTeam`, which leaves such a person out entirely. Two entry points to one rule
		// have to agree, or a settings screen and a ticket's duration describe different people.
		for (person in listOf(ana, newcomer)) {
			val one_ = effective.forPerson(person, team.id)
			val many = effective.forPeople(listOf(person), team.id).single()
			assertEquals(one_.source, many.source, "source disagrees for ${person.displayName}")
			assertEquals(one_.perWorkingDay, many.perWorkingDay, "rate disagrees for ${person.displayName}")
			assertEquals(one_.measured, many.measured, "measurement disagrees for ${person.displayName}")
		}
	}

	// --- what the column refuses ----------------------------------------------------

	@Test
	fun `zero is refused rather than stored as a withdrawal`() {
		val ana = person("Ana")

		assertFailsWith<BadRequestException>(
			"a rate of zero divides into an infinite duration; the way to withdraw a" +
				" declaration is to unset it",
		) { declare(ana, 0.0) }
		assertFailsWith<BadRequestException> { declare(ana, -1.0) }
	}

	@Test
	fun `a dropped decimal point is refused before it becomes a duration`() {
		val ana = person("Ana")

		// 250 typed for 2.50 would otherwise turn every estimate on the screen into "under a
		// day", silently and plausibly.
		assertFailsWith<BadRequestException> { declare(ana, 250.0) }
		declare(ana, 0.25)
		assertEquals(0.25, assertNotNull(preferences.get(ana.id).declaredVelocity), 1e-9)
	}
}

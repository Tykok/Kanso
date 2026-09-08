package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.db.Tickets
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertTrue

/**
 * Screen 23: open tickets per person, cut by status, counted — and, since tickets carry
 * an estimate, weighed.
 *
 * This file used to assert the opposite of its last test: the drawing said "aucune
 * estimation en points : la charge se lit au nombre et à l'ancienneté", and the shape was
 * held to a count and an age by a whitebox assertion. Tickets have points now, and the
 * prohibition became the lie — three 13s and three 1s were reading as the same load. What
 * survives of the old rule is that the count did not go away and the sum never pretends
 * to cover what nobody sized.
 */
@Transactional
class WorkloadTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var workload: WorkloadService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String) = users.createLocalUser(
		email = "load-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "load-admin-${UUID.randomUUID()}@kanso.test",
			displayName = "Load admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Loaded", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(
		title: String,
		status: DefaultStatus = DefaultStatus.TODO,
		priority: TicketPriority = TicketPriority.NONE,
		assignees: List<UUID> = emptyList(),
	) = tickets.create(
		actor = admin,
		teamId = team.id,
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

	/**
	 * Backdates a ticket, since `created_at` is `now()` on insert. Written here with the
	 * DSL rather than as a repository method: ageing a row is a thing only a test wants,
	 * and a production `backdate` would be an invitation.
	 */
	private fun age(ticketId: UUID, days: Long) {
		Tickets.update({ Tickets.id eq ticketId }) {
			it[createdAt] = OffsetDateTime.now().minusDays(days)
		}
	}

	@Test
	fun `one row per person carrying work, counted and cut by status`() {
		val rey = person("M. Rey")
		ticket("a", DefaultStatus.IN_PROGRESS, assignees = listOf(rey.id))
		ticket("b", DefaultStatus.IN_REVIEW, assignees = listOf(rey.id))
		ticket("c", DefaultStatus.TODO, assignees = listOf(rey.id))

		val row = workload.forTeam(team.id).rows.single { it.person?.id == rey.id }

		assertEquals(3, row.total)
		// The team's own keys, because this scope is one team — `KAN-90`. A scope holding
		// descendants would key these by category instead, and `StatusGrouping` is the one
		// place that decides which.
		assertEquals(1, row.byStatus["in_progress"])
		assertEquals(1, row.byStatus["in_review"])
		assertEquals(1, row.byStatus["todo"])
		// Two of those three are STARTED, and the column that prints it is resolved here
		// rather than summed by a reader who would need the catalogue to do it.
		assertEquals(2, row.started)
		assertEquals(row.total, row.byStatus.values.sum(), "the bar has to add up to the number beside it")
	}

	@Test
	fun `only open tickets count — the header says "ouverts seulement"`() {
		val rey = person("M. Rey")
		ticket("open", DefaultStatus.IN_PROGRESS, assignees = listOf(rey.id))
		ticket("finished", DefaultStatus.DONE, assignees = listOf(rey.id))
		ticket("dropped", DefaultStatus.CANCELED, assignees = listOf(rey.id))

		assertEquals(1, workload.forTeam(team.id).rows.single { it.person?.id == rey.id }.total)
	}

	@Test
	fun `the unassigned column is a row like any other, and is not a person`() {
		ticket("nobody on it")
		ticket("nobody on this either")

		val unassigned = workload.forTeam(team.id).rows.single { it.person == null }

		assertEquals(2, unassigned.total)
		assertNull(unassigned.person, "'Sans assigné' is a bucket, not an account with no name")
	}

	@Test
	fun `the heaviest carrier comes first, because that is what the screen is for`() {
		val heavy = person("Heavy")
		val light = person("Light")
		repeat(3) { ticket("h$it", assignees = listOf(heavy.id)) }
		ticket("l", assignees = listOf(light.id))

		val people = workload.forTeam(team.id).rows.filter { it.person != null }

		assertEquals(listOf(heavy.id, light.id), people.map { it.person!!.id })
	}

	@Test
	fun `the unassigned row sorts last however much is in it`() {
		val someone = person("Someone")
		ticket("theirs", assignees = listOf(someone.id))
		repeat(5) { ticket("nobody $it") }

		val rows = workload.forTeam(team.id).rows

		assertNull(rows.last().person, "a person's load is the subject; the orphan pile is the footnote")
	}

	@Test
	fun `an urgent ticket open more than three days is counted, and three days is not more than three`() {
		val rey = person("M. Rey")
		val old = ticket("old and urgent", DefaultStatus.IN_PROGRESS, TicketPriority.URGENT, listOf(rey.id))
		val exactly = ticket("three days old", DefaultStatus.IN_PROGRESS, TicketPriority.URGENT, listOf(rey.id))
		val calm = ticket("old but not urgent", DefaultStatus.IN_PROGRESS, TicketPriority.LOW, listOf(rey.id))
		age(old, 5)
		age(exactly, 3)
		age(calm, 9)

		val row = workload.forTeam(team.id).rows.single { it.person?.id == rey.id }

		assertEquals(
			1,
			row.urgentOverThreeDays,
			"the sentence under the chart says 'more than three days'; a boundary read as 'at least'" +
				" would put a ticket opened this morning of the third day into a warning",
		)
	}

	@Test
	fun `the shape weighs the load and still counts it, and says what it could not weigh`() {
		val rey = person("M. Rey")
		ticket("a", assignees = listOf(rey.id))

		val row = workload.forTeam(team.id).rows.single { it.person?.id == rey.id }

		// Still a whitebox assertion, and still holding a rule rather than a behaviour — only
		// the rule changed. `points` may sit beside `total`; it may not replace it, and
		// `unestimated` has to travel with it, because a bar drawn in points has to be able
		// to say what it is not showing.
		assertEquals(
			listOf(
				"person", "total", "points", "unestimated", "byStatus", "started",
				"urgentOverThreeDays", "oldestOpenDays",
			),
			WorkloadRow::class.primaryConstructor!!.parameters.map { it.name },
			"a points column that arrives without the count, or without the tally of what it" +
				" left out, throws away the screen's own argument",
		)
		assertEquals(0, row.points, "an unsized ticket weighs nothing; it is not a zero-point ticket")
		assertEquals(1, row.unestimated)
		assertTrue(row.oldestOpenDays >= 0)
	}
}

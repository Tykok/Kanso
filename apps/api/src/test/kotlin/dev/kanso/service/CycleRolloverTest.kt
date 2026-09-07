package dev.kanso.service

import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.PostgresTest
import dev.kanso.repo.CycleRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closing a cycle is a report on the work that did not happen.
 *
 * A cycle that closes over its unfinished tickets buries them: the next cycle starts
 * empty and looks healthy, and the four tickets that slipped are only findable by
 * opening a cycle nobody has a reason to open again. So closing moves them, and says
 * so in each ticket's feed.
 *
 * The tests assert the *destination membership*, not a flag on the closed cycle. There
 * is no "carried over" column anywhere and there will not be one — `ticket_cycles` is
 * keyed on the ticket, so where a ticket is is the only fact, and a second copy of it
 * is a second thing that can be wrong.
 */
@Transactional
class CycleRolloverTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var cycleRows: CycleRepository
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "rollover-${UUID.randomUUID()}@kanso.test",
			displayName = "Rollover admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Rolling", "R${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** Fourteen days, the cadence every date assertion below is measured against. */
	private val start: LocalDate = LocalDate.of(2026, 8, 4)
	private val end: LocalDate = LocalDate.of(2026, 8, 17)

	private fun cycle(number: Int, state: CycleState = CycleState.ACTIVE) =
		cycles.create(admin, team.id, number, start, end, state)

	private fun ticket(title: String, status: DefaultStatus) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	private fun idsIn(cycleId: UUID) = cycleRows.ticketsIn(cycleId).map { it.id }.toSet()

	private fun close(id: UUID) = cycles.setState(admin, id, CycleState.CLOSED)

	@Test
	fun `unfinished work leaves the cycle that is closing and lands in the next one`() {
		val closing = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		val open = listOf(
			ticket("still to do", DefaultStatus.TODO),
			ticket("half written", DefaultStatus.IN_PROGRESS),
			ticket("waiting on review", DefaultStatus.IN_REVIEW),
			ticket("never started", DefaultStatus.BACKLOG),
		)
		cycles.addTickets(admin, closing.id, open)

		close(closing.id)

		assertEquals(emptySet(), idsIn(closing.id), "a closed cycle is a finished one; nothing open stays in it")
		assertEquals(open.toSet(), idsIn(next.id), "every unfinished status carries, backlog included")
	}

	@Test
	fun `work that is finished or abandoned stays where it happened`() {
		val closing = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		val done = ticket("shipped", DefaultStatus.DONE)
		val canceled = ticket("dropped", DefaultStatus.CANCELED)
		val open = ticket("slipped", DefaultStatus.TODO)
		cycles.addTickets(admin, closing.id, listOf(done, canceled, open))

		close(closing.id)

		assertEquals(
			setOf(done, canceled),
			idsIn(closing.id),
			"the closed cycle keeps its record — moving what was done would erase the cycle's own history",
		)
		assertEquals(
			setOf(open),
			idsIn(next.id),
			"a cancelled ticket is a decision not to do the work, so carrying it forward would re-open it",
		)
	}

	@Test
	fun `the next cycle is created when the team planned none, one cadence long`() {
		val closing = cycle(24)
		val open = ticket("slipped", DefaultStatus.TODO)
		cycles.addTickets(admin, closing.id, listOf(open))

		close(closing.id)

		val created = cycles.list(team.id).map { it.cycle }.singleOrNull { it.number == 25 }
		assertNotNull(created, "work with nowhere to go is work that was lost; the destination is created")
		assertEquals(CycleState.UPCOMING, created.state)
		assertEquals(end.plusDays(1), created.startsOn, "no gap: a day belonging to no cycle is a day of missing work")
		assertEquals(end.plusDays(14), created.endsOn, "the team's cadence is the length of the cycle it just finished")
		assertEquals(setOf(open), idsIn(created.id))
	}

	@Test
	fun `an upcoming cycle the team already planned is used rather than a second one invented`() {
		val closing = cycle(24)
		val planned = cycle(25, CycleState.UPCOMING)
		cycles.addTickets(admin, closing.id, listOf(ticket("slipped", DefaultStatus.TODO)))

		close(closing.id)

		assertEquals(
			listOf(24, 25),
			cycles.list(team.id).map { it.cycle.number }.sorted(),
			"a cycle nobody planned appearing beside the one they did is how a sidebar stops being trusted",
		)
		assertEquals(1, cycles.list(team.id).single { it.cycle.id == planned.id }.ticketCount)
	}

	@Test
	fun `a cycle that finished its work closes without inventing somewhere to put nothing`() {
		val closing = cycle(24)
		cycles.addTickets(
			admin,
			closing.id,
			listOf(ticket("shipped", DefaultStatus.DONE), ticket("dropped", DefaultStatus.CANCELED)),
		)

		close(closing.id)

		assertEquals(
			listOf(24),
			cycles.list(team.id).map { it.cycle.number },
			"an empty cycle 25 nobody asked for is a plan the team did not make",
		)
	}

	@Test
	fun `each carried ticket says so in its own feed, naming both cycles`() {
		val closing = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		val open = ticket("slipped", DefaultStatus.TODO)
		val done = ticket("shipped", DefaultStatus.DONE)
		cycles.addTickets(admin, closing.id, listOf(open, done))

		close(closing.id)

		val row = activity.forEntity(ActivityEntity.TICKET, open)
			.single { it.kind == ActivityKind.CARRIED_OVER }
		assertEquals(admin.id, row.actor?.id, "somebody closed the cycle; the move is theirs")
		assertEquals(24, (row.payload["from"] as Number).toInt())
		assertEquals(25, (row.payload["to"] as Number).toInt())
		assertEquals(next.id.toString(), row.payload["cycleId"], "the feed links where the work went")
		assertTrue(
			activity.forEntity(ActivityEntity.TICKET, done).none { it.kind == ActivityKind.CARRIED_OVER },
			"a ticket that did not move has nothing to say about moving",
		)
	}

	@Test
	fun `closing a cycle that is already closed moves nothing a second time`() {
		val closing = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		val open = ticket("slipped", DefaultStatus.TODO)
		cycles.addTickets(admin, closing.id, listOf(open))
		close(closing.id)

		// The button is pressed twice, or two tabs press it once each. The ticket is in
		// cycle 25 by then, so a second pass must not walk it on to a cycle 26.
		close(closing.id)

		assertEquals(setOf(open), idsIn(next.id))
		assertEquals(listOf(24, 25), cycles.list(team.id).map { it.cycle.number }.sorted())
		assertEquals(
			1,
			activity.forEntity(ActivityEntity.TICKET, open).count { it.kind == ActivityKind.CARRIED_OVER },
			"one move, one line in the feed — a second is a move that did not happen",
		)
	}

	@Test
	fun `a cycle closed twice over work put back into it carries that work once`() {
		val closing = cycle(24)
		val next = cycle(25, CycleState.UPCOMING)
		cycles.addTickets(admin, closing.id, listOf(ticket("slipped", DefaultStatus.TODO)))
		close(closing.id)
		val late = ticket("filed against the old cycle", DefaultStatus.TODO)
		cycles.addTickets(admin, closing.id, listOf(late))

		close(closing.id)

		assertEquals(
			setOf(late),
			idsIn(closing.id),
			"re-closing is not the transition; a ticket deliberately filed into a closed cycle stays put",
		)
		assertEquals(1, idsIn(next.id).size)
	}
}

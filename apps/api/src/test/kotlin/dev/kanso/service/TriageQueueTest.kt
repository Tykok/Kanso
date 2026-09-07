package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Screen 20: one incoming ticket at a time, four keys, each advancing to the next.
 *
 * The promise the drawing makes in words is the one worth testing: "nothing is lost —
 * the queue keeps a record of what was closed".
 */
@Transactional
class TriageQueueTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService
	@Autowired lateinit var triage: TriageService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "triage-${UUID.randomUUID()}@kanso.test",
			displayName = "Triage admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Triaging", "T${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	private fun activeCycle() = cycles.create(
		actor = admin,
		teamId = team.id,
		number = 24,
		startsOn = LocalDate.now().minusDays(2),
		endsOn = LocalDate.now().plusDays(12),
		state = CycleState.ACTIVE,
	)

	@Test
	fun `the queue is what has not been decided, oldest first`() {
		val first = ticket("reported first")
		val second = ticket("reported second")

		val queue = triage.queue(team.id)

		assertEquals(
			listOf(first, second),
			queue.items.map { it.ticket.id },
			"a triage queue that shows the newest first buries the thing that has waited longest",
		)
		assertEquals(2, queue.total)
	}

	@Test
	fun `each of the four decisions takes the ticket out of the queue`() {
		activeCycle()
		val accepted = ticket("accept me")
		val backlogged = ticket("later")
		val duplicate = ticket("said twice")
		val closed = ticket("no thanks")
		val original = ticket("said first")

		triage.decide(admin, accepted, TriageDecision.ACCEPTED, null)
		triage.decide(admin, backlogged, TriageDecision.BACKLOGGED, null)
		triage.decide(admin, duplicate, TriageDecision.DUPLICATE, original)
		triage.decide(admin, closed, TriageDecision.CLOSED, null)

		assertEquals(
			listOf(original),
			triage.queue(team.id).items.map { it.ticket.id },
			"four decisions, four tickets gone; the one nobody ruled on stays",
		)
	}

	@Test
	fun `nothing is lost — a closed ticket is still findable as a decision`() {
		val dropped = ticket("closed without action")

		triage.decide(admin, dropped, TriageDecision.CLOSED, null)

		val trace = triage.decisions(team.id)
		assertEquals(1, trace.size)
		assertEquals(TriageDecision.CLOSED, trace.single().decision)
		assertEquals(admin.id, trace.single().decidedBy?.id, "who ruled is half of what a trace is for")
	}

	@Test
	fun `accepting puts the ticket in the cycle that is in progress`() {
		val cycle = activeCycle()
		val incoming = ticket("accept into the cycle")

		triage.decide(admin, incoming, TriageDecision.ACCEPTED, null)

		assertEquals(
			listOf(incoming),
			cycles.report(cycle.id).tickets.map { it.ticket.id },
			"'accept into the cycle' is the button's own wording; accepting into nothing is not a decision",
		)
	}

	@Test
	fun `accepting is refused when the team has no cycle in progress to accept into`() {
		val incoming = ticket("nowhere to go")

		assertFailsWith<ConflictException> {
			triage.decide(admin, incoming, TriageDecision.ACCEPTED, null)
		}
	}

	@Test
	fun `sending to the backlog changes the status, not just the queue`() {
		val incoming = ticket("later, really")

		triage.decide(admin, incoming, TriageDecision.BACKLOGGED, null)

		assertEquals(DefaultStatus.BACKLOG, tickets.get(incoming).ticket.status)
	}

	@Test
	fun `a duplicate has to name what it duplicates, and cannot name itself`() {
		val incoming = ticket("said twice")

		assertFailsWith<BadRequestException> {
			triage.decide(admin, incoming, TriageDecision.DUPLICATE, null)
		}
		assertFailsWith<BadRequestException> {
			triage.decide(admin, incoming, TriageDecision.DUPLICATE, incoming)
		}
	}

	@Test
	fun `deciding twice on one ticket is refused rather than rewriting the record`() {
		val incoming = ticket("ruled once")
		triage.decide(admin, incoming, TriageDecision.CLOSED, null)

		val error = assertFailsWith<ConflictException> {
			triage.decide(admin, incoming, TriageDecision.BACKLOGGED, null)
		}

		assertTrue(error.message!!.contains("already"), "a trace that can be overwritten is not a trace")
	}

	@Test
	fun `similarity is measured on the title and reported as a percentage`() {
		val original = ticket("Echo suppression drops our own writes")
		val incoming = ticket("Echo suppression drops our writes")
		ticket("Compact density keeps 44px on touch")

		val similar = triage.similar(incoming, limit = 5)

		assertEquals(
			original,
			similar.first().detail.ticket.id,
			"the near-identical title has to come first or the panel is noise",
		)
		assertTrue(similar.first().similarity in 1..100, "the drawing prints '68 %', so a whole percent is the unit")
		assertTrue(
			similar.none { it.detail.ticket.id == incoming },
			"a ticket is not a duplicate of itself, however similar the title",
		)
	}

	@Test
	fun `a title that resembles nothing gets an empty list rather than a weak guess`() {
		val incoming = ticket("Seal is unreadable at 100% zoom on Windows")
		ticket("Compact density keeps 44px on touch")

		assertTrue(
			triage.similar(incoming, limit = 5).isEmpty(),
			"offering a 12% match as a possible duplicate makes the whole panel unreadable",
		)
	}
}

package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sentence a refused arrow produces, read as a person reads it.
 *
 * `DependencyTest` already holds that the chain is *found* and comes back in order. What
 * this holds is the only part of it anybody sees: the words. The two are apart on purpose
 * — the walk answers in ids and is right to, and the day it stops answering in ids is a
 * day `DependencyTest` should fail and this file should not.
 *
 * One 409 feeds two neighbours — the toast on the chart and the string
 * `kanso_link_tickets` hands an agent — because both print
 * [ScheduleService.linkRefusal]'s own words and neither rewrites them. So this asserts
 * the sentence whole rather than a fragment of it: a substring check would pass on the
 * UUID version of the very same sentence.
 */
@Transactional
class LoopRefusalTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "loop-${UUID.randomUUID()}@kanso.test",
			displayName = "Loop admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Loop", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String, teamId: UUID? = team.id): TicketDetail = tickets.create(
		actor = admin,
		teamId = teamId,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	/** A UUID printed anywhere in a sentence a person is expected to act on. */
	private val anyUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

	@Test
	fun `the loop refusal names the chain by identifier`() {
		val a = ticket("A")
		val b = ticket("B")
		val c = ticket("C")
		deps.insert(a.ticket.id, b.ticket.id)
		deps.insert(b.ticket.id, c.ticket.id)

		// C -> A is the arrow that closes A -> B -> C, so the chain runs A, B, C.
		val refusal = schedule.linkRefusal(c.ticket.id, a.ticket.id)

		assertEquals(
			"That dependency would close a loop: ${a.identifier} -> ${b.identifier} -> ${c.identifier}",
			refusal,
			"the chain is what makes the refusal actionable, and only if it is readable",
		)
	}

	@Test
	fun `no ticket id reaches the sentence`() {
		val a = ticket("A")
		val b = ticket("B")
		deps.insert(a.ticket.id, b.ticket.id)

		val refusal = assertNotNull(schedule.linkRefusal(b.ticket.id, a.ticket.id))

		assertTrue(
			anyUuid.containsMatchIn(refusal).not(),
			"a UUID on screen is the whole defect, not a detail of it: $refusal",
		)
	}

	@Test
	fun `a draft in the chain is named by the address it has`() {
		val draft = ticket("Typed in a meeting", teamId = null)
		val filed = ticket("Filed")
		deps.insert(draft.ticket.id, filed.ticket.id)

		val refusal = schedule.linkRefusal(filed.ticket.id, draft.ticket.id)

		// Its id, because it has no other name yet — not `?-null`, and not a placeholder
		// that would stop being true the moment somebody attaches a team.
		assertEquals(
			"That dependency would close a loop: ${draft.ticket.id} -> ${filed.identifier}",
			refusal,
		)
	}
}

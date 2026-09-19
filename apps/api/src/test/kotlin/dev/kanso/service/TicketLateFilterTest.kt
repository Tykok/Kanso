package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `late` asked of the list, and answering the same as it does on the timeline.
 *
 * That agreement is the point of the file rather than a nicety. The ruling behind this key
 * is **one definition for the badge, the filter and the bar** — so a saved view holding
 * `late` and a timeline drawn beside it must never disagree about which tickets are in it.
 * `TimelineLateTest` is the other half, and the two assert the same four corners on purpose.
 */
@Transactional
class TicketLateFilterTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var teamRepo: TeamRepository

	private val admin: User by lazy {
		users.createLocalUser(
			email = "latefilter-${UUID.randomUUID()}@kanso.test",
			displayName = "Late filter",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Late", "F${UUID.randomUUID().toString().take(4).uppercase()}", null)
			.also { teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER) }
	}

	private fun dayFromToday(offset: Long) = KansoInstant(
		LocalDate.now(ZoneOffset.UTC).plusDays(offset).atStartOfDay().atOffset(ZoneOffset.UTC),
		false,
	)

	private fun ticketDue(due: KansoInstant?, status: String = "todo"): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = "Work ${UUID.randomUUID()}",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = due,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	private fun lateIds(): List<UUID> = tickets.list(
		teamId = team.id,
		includeDescendants = false,
		includeArchived = false,
		filters = TicketFilters(late = true),
		sortBy = ViewSortBy.CREATED,
		limit = 200,
		offset = 0,
	).map { it.ticket.id }

	@Test
	fun `an overdue open ticket matches`() {
		val overdue = ticketDue(dayFromToday(-1))
		assertTrue(lateIds().contains(overdue))
	}

	@Test
	fun `finishing it takes it out of the filter`() {
		val done = ticketDue(dayFromToday(-10), status = "done")
		assertFalse(lateIds().contains(done))
	}

	@Test
	fun `cancelling it takes it out of the filter`() {
		val canceled = ticketDue(dayFromToday(-10), status = "canceled")
		assertFalse(lateIds().contains(canceled))
	}

	@Test
	fun `a future due date does not match`() {
		assertFalse(lateIds().contains(ticketDue(dayFromToday(7))))
	}

	@Test
	fun `a ticket with no due date does not match`() {
		assertFalse(lateIds().contains(ticketDue(null)))
	}

	/**
	 * The boundary, asserted on this side too. `TimelineLateTest` makes the same claim about
	 * the bar; if one of them ever used `lessEq` the two screens would disagree by a day and
	 * the ruling this key exists to enforce would be quietly broken.
	 */
	@Test
	fun `a ticket due today does not match yet`() {
		assertFalse(lateIds().contains(ticketDue(dayFromToday(0))))
	}
}

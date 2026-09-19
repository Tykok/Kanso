package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One list, paged and sorted — the tray's replacement.
 *
 * The screen used to split its rows by whether somebody had given them dates, which is not
 * a property of the work. Every ticket the scope resolves is now a row; a row without dates
 * simply has no bar.
 *
 * The fourth test is the one worth keeping. `LIMIT`/`OFFSET` over a non-unique ordering
 * lets two equal rows swap between one page and the next, so one is served twice and
 * another never — and it reads as a caching bug for a week before anybody suspects the sort.
 */
@Transactional
class TimelineColumnTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var timeline: TimelineService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var teamRepo: TeamRepository

	private val admin: User by lazy {
		users.createLocalUser(
			email = "col-${UUID.randomUUID()}@kanso.test",
			displayName = "Column",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Column", "C${UUID.randomUUID().toString().take(4).uppercase()}", null)
			.also { teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER) }
	}

	private fun day(offset: Long) = KansoInstant(
		LocalDate.now(ZoneOffset.UTC).plusDays(offset).atStartOfDay().atOffset(ZoneOffset.UTC),
		false,
	)

	private fun make(title: String, start: KansoInstant?, status: String = "todo"): UUID =
		tickets.create(
			actor = admin, teamId = team.id, title = title, description = null,
			status = status, priority = TicketPriority.NONE,
			start = start, due = null, projectId = null,
			assigneeIds = emptyList(), docIds = emptyList(),
		).ticket.id

	private fun load(page: Int = 0, pageSize: Int = 50, hideCompleted: Boolean = false) =
		timeline.load(
			admin, teamId = team.id, projectId = null,
			sort = TimelineSort.START, page = page, pageSize = pageSize,
			hideCompleted = hideCompleted,
		)

	@Test
	fun `a ticket with no dates is a row, not a separate list`() {
		val undated = make("Nobody has planned this", start = null)

		val row = load().tickets.single { it.id == undated }

		assertEquals(null, row.start)
		assertEquals(null, row.due)
	}

	@Test
	fun `a dated and an undated ticket are in the same list`() {
		val dated = make("Planned", start = day(1))
		val undated = make("Unplanned", start = null)

		val ids = load().tickets.map { it.id }

		assertTrue(ids.contains(dated))
		assertTrue(ids.contains(undated))
	}

	@Test
	fun `hiding completed work takes it out of the column`() {
		val open = make("Still going", start = day(1))
		val done = make("Finished", start = day(1), status = "done")

		val ids = load(hideCompleted = true).tickets.map { it.id }

		assertTrue(ids.contains(open))
		assertFalse(ids.contains(done))
	}

	/**
	 * Six tickets sharing one start date, so the sort key is identical for every one of them
	 * and only the tiebreaker decides. Without it this test fails intermittently, which is
	 * exactly how the bug would reach production.
	 */
	@Test
	fun `the sort is stable across pages`() {
		val all = (1..6).map { make("Same day $it", start = day(3)) }.toSet()

		val first = load(page = 0, pageSize = 3).tickets.map { it.id }
		val second = load(page = 1, pageSize = 3).tickets.map { it.id }

		assertEquals(3, first.size)
		assertEquals(emptySet(), first.toSet() intersect second.toSet(), "no row is served twice")
		assertTrue(all.all { it in first.toSet() + second.toSet() }, "and none is skipped")
	}

	@Test
	fun `hasMore says whether another page exists`() {
		(1..4).forEach { make("Row $it", start = day(it.toLong())) }

		assertTrue(load(page = 0, pageSize = 2).hasMore)
		assertFalse(load(page = 0, pageSize = 50).hasMore)
	}
}

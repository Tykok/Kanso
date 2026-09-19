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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `late` is a fact about one ticket; `slipping` is a forecast about a graph.
 *
 * This file exists because the two were one field under the wrong name. `TimelineService`
 * computed `late` from negative slack and `bar-style.ts` announced that state as `overdue`,
 * so a screen reader said a ticket was late when its due date was three weeks out.
 *
 * The four corners below are the point. A boolean asserted only in its true case proves
 * nothing — the implementation that returns `true` unconditionally passes it — so every
 * reason not to be late gets its own test. `TimelineServiceTest` owns the forecast.
 */
@Transactional
class TimelineLateTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var timeline: TimelineService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var teamRepo: TeamRepository

	private val admin: User by lazy {
		users.createLocalUser(
			email = "late-${UUID.randomUUID()}@kanso.test",
			displayName = "Late admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Late", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
			.also { teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER) }
	}

	/**
	 * A day relative to today, as a floating instant — the shape a due date actually has.
	 *
	 * Relative and not a literal like `TimelineServiceTest`'s `day(3)`: this file is about a
	 * comparison against *now*, so a fixed 2026 date would start passing for the wrong reason
	 * the moment the calendar went past it, and then never fail again.
	 */
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
		// Every ticket here carries a start, so every one of them is on the chart rather than
		// in `unscheduled`. That split is what the next task removes; this file is about the
		// due date alone and must not fail for a reason it is not testing.
		//
		// Far enough back to sit before every due date used below. A start *after* its own due
		// gives the ticket negative slack, which is the forecast — and a test for the fact that
		// accidentally triggers the forecast proves nothing about either.
		start = dayFromToday(-60),
		due = due,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	private fun lateness(id: UUID): Boolean =
		timeline.load(admin, teamId = team.id, projectId = null)
			.tickets.single { it.id == id }.late

	@Test
	fun `a due date in the past on open work is late`() {
		assertTrue(lateness(ticketDue(dayFromToday(-1))))
	}

	@Test
	fun `a completed ticket is never late, however old its due date`() {
		assertFalse(lateness(ticketDue(dayFromToday(-30), status = "done")))
	}

	/**
	 * Cancelling is a decision, and a decision is not a debt. The same reading `PrTransition`
	 * makes when it refuses to move a cancelled ticket to Done.
	 */
	@Test
	fun `a canceled ticket is never late`() {
		assertFalse(lateness(ticketDue(dayFromToday(-30), status = "canceled")))
	}

	@Test
	fun `a due date in the future is not late`() {
		assertFalse(lateness(ticketDue(dayFromToday(7))))
	}

	/**
	 * Not late, and not a null pretending to be a date. "Not late" and "has no deadline" are
	 * different states, and the screen draws nothing at all for the second.
	 */
	@Test
	fun `a ticket with no due date is never late`() {
		assertFalse(lateness(ticketDue(null)))
	}

	/**
	 * The boundary somebody will eventually get wrong. A ticket due *today* is not late: the
	 * day is not over. A badge that lit at midnight on the due date itself would accuse
	 * somebody of being late on the morning of the day they were given.
	 */
	@Test
	fun `a ticket due today is not late yet`() {
		assertFalse(lateness(ticketDue(dayFromToday(0))))
	}
}

package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.StatusOrder
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.service.TeamService
import dev.kanso.service.TicketGroups
import dev.kanso.service.TicketService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order the database actually stacks status buckets in, asked of the database.
 *
 * `TicketGroupingTest` next door proves the buckets are stacked and counted correctly;
 * what it never does is put a ticket in all six statuses at once, so it could not tell a
 * `CASE` that ranked five of them right from one that ranked all six. This does, and it
 * asserts the answer twice over: against [StatusOrder.WORKFLOW], which is what the SQL is
 * now rendered from, and against the sequence written out by hand — the same one the web
 * app's `lib/status-order.test.ts` pins for its `WORKFLOW_ORDER`.
 *
 * That second assertion is the point of the file. The two sides of the wire hold separate
 * copies of this order on purpose, and this is the test that makes the copies answerable
 * to each other: the day one is edited alone, the side that moved goes red here or there
 * rather than shipping a grouped page whose headers no longer match its rows.
 */
@Transactional
class StatusBucketOrderTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var groups: TicketGroups
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "bucket-order-${UUID.randomUUID()}@kanso.test",
			displayName = "Stacker",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Buckets", "B${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	@Test
	fun `stacks every status the vocabulary has, in the workflow order`() {
		// Created in reverse, so a query that returned them in insertion order — or in the
		// column's alphabetical order, which starts `backlog, canceled` — fails.
		for (status in TicketStatus.entries.reversed()) {
			tickets.create(
				actor = admin,
				teamId = team.id,
				title = "A ticket that is ${status.wire}",
				description = null,
				status = status,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}

		val keys = groups
			.of(TicketScope(listOf(team.id)), TicketFilters(), ViewGroupBy.STATUS, ViewSortBy.UPDATED, 200)
			.map { it.key }

		assertEquals(StatusOrder.WORKFLOW.map { it.wire }, keys)
		assertEquals(
			listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled"),
			keys,
		)
	}
}

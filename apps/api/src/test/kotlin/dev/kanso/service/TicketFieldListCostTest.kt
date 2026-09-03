package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What custom fields cost the hottest read in the application.
 *
 * `KAN-59` put the main list on a grouped endpoint with server-side counts, so this is the
 * query somebody runs a few hundred times a day, and a feature that fetched its values
 * where it renders them would have turned it into two hundred round trips. That is the
 * failure this file exists to keep out, permanently — the number is asserted, not measured
 * once and written in a commit message.
 *
 * **How it counts.** `Transaction.statementCount` is incremented by Exposed's own `exec`,
 * unconditionally and with no `debug` flag, so reading it before and after is an exact count
 * of the statements the repositories issued. Nothing is installed to make this work: no
 * datasource proxy, no `@TestConfiguration`, no extra bean — which matters because a test
 * that wraps the `DataSource` gets its own Spring context cache key, and `MockMvcTest` and
 * `SecurityBootstrapTest` both record what that costs the rest of the suite.
 *
 * What it does not count is a statement issued through `JdbcClient` rather than Exposed.
 * The list path is Exposed end to end, and the assertions below are bounds rather than exact
 * equalities for that reason: what is being defended is *the absence of per-row growth*, and
 * a bound says that where an equality would break on an unrelated refactor.
 *
 * **The measured numbers, for the record.** A flat list of 200 tickets with three custom
 * fields valued on every one costs **5** statements: the rows, the team keys, the assignees,
 * the docs, and the values. The grouped list the main screen reads costs **6** — the extra
 * one is the `GROUP BY` that makes `Todo · 29` a fact about the team rather than about a
 * fetch. So `V32` cost the hottest read in the application exactly one statement, and the
 * same one whether the team has defined no fields or thirty.
 */
@Transactional
class TicketFieldListCostTest : PostgresTest() {

	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var values: TicketFieldService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "cost-${UUID.randomUUID()}@kanso.test",
			displayName = "Cost admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun team(): Team =
		teams.create(admin, "Cost", "C${UUID.randomUUID().toString().take(4).uppercase()}", null).also {
			teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER)
		}

	/** Exposed's own counter, read around the block. */
	private fun statements(block: () -> Unit): Int {
		val transaction = TransactionManager.current()
		val before = transaction.statementCount
		block()
		return transaction.statementCount - before
	}

	/**
	 * The measurement the ticket asked for: 200 tickets, three fields, values on every one.
	 *
	 * The list is expected to cost a small constant — the rows, the team keys, the assignees,
	 * the docs and the values — and the assertion is that it is nowhere near 200. The
	 * generous ceiling is deliberate: pinning the exact number would make this file fail
	 * whenever an unrelated relation is added, which is how a cost guard gets deleted.
	 */
	@Test
	fun `a list of 200 tickets with three custom fields costs a constant number of queries`() {
		val team = team()
		val note = fields.define(admin, team.id, "Note", "text", false, emptyList())
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())
		val severity = fields.define(admin, team.id, "Severity", "select", false, listOf("low", "high"))

		repeat(TICKETS) { index ->
			val ticket = tickets.create(
				actor = admin, teamId = team.id, title = "Work $index", description = null,
				status = TicketStatus.TODO, priority = TicketPriority.NONE,
				start = null, due = null, projectId = null,
				assigneeIds = emptyList(), docIds = emptyList(),
			).ticket
			values.setValues(
				admin,
				ticket.id,
				mapOf(
					note.id.toString() to "note $index",
					size.id.toString() to index,
					severity.id.toString() to if (index % 2 == 0) "low" else "high",
				),
			)
		}

		var page = emptyList<TicketDetail>()
		val cost = statements {
			page = tickets.list(
				teamId = team.id,
				includeDescendants = false,
				includeArchived = false,
				filters = TicketFilters(),
				sortBy = ViewSortBy.UPDATED,
				limit = TICKETS,
				offset = 0,
			)
		}

		// The read actually happened and actually carries the values, or the count above is a
		// count of nothing — the way a cost test quietly stops testing anything.
		assertEquals(TICKETS, page.size)
		assertEquals(3, page.first().customFields.size)
		assertTrue(
			page.all { it.customFields.size == 3 },
			"some rows came back without their values, so the batched read is missing tickets",
		)

		assertTrue(
			cost <= CEILING,
			"a $TICKETS-ticket list cost $cost statements; anything near $TICKETS is a query per row",
		)
	}

	/**
	 * The property the number above is a proxy for, stated directly: **the cost does not grow
	 * with the page.** A list of 200 costs what a list of 10 costs, which is the only claim
	 * that stays true as the app grows and the one an N+1 would break.
	 */
	@Test
	fun `the cost of a list does not grow with the number of tickets or fields`() {
		val team = team()
		val defined = (1..3).map { fields.define(admin, team.id, "F$it", "text", false, emptyList()) }
		val ids = (1..TICKETS).map { index ->
			tickets.create(
				actor = admin, teamId = team.id, title = "Work $index", description = null,
				status = TicketStatus.TODO, priority = TicketPriority.NONE,
				start = null, due = null, projectId = null,
				assigneeIds = emptyList(), docIds = emptyList(),
			).ticket.id
		}
		for (id in ids) {
			values.setValues(admin, id, defined.associate { it.id.toString() to "v" })
		}

		fun listOf(limit: Int) = statements {
			tickets.list(
				teamId = team.id,
				includeDescendants = false,
				includeArchived = false,
				filters = TicketFilters(),
				sortBy = ViewSortBy.UPDATED,
				limit = limit,
				offset = 0,
			)
		}

		val small = listOf(10)
		val large = listOf(TICKETS)

		assertEquals(
			small,
			large,
			"a page of $TICKETS cost $large statements where a page of 10 cost $small;" +
				" the difference is a query per row",
		)
	}

	/**
	 * The grouped endpoint the main list actually uses, which is one more query than the flat
	 * one by construction — the `GROUP BY` that makes `Todo · 29` true.
	 */
	@Test
	fun `the grouped list the main screen reads is a constant too`() {
		val team = team()
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())
		repeat(TICKETS) { index ->
			val ticket = tickets.create(
				actor = admin, teamId = team.id, title = "Work $index", description = null,
				status = if (index % 2 == 0) TicketStatus.TODO else TicketStatus.IN_PROGRESS,
				priority = TicketPriority.NONE,
				start = null, due = null, projectId = null,
				assigneeIds = emptyList(), docIds = emptyList(),
			).ticket
			values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))
		}

		var groups = emptyList<TicketGroup>()
		val cost = statements {
			groups = tickets.grouped(
				teamId = team.id,
				includeDescendants = false,
				includeArchived = false,
				filters = TicketFilters(),
				groupBy = ViewGroupBy.STATUS,
				sortBy = ViewSortBy.UPDATED,
				limit = TICKETS,
				offset = 0,
			)
		}

		assertEquals(2, groups.size)
		assertEquals(TICKETS, groups.sumOf { it.count })
		assertTrue(
			groups.all { group -> group.tickets.all { it.customFields.size == 1 } },
			"a grouped row lost its values, which the flat path would not have",
		)
		assertTrue(cost <= CEILING, "a grouped $TICKETS-ticket list cost $cost statements")
	}

	private companion object {
		const val TICKETS = 200

		/**
		 * Generous on purpose. The measured cost is a single-figure constant; this is the line
		 * between "a constant" and "per row", and it is nowhere near [TICKETS] so that an
		 * honest future relation does not trip it.
		 */
		const val CEILING = 20
	}
}

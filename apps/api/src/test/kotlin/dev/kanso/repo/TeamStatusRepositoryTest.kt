package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TeamStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalogue on disk — `KAN-28`.
 *
 * Three of these tests are about the database rather than about this class, and they are
 * the three that matter most: `teams_seed_statuses` giving a team its six whoever created
 * it, `tickets_status_fk` refusing a status a team never defined,
 * and letting a draft through because `MATCH SIMPLE` never looks. Both are invariants a
 * repository added later cannot forget, and this is where that claim is checked.
 */
@Transactional
class TeamStatusRepositoryTest : PostgresTest() {

	@Autowired lateinit var statuses: TeamStatusRepository
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var tickets: TicketRepository

	private var keys = 0

	private fun team() = teams.insert("Statuses ${UUID.randomUUID()}", "S${keys++}${(100..999).random()}", null)

	private fun ticket(teamId: UUID?, status: TicketStatus) = tickets.insert(
		id = UUID.randomUUID(),
		number = null,
		teamId = teamId,
		createdBy = null,
		title = "Held",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		estimate = null,
		start = null,
		due = null,
		projectId = null,
	)

	@Test
	fun `a team is created holding the six it always had, in order`() {
		val team = team()

		assertEquals(
			listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled"),
			statuses.forTeam(team.id).map { it.key },
		)
		// `in_review` is `started` because somebody is holding it — `DefaultStatus.category`
		// makes that argument and the seed carries it onto the row.
		assertEquals(StatusCategory.STARTED, statuses.forTeam(team.id)[3].category)
	}

	@Test
	fun `a reorder is one pass and cannot half-fail on a swap`() {
		val team = team()

		statuses.reposition(
			team.id,
			listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"),
		)

		assertEquals(listOf("todo", "backlog"), statuses.forTeam(team.id).take(2).map { it.key })
	}

	@Test
	fun `a rename leaves the key alone, because saved views hold keys`() {
		val team = team()

		assertTrue(statuses.rename(team.id, "done", "Livré"))

		assertEquals("Livré", statuses.forTeam(team.id).single { it.key == "done" }.label)
	}

	@Test
	fun `reading many teams at once keeps their words apart`() {
		val one = team()
		val other = team()
		statuses.rename(other.id, "done", "Livré")
		statuses.insert(TeamStatus(other.id, "attente_client", "Attente client", StatusCategory.STARTED, 6))

		val both = statuses.forTeams(listOf(one.id, other.id))

		assertEquals(6, both.getValue(one.id).size)
		assertEquals("Done", both.getValue(one.id).single { it.key == "done" }.label)
		assertEquals(7, both.getValue(other.id).size)
		assertEquals("Livré", both.getValue(other.id).single { it.key == "done" }.label)
	}

	@Test
	fun `a deleted status is gone`() {
		val team = team()

		assertTrue(statuses.delete(team.id, "in_review"))

		assertNull(statuses.forTeam(team.id).firstOrNull { it.key == "in_review" })
	}

	@Test
	fun `a ticket cannot hold a status its team never defined`() {
		val team = team()
		statuses.delete(team.id, "in_review")

		// The database's answer, not a service's.
		assertFailsWith<ExposedSQLException> { ticket(team.id, TicketStatus.IN_REVIEW) }
	}

	@Test
	fun `a draft passes with no team to ask`() {
		// MATCH SIMPLE: a NULL in the referencing tuple satisfies the constraint without a
		// lookup, so `KAN-9`'s drafts need no exception written anywhere.
		assertEquals(TicketStatus.IN_REVIEW, ticket(null, TicketStatus.IN_REVIEW).status)
	}
}

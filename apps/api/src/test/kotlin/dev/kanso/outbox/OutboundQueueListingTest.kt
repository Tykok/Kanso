package dev.kanso.outbox

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the queue screen lists, and in what order.
 *
 * The order is the claim: the top of the list is what moves next, so it has to be
 * `claimBatch`'s order with running jobs above it. Rolled back per test, and the Notion
 * queue emptied first, because other classes commit jobs into the same table.
 */
@Transactional
class OutboundQueueListingTest : PostgresTest() {

	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	@BeforeEach
	fun emptyTheNotionQueue() {
		jdbc.sql("DELETE FROM outbound_jobs WHERE destination = 'notion'").update()
	}

	private fun queue(
		type: OutboundEntityType,
		id: UUID = UUID.randomUUID(),
		status: String = "pending",
		dueInSeconds: Long = 0,
		error: String? = null,
	): UUID {
		jobs.enqueue(Destination.NOTION, type, id, OutboundOperation.UPSERT)
		jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = :status, last_error = :error,
			       next_attempt_at = now() + make_interval(secs => :due)
			 WHERE entity_id = :id
			""".trimIndent()
		)
			.param("status", status).param("error", error).param("due", dueInSeconds)
			.param("id", id)
			.update()
		return id
	}

	@Test
	fun `running jobs come first, then pending ones in the order the worker claims them`() {
		val ticket = queue(OutboundEntityType.TICKET)
		val team = queue(OutboundEntityType.TEAM, dueInSeconds = 60)
		val project = queue(OutboundEntityType.PROJECT, status = "running")

		assertEquals(
			listOf(project, team, ticket),
			jobs.findQueued(Destination.NOTION).map { it.entityId },
		)
	}

	@Test
	fun `failed jobs are listed apart, with their reason`() {
		val waiting = queue(OutboundEntityType.TICKET)
		val refused = queue(OutboundEntityType.TICKET, status = "failed", error = "Notion API 400")

		assertEquals(listOf(waiting), jobs.findQueued(Destination.NOTION).map { it.entityId })
		val failed = jobs.findFailedRows(Destination.NOTION).single()
		assertEquals(refused, failed.entityId)
		assertEquals("Notion API 400", failed.lastError)
	}

	@Test
	fun `a row names its entity, and says nothing for one that is gone`() {
		val admin = users.createLocalUser(
			email = "queue-${UUID.randomUUID()}@kanso.test",
			displayName = "Queue admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
		// `create` enqueues the team's own push, which is the row under test.
		val key = "Q${UUID.randomUUID().toString().take(3).uppercase()}"
		val team = teams.create(admin, "Platform", key, null)
		val gone = queue(OutboundEntityType.TICKET)

		val rows = jobs.findQueued(Destination.NOTION).associateBy { it.entityId }
		assertEquals("Platform", rows.getValue(team.id).label)
		assertNull(rows.getValue(gone).label)
	}

	@Test
	fun `the list stops at its cap and the count does not`() {
		repeat(51) { queue(OutboundEntityType.TICKET) }

		assertEquals(50, jobs.findQueued(Destination.NOTION).size)
		assertEquals(51L, jobs.countsByStatus(Destination.NOTION)["pending"])
	}
}

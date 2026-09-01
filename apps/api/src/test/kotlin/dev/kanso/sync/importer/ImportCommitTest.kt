package dev.kanso.sync.importer

import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.Team
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one import test that commits, and the only place the import's central promise —
 * one bad page must never roll back the run — is actually under test.
 *
 * Every sibling here is `@Transactional`, which is exactly why this file exists. A test
 * transaction makes `ImportWriter.write` *participate* rather than open one of its own,
 * so nothing it writes is ever committed and no commit ever evaluates the transaction's
 * rollback-only flag. That flag is the whole mechanism: a service refusing one page marks
 * it on its way out — see [ImportWriter] — and a suite that never commits cannot tell a
 * page that was dropped from a page that took the other four hundred with it. Both look
 * identical from inside the transaction that is about to be thrown away.
 *
 * The price is cleanup by hand, in the shape `InvitationTest` and `LocalAuthTest` already
 * use: everything this class writes is named from [PREFIX] so [wipe] can find it, and it
 * runs on both sides of every test so a failure mid-way cannot leave rows behind for the
 * `@Transactional` classes to trip over.
 */
class ImportCommitTest : ImportTestBase() {

	@Autowired lateinit var dependencies: DependencyRepository
	@Autowired lateinit var jdbc: JdbcClient

	/**
	 * Built per test rather than lazily like [ImportTestBase.admin]: those two are
	 * repository calls that need an Exposed transaction in context, which a class without
	 * `@Transactional` does not have — and they have to be *committed* here, because the
	 * import that reads them runs in a transaction of its own.
	 */
	private lateinit var actor: User
	private lateinit var destination: Team

	@BeforeTest
	fun open() {
		wipe()
		actor = tx.execute {
			users.createLocalUser(
				email = "$PREFIX-${UUID.randomUUID()}@kanso.test",
				displayName = "Committing import admin",
				passwordHash = encoder.hash("correct-horse-battery"),
				role = InstanceRole.ADMIN,
			)
		}!!
		destination = teamService.create(actor, "$PREFIX destination", "CMT", null)
	}

	@AfterTest
	fun close() = wipe()

	@Test
	fun `a team relation cycle drops one arrow and the rest of the import commits`() {
		// Two teams naming each other as parent. The first move is legal — nothing is
		// under either of them yet — and the second closes the loop, which `TeamService`
		// refuses. Before the pre-check existed that refusal marked the import's own
		// transaction rollback-only, and both teams vanished at the commit.
		val alpha = fakePage("$PREFIX alpha", id = "page-cmt-a", properties = mapOf("Parent" to notionRelation("page-cmt-b")))
		val beta = fakePage("$PREFIX beta", id = "page-cmt-b", properties = mapOf("Parent" to notionRelation("page-cmt-a")))
		val squads = FakeDatabase("Squads", listOf(alpha, beta), dataSourceId = "ds-cmt-teams")

		val outcome = importerFor(squads).perform(
			actor, null, listOf(mapped(squads, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent")),
		)

		assertEquals(2, outcome.teams)
		assertTrue(outcome.skipped.isEmpty(), "neither page was refused; only one arrow between them was")

		// Read in a transaction of its own, after the import's has committed: the point of
		// this class is that the rows survive the commit, which the import's own return
		// value cannot say anything about.
		val written = committedTeams()
		val committedAlpha = assertNotNull(written["$PREFIX alpha"], "the import committed")
		val committedBeta = assertNotNull(written["$PREFIX beta"], "the import committed")
		assertEquals(
			1,
			listOf(committedAlpha, committedBeta).count { it.parentTeamId != null },
			"one arrow was drawn and the one that would have closed the loop was dropped",
		)
	}

	@Test
	fun `a dependency Kanso refuses drops one arrow and the rest of the import commits`() {
		// Each page says it is blocked by the other, so the second arrow closes the loop
		// `ScheduleService` exists to refuse.
		val first = fakePage("$PREFIX first", id = "page-cmt-1", properties = mapOf("Blocked by" to notionRelation("page-cmt-2")))
		val second = fakePage("$PREFIX second", id = "page-cmt-2", properties = mapOf("Blocked by" to notionRelation("page-cmt-1")))
		val tasks = FakeDatabase("Tasks", listOf(first, second), dataSourceId = "ds-cmt-tasks")

		val outcome = importerFor(tasks).perform(
			actor, destination.id, listOf(mapped(tasks, ImportTarget.TICKETS, ImportField.BLOCKED_BY to "Blocked by")),
		)

		assertEquals(2, outcome.tickets)
		assertEquals(1, outcome.dependencies)
		assertEquals(1, outcome.droppedRelations, "counted, not guessed at")

		val committed = tx.execute { ticketRows.search(includeArchived = true, limit = 50) }.orEmpty()
			.filter { it.title.startsWith(PREFIX) }
			.associateBy { it.title }
		val one = assertNotNull(committed["$PREFIX first"], "the import committed")
		val other = assertNotNull(committed["$PREFIX second"], "the import committed")
		assertEquals(
			1,
			tx.execute { listOf(one.id to other.id, other.id to one.id).count { dependencies.exists(it.first, it.second) } },
			"one arrow was drawn and the one that would have closed the loop was dropped",
		)
	}

	@Test
	fun `a team whose name yields no free key is dropped and the rest of the import commits`() {
		// `TeamService.resolveKey` derives a key from the first three alphanumerics of the
		// name and gives up after ninety-nine collisions. A hundred names that share those
		// three characters take `COM` and `COM2`..`COM99`; the hundredth has nowhere to go.
		val crowd = List(100) { fakePage("$PREFIX key $it", id = "page-cmt-key-$it") }
		val squads = FakeDatabase("Squads", crowd, dataSourceId = "ds-cmt-keys")

		val outcome = importerFor(squads).perform(
			actor, null, listOf(ImportPlanEntry(squads.dataSourceId, ImportTarget.TEAMS)),
		)

		assertEquals(99, outcome.teams, "ninety-nine keys is all the derivation has to offer")
		assertEquals(1, outcome.skipped.size, "the hundredth is reported rather than invented")
		assertEquals(
			99,
			committedTeams().values.count { it.key.startsWith("COM") },
			"and the ninety-nine committed rather than following the hundredth into the rollback",
		)
	}

	private fun committedTeams(): Map<String, Team> =
		tx.execute { teamRows.findAll(includeArchived = true) }.orEmpty()
			.filter { it.name.startsWith(PREFIX) }
			.associateBy { it.name }

	/**
	 * Everything this class committed, and nothing else.
	 *
	 * Scoped by name rather than truncating: the container is shared with every other
	 * class in the suite, and a `DELETE FROM teams` here would take out whatever a
	 * non-`@Transactional` neighbour had committed for its own run. `outbound_jobs` is the one
	 * table this cannot scope — a job carries an entity id and no name — and it is emptied
	 * wholesale on purpose: the queue tests are `@Transactional`, so nothing of theirs is
	 * ever committed for this to delete, but the rows *this* class commits would otherwise
	 * be visible to `OutboundJobQueueTest`'s own claim.
	 */
	private fun wipe() {
		jdbc.sql("DELETE FROM outbound_jobs").update()
		jdbc.sql("DELETE FROM notion_import_origin").update()
		// Tickets and projects before their teams: a ticket is `ON DELETE CASCADE` from
		// `teams` but a project is `ON DELETE SET NULL`, so deleting the team first would
		// leave a team-less project behind for the next class to count.
		jdbc.sql("DELETE FROM tickets WHERE team_id IN (SELECT id FROM teams WHERE name LIKE :like)")
			.param("like", "$PREFIX%").update()
		jdbc.sql("DELETE FROM projects WHERE team_id IN (SELECT id FROM teams WHERE name LIKE :like)")
			.param("like", "$PREFIX%").update()
		jdbc.sql("DELETE FROM teams WHERE name LIKE :like").param("like", "$PREFIX%").update()
		jdbc.sql("DELETE FROM users WHERE email LIKE :like").param("like", "$PREFIX-%@kanso.test").update()
	}

	private companion object {
		/** On every row this class writes, so [wipe] can find them all by name. */
		const val PREFIX = "committing-import"
	}
}

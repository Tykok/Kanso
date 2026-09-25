package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.DocRepository
import dev.kanso.repo.NotionDatabaseRef
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.settings.InstanceSettingsService
import dev.kanso.sync.bootstrap.NotionBootstrap
import dev.kanso.sync.importer.FakeNotionWorkspace
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route the connections screen's Save calls, on the path where everything works.
 *
 * It creates the four databases and then reads them back through [SyncAdminController.detail].
 * That call is on `this`, so the proxy never sees it and `detail`'s `@Transactional` did
 * nothing: Exposed threw "No transaction in context" and the screen showed "Unexpected
 * server error" over a bootstrap that had succeeded. The existing guard test only reaches
 * this route with no Notion configured, which fails before the read-back.
 */
class NotionBootstrapEndpointTest : PostgresTest() {

	@Autowired lateinit var props: KansoProperties
	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var requestBases: RequestBaseRepository
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var projects: ProjectRepository
	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var docs: DocRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var jdbc: JdbcClient

	/**
	 * What the other classes left registered, put back afterwards. A forced bootstrap
	 * registers all four kinds, and `NotionOutboundHandlerTest` reads an unregistered
	 * `teams` as its "not bootstrapped yet" case — leaving ours behind fails it.
	 */
	private var before: List<NotionDatabaseRef> = emptyList()

	@BeforeEach
	fun rememberRegistrations() {
		before = tx.execute { meta.findAll() }.orEmpty()
	}

	@AfterEach
	fun restoreRegistrations() {
		tx.executeWithoutResult {
			jdbc.sql("DELETE FROM notion_databases").update()
			before.forEach { meta.save(it.kind, it.databaseId, it.dataSourceId, it.parentPageId) }
		}
	}

	/** Answers the two calls a bootstrap makes, and nothing else. */
	private class Workspace(inner: NotionClient = FakeNotionWorkspace()) : NotionClient by inner {
		override suspend fun createDatabase(
			parentPageId: String,
			title: String,
			properties: Map<String, Any?>,
		): NotionDatabase {
			val id = UUID.randomUUID().toString()
			return NotionDatabase(id, listOf("ds-$id"), title)
		}

		override suspend fun updateDataSourceSchema(
			dataSourceId: String,
			properties: Map<String, Any?>,
		) {}
	}

	@Test
	fun `a successful bootstrap answers with the databases it created`() {
		val admin = tx.execute {
			users.createLocalUser(
				email = "bootstrap-${UUID.randomUUID()}@kanso.test",
				displayName = "Bootstrap admin",
				passwordHash = encoder.hash("correct-horse-battery"),
				role = InstanceRole.ADMIN,
			)
		}!!
		tx.executeWithoutResult { settings.saveNotion(token = null, parentPageId = "parent-page") }

		val client = Workspace()
		val bootstrap = NotionBootstrap(settings, client, meta, teams, projects, tickets, docs, jobs, tx)
		val signedIn = object : CurrentUser(users) {
			override fun require(): User = admin
		}
		val controller =
			SyncAdminController(props, client, jobs, meta, requestBases, bootstrap, tx, signedIn)

		val answer = controller.bootstrapNotion(force = true)

		assertTrue(answer.bootstrapped, "the four databases were just created: $answer")
		assertEquals(
			setOf("teams", "projects", "tickets", "docs"),
			answer.databases.map { it.kind }.toSet(),
		)
	}
}

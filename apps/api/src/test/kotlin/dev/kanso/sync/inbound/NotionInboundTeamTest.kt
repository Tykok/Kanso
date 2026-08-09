package dev.kanso.sync.inbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.realtime.EventPublisher
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.sync.SyncOperation
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDatabase
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionQueryPage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Inbound sync has no actor and no plan, so it may not decide that a team is
 * archived.
 *
 * The poller is built by hand here rather than autowired: the test profile turns
 * inbound polling off (the scheduler would otherwise claim work from under other
 * tests), and the client has to be one this test controls.
 */
@Transactional
class NotionInboundTeamTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projectRows: ProjectRepository
	@Autowired lateinit var ticketRows: TicketRepository
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var events: EventPublisher
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "inbound-${UUID.randomUUID()}@kanso.test",
			displayName = "Inbound admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun newTeam(name: String, parentId: UUID? = null) =
		teams.create(admin, name, "N${UUID.randomUUID().toString().take(5).uppercase()}", parentId)

	/** Answers one page for the teams data source and nothing else. */
	private class OnePageClient(private val page: NotionPage) : NotionClient {
		override val enabled = true
		override suspend fun botUserId(): String = "bot-user"
		override suspend fun queryDataSource(
			dataSourceId: String,
			editedOnOrAfter: OffsetDateTime?,
			startCursor: String?,
			pageSize: Int,
			includeArchived: Boolean,
		) = NotionQueryPage(pages = listOf(page), nextCursor = null, hasMore = false)

		override suspend fun createDatabase(parentPageId: String, title: String, properties: Map<String, Any?>): NotionDatabase =
			throw UnsupportedOperationException()
		override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = throw UnsupportedOperationException()
		override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
			throw UnsupportedOperationException()
		override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun updatePage(pageId: String, properties: Map<String, Any?>?, archived: Boolean?): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
	}

	/**
	 * Mirrors [teamId] onto a Notion page, then polls with that page carrying
	 * [notionArchived]. `notionLastEditedTime` is left null so the echo guard does not
	 * swallow the page, and `markSynced` stamps `notion_synced_at` from the wall clock,
	 * which is after this transaction's `now()` — so the row does not look locally
	 * changed either, and the poll reaches the decision under test.
	 */
	private fun pollWith(teamId: UUID, notionArchived: Boolean): String {
		val pageId = "page-${UUID.randomUUID()}"
		val dataSourceId = "ds-${UUID.randomUUID()}"
		teamRows.markSynced(teamId, pageId, null)
		meta.save("teams", "db-$dataSourceId", dataSourceId, null)

		val poller = NotionPoller(
			props = KansoProperties(
				sync = KansoProperties.Sync(inbound = KansoProperties.Inbound(enabled = true)),
			),
			client = OnePageClient(
				NotionPage(
					id = pageId,
					lastEditedTime = OffsetDateTime.now(),
					lastEditedById = "a-human",
					archived = notionArchived,
					properties = null,
					url = null,
				)
			),
			meta = meta,
			teams = teamRows,
			projects = projectRows,
			tickets = ticketRows,
			jobs = jobs,
			events = events,
			tx = tx,
		)
		poller.poll()
		return pageId
	}

	@Test
	fun `archiving a team in Notion does not archive it in Kanso`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		jobs.claimBatch(200, "drain")

		pollWith(core.id, notionArchived = true)

		assertFalse(
			teams.get(core.id).archived,
			"a checkbox in Notion carries no disposition plan for what the team holds",
		)
		assertFalse(
			teams.get(mobile.id).archived,
			"and letting it through would have left this one live under an archived ancestor",
		)
		assertEquals(
			SyncOperation.UPSERT,
			jobs.claimBatch(200, "test").single { it.entityId == core.id }.operation,
			"Notion is pushed back to Kanso's truth rather than left disagreeing forever",
		)
	}

	@Test
	fun `unarchiving a team in Notion does not unarchive it in Kanso either`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile", core.id)
		teams.archive(admin, core.id, DispositionPlan(subTeams = DispositionChoice.TAKE))
		jobs.claimBatch(200, "drain")

		pollWith(mobile.id, notionArchived = false)

		assertTrue(
			teams.get(mobile.id).archived,
			"the other direction breaks the same invariant: a live team under an archived one",
		)
		assertEquals(
			SyncOperation.ARCHIVE,
			jobs.claimBatch(200, "test").single { it.entityId == mobile.id }.operation,
		)
	}

	@Test
	fun `a page that already agrees queues nothing`() {
		val core = newTeam("Core")
		jobs.claimBatch(200, "drain")

		pollWith(core.id, notionArchived = false)

		assertTrue(
			jobs.claimBatch(200, "test").none { it.entityId == core.id },
			"nothing disagrees, so there is nothing to correct",
		)
	}
}

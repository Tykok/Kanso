package dev.kanso.sync.inbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.realtime.EventPublisher
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TeamStatusRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.NotificationService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDataSource
import dev.kanso.sync.notion.NotionDatabase
import dev.kanso.sync.notion.NotionMember
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionQueryPage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The one place a conflict can be observed, and the row it has to leave behind.
 *
 * Screen 15's third state — the chooser — has no other way to exist: the Kanso-wins rule
 * is the only moment in the product where two versions of one field are both known, and it
 * discards one of them without saying so to anybody. This file pins that it now says so,
 * and pins the two ways that could go wrong in a way nobody would notice: a conflict
 * recorded for an edit that changed nothing, and the same conflict recorded once per poll.
 *
 * The poller is built by hand, as the two sibling files do it: the test profile turns
 * inbound polling off and the client has to be one this test controls.
 */
@Transactional
class NotionInboundConflictTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projectRows: ProjectRepository
	@Autowired lateinit var ticketRows: TicketRepository
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var requestBases: RequestBaseRepository
	@Autowired lateinit var siphon: RequestSiphon
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var statusRows: TeamStatusRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var notifications: NotificationService
	@Autowired lateinit var events: EventPublisher
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var objectMapper: ObjectMapper
	@Autowired lateinit var jdbc: JdbcClient

	private val pageId = "page-${UUID.randomUUID()}"
	private val dataSourceId = "ds-${UUID.randomUUID()}"

	private fun person(name: String, role: InstanceRole = InstanceRole.MEMBER): User =
		users.createLocalUser(
			email = "$name-${UUID.randomUUID()}@kanso.test",
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = role,
		)

	private val admin: User by lazy { person("Conflict admin", InstanceRole.ADMIN) }

	private val team by lazy {
		teams.create(admin, "Inbound", "X${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(title: String, assignees: List<UUID>): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = "todo",
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
	).ticket.id

	/** Answers one page for whatever data source is asked. */
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
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? = throw UnsupportedOperationException()
		override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
			throw UnsupportedOperationException()
		override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun updatePage(pageId: String, properties: Map<String, Any?>?, archived: Boolean?): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()

		// The import's discovery, which this fake exists to have nothing to do with. Added
		// at integration: this test and `searchDatabases` were written on two branches that
		// never saw each other, so the fake was complete on one and short a member on the
		// other. The sibling poller fakes answer the same way.
		override suspend fun searchDatabases(startCursor: String?, pageSize: Int) =
			throw UnsupportedOperationException()
		override suspend fun searchPages(startCursor: String?, pageSize: Int) =
			throw UnsupportedOperationException()
		override suspend fun listUsers(): List<NotionMember> = throw UnsupportedOperationException()
	}

	/**
	 * Mirrors [ticketId] onto the page this test polls, and puts the last successful push
	 * *before* the row's own last edit — which is the whole of "Kanso has moved on since".
	 *
	 * The one raw UPDATE is unavoidable: `markSynced` stamps `notion_synced_at` from the
	 * wall clock, which inside a rolled-back transaction lands after `updated_at`'s
	 * `now()`, so every mirrored row looks older than its own push and the poller would
	 * accept the Notion edit instead of discarding it.
	 */
	private fun mirror(ticketId: UUID) {
		ticketRows.markSynced(ticketId, pageId, null)
		jdbc.sql("UPDATE tickets SET notion_synced_at = updated_at - interval '1 minute' WHERE id = :id")
			.param("id", ticketId)
			.update()
		meta.save("tickets", "db-$dataSourceId", dataSourceId, null)
	}

	/** One poll, with the mirrored page carrying [properties]. */
	private fun poll(properties: String) {
		NotionPoller(
			props = KansoProperties(
				sync = KansoProperties.Sync(inbound = KansoProperties.Inbound(enabled = true)),
			),
			client = OnePageClient(
				NotionPage(
					id = pageId,
					lastEditedTime = OffsetDateTime.now(),
					lastEditedById = "a-human",
					archived = false,
					properties = objectMapper.readTree(properties),
					url = null,
				)
			),
			meta = meta,
			requestBases = requestBases,
			siphon = siphon,
			teams = teamRows,
			projects = projectRows,
			tickets = ticketRows,
			jobs = jobs,
			schedule = schedule,
			notifications = notifications,
			events = events,
			tx = tx,
			statuses = statusRows,
		).poll()
	}

	private fun titled(title: String) = """{"Name":{"title":[{"plain_text":"$title"}]}}"""

	@Test
	fun `a discarded Notion title reaches the assignee as a conflict the chooser can draw`() {
		val lea = person("Lea")
		val id = ticket("Echo suppression drops our own writes", listOf(lea.id))
		mirror(id)

		poll(titled("Echo suppression: our own writes are dropped"))

		val row = notifications.inbox(lea.id).rows.single { it.kind == "conflict" }
		assertEquals("title", row.payload["field"])
		assertEquals("Echo suppression drops our own writes", row.payload["mine"])
		assertEquals("Echo suppression: our own writes are dropped", row.payload["theirs"])
		assertNotNull(row.payload["theirEditedAt"], "the chooser prints the hour the Notion edit was made")
		assertNull(row.actor, "the poller has no Kanso actor to name")
		assertEquals(
			"Echo suppression drops our own writes",
			ticketRows.findById(id)!!.title,
			"and the rule is untouched: the notification records that Kanso won, it does not soften it",
		)
	}

	@Test
	fun `an inbound edit that changes no mirrored text records no conflict`() {
		val lea = person("Lea")
		val id = ticket("Echo suppression drops our own writes", listOf(lea.id))
		mirror(id)

		// Kanso wins on every poll of a row that moved since the last push, whatever the
		// page says — a page whose `last_edited_time` moved for a property Kanso does not
		// mirror included. A conflict row per poll would fill the inbox with a
		// disagreement nobody has.
		poll(titled("Echo suppression drops our own writes"))

		assertEquals(listOf("assigned"), notifications.inbox(lea.id).rows.map { it.kind })
	}

	@Test
	fun `the same discarded value is recorded once, however often the poller sees it`() {
		val lea = person("Lea")
		val id = ticket("Echo suppression drops our own writes", listOf(lea.id))
		mirror(id)

		poll(titled("Renamed in Notion"))
		poll(titled("Renamed in Notion"))

		assertEquals(1, notifications.inbox(lea.id).rows.count { it.kind == "conflict" })
	}

	@Test
	fun `a conflict on a ticket nobody owns is not broadcast`() {
		val id = ticket("Nobody's ticket", emptyList())
		mirror(id)

		poll(titled("Renamed in Notion"))

		assertEquals(
			0,
			notifications.inbox(admin.id).rows.count { it.kind == "conflict" },
			"an unassigned ticket names nobody to tell, and the poller does not fan out to a team",
		)
	}
}

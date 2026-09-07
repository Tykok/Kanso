package dev.kanso.sync.inbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.realtime.EventPublisher
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A date edited in Notion is a date change like any other, and has to reach the
 * scheduling engine.
 *
 * The contract test in `ScheduleServiceTest` pins what the poller must do; this one
 * pins that it actually does it — the failure mode being a poller that writes the
 * scalar straight to the row and leaves every successor where it was.
 *
 * The poller is built by hand for the same reasons the team test does it: the test
 * profile turns inbound polling off, and the client has to be one this test controls.
 */
@Transactional
class NotionInboundTicketTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projectRows: ProjectRepository
	@Autowired lateinit var ticketRows: TicketRepository
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var requestBases: RequestBaseRepository
	@Autowired lateinit var siphon: RequestSiphon
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var notifications: NotificationService
	@Autowired lateinit var events: EventPublisher
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var objectMapper: ObjectMapper

	private val admin: User by lazy {
		users.createLocalUser(
			email = "inbound-ticket-${UUID.randomUUID()}@kanso.test",
			displayName = "Inbound admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Inbound", "I${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, start: Int, due: Int): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = day(start),
		due = day(due),
		projectId = null,
		assigneeIds = emptyList(),
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
		override suspend fun searchDatabases(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
		override suspend fun searchPages(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
		override suspend fun listUsers(): List<NotionMember> = throw UnsupportedOperationException()
	}

	/**
	 * Mirrors [ticketId] onto a page and polls with that page carrying [due] as its
	 * `Due`. `notionLastEditedTime` stays null so the echo guard lets the page through,
	 * and `markSynced` stamps `notion_synced_at` from the wall clock — after this
	 * transaction's `now()` — so the row does not look locally changed either.
	 */
	private fun pollWithDue(ticketId: UUID, due: String) {
		val pageId = "page-${UUID.randomUUID()}"
		val dataSourceId = "ds-${UUID.randomUUID()}"
		ticketRows.markSynced(ticketId, pageId, null)
		meta.save("tickets", "db-$dataSourceId", dataSourceId, null)

		val poller = NotionPoller(
			props = KansoProperties(
				sync = KansoProperties.Sync(inbound = KansoProperties.Inbound(enabled = true)),
			),
			client = OnePageClient(
				NotionPage(
					id = pageId,
					lastEditedTime = OffsetDateTime.now(),
					lastEditedById = "a-human",
					archived = false,
					properties = objectMapper.readTree("""{"Due":{"date":{"start":"$due"}}}"""),
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
		)
		poller.poll()
	}

	@Test
	fun `a date edited in Notion cascades to the successors instead of bypassing the engine`() {
		val a = ticket("A", 1, 10)
		val b = ticket("B", 10, 15)
		deps.insert(a, b)

		pollWithDue(a, "2026-08-12")

		assertEquals(day(12).at, ticketRows.findById(a)!!.due!!.at, "the edit itself lands")
		val moved = ticketRows.findById(b)!!
		assertEquals(day(12).at, moved.start!!.at, "and its successor moves with it")
		assertEquals(day(17).at, moved.due!!.at, "keeping the five days it had")
	}

	@Test
	fun `an edit that leaves the dates alone does not cascade`() {
		val a = ticket("A", 1, 10)
		// Already violated, and deliberately so: `deps.insert` writes the arrow without
		// settling it, so B overlaps A before the poll. A cascade fired on every inbound
		// edit would repair a violation nobody asked about in this request, moving a
		// ticket on the strength of a title change.
		val b = ticket("B", 5, 8)
		deps.insert(a, b)
		jobs.claimBatch(Destination.NOTION, 200, "drain")

		pollWithDue(a, "2026-08-10")

		assertEquals(day(5).at, ticketRows.findById(b)!!.start!!.at, "B stays where it was")
		assertEquals(
			emptySet(),
			jobs.claimBatch(Destination.NOTION, 200, "test").map { it.entityId }.toSet(),
			"and no push is queued, or the mirror argues with itself over an edit that changed no date",
		)
	}
}

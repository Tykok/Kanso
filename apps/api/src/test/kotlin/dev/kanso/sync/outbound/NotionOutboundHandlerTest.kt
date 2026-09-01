package dev.kanso.sync.outbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.SyncState
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.outbox.Destination
import dev.kanso.outbox.Failure
import dev.kanso.repo.DocRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionDataSource
import dev.kanso.sync.notion.NotionDatabase
import dev.kanso.sync.notion.NotionMember
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionProps
import dev.kanso.sync.notion.NotionRateLimited
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Notion push, after the queue stopped being Notion's.
 *
 * The old `SyncWorker` had no test of its own — the queue's behaviour was pinned and
 * the push was not — so this is the pin the refactor owed: what left for Notion before
 * has to be what leaves now, and the retry policy that used to be spelled out inside
 * the worker has to survive being expressed as [Failure] values.
 */
@Transactional
class NotionOutboundHandlerTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var mapper: NotionMapper
	@Autowired lateinit var meta: NotionMetaRepository
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projectRows: ProjectRepository
	@Autowired lateinit var ticketRows: TicketRepository
	@Autowired lateinit var docRows: DocRepository
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var objectMapper: ObjectMapper
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	/** Remembers what it was asked to write, and answers as Notion would. */
	private class RecordingClient(override val enabled: Boolean = true) : NotionClient {
		val created = mutableListOf<Pair<String, Map<String, Any?>>>()
		val updated = mutableListOf<Triple<String, Map<String, Any?>?, Boolean?>>()

		override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage {
			created += dataSourceId to properties
			return page("page-${created.size}")
		}

		override suspend fun updatePage(
			pageId: String,
			properties: Map<String, Any?>?,
			archived: Boolean?,
		): NotionPage {
			updated += Triple(pageId, properties, archived)
			return page(pageId, archived == true)
		}

		private fun page(id: String, archived: Boolean = false) = NotionPage(
			id = id,
			lastEditedTime = EDITED_AT,
			lastEditedById = "bot-user",
			archived = archived,
			properties = null,
			url = null,
		)

		override suspend fun botUserId(): String = "bot-user"
		override suspend fun createDatabase(parentPageId: String, title: String, properties: Map<String, Any?>): NotionDatabase =
			throw UnsupportedOperationException()
		override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? = throw UnsupportedOperationException()
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? = throw UnsupportedOperationException()
		override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
			throw UnsupportedOperationException()
		override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
		override suspend fun searchDatabases(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
		override suspend fun searchPages(startCursor: String?, pageSize: Int) = throw UnsupportedOperationException()
		override suspend fun listUsers(): List<NotionMember> = throw UnsupportedOperationException()
		override suspend fun queryDataSource(
			dataSourceId: String,
			editedOnOrAfter: OffsetDateTime?,
			startCursor: String?,
			pageSize: Int,
			includeArchived: Boolean,
		) = throw UnsupportedOperationException()

		companion object {
			val EDITED_AT: OffsetDateTime = OffsetDateTime.parse("2026-09-01T10:00:00Z")
		}
	}

	private fun handler(client: NotionClient) = NotionOutboundHandler(
		client = client,
		mapper = mapper,
		meta = meta,
		teams = teamRows,
		projects = projectRows,
		tickets = ticketRows,
		docs = docRows,
		tx = tx,
		objectMapper = objectMapper,
	)

	private val admin: User by lazy {
		users.createLocalUser(
			email = "handler-${UUID.randomUUID()}@kanso.test",
			displayName = "Handler admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	/**
	 * The team is mirrored first, because a ticket's page carries a relation to it and
	 * `NotionMapper` raises [DependencyNotReady] rather than write a dangling one. That
	 * is the dependency order `priority` exists to produce, arranged by hand here so
	 * these tests are about the push rather than about the queue.
	 */
	private fun newTicket(title: String) = tickets.create(
		actor = admin,
		teamId = teams.create(
			admin,
			"T ${UUID.randomUUID().toString().take(4)}",
			"H${UUID.randomUUID().toString().take(4).uppercase()}",
			null,
		).id.also { teamRows.markSynced(it, "page-team", null) },
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	private fun claim() = jobs.claimBatch(Destination.NOTION, 50, "test")

	@Test
	fun `an unmirrored ticket is created in the tickets data source with its whole state`() = runTest {
		meta.save("tickets", "db-1", "ds-tickets", null)
		val ticket = newTicket("Push me")
		val job = claim().single { it.entityId == ticket.ticket.id }
		val client = RecordingClient()

		val completion = handler(client).handle(job)
		completion.record()

		val (dataSourceId, properties) = client.created.single()
		assertEquals("ds-tickets", dataSourceId)
		assertEquals(mapper.ticketProperties(ticketRows.findById(ticket.ticket.id)!!), properties)
		assertTrue(properties.containsKey(NotionProps.KANSO_ID), "the mirror is reconciled by this, not by the title")

		// The page id and Notion's own last_edited_time are the echo guard's half of
		// the deal: the inbound poller ignores any page not edited later than this.
		val stored = ticketRows.findById(ticket.ticket.id)!!
		assertEquals("page-1", stored.mirror.notionPageId)
		assertEquals(RecordingClient.EDITED_AT, stored.mirror.notionLastEditedTime)
		assertEquals(SyncState.SYNCED, stored.mirror.syncState)
	}

	@Test
	fun `a ticket that already has a page is updated rather than duplicated`() = runTest {
		meta.save("tickets", "db-1", "ds-tickets", null)
		val ticket = newTicket("Already there")
		ticketRows.markSynced(ticket.ticket.id, "page-existing", null)
		val job = claim().single { it.entityId == ticket.ticket.id }
		val client = RecordingClient()

		handler(client).handle(job).record()

		assertTrue(client.created.isEmpty(), "a second page for one ticket is how a mirror drifts")
		val (pageId, _, archived) = client.updated.single()
		assertEquals("page-existing", pageId)
		assertEquals(false, archived, "an upsert un-archives: the row is not archived any more")
	}

	@Test
	fun `archiving something never pushed writes nothing`() = runTest {
		meta.save("tickets", "db-1", "ds-tickets", null)
		val ticket = newTicket("Never mirrored")
		tickets.patch(admin, ticket.ticket.id, TicketPatch(archived = true))
		val job = claim().single { it.entityId == ticket.ticket.id }
		val client = RecordingClient()

		handler(client).handle(job).record()

		assertTrue(client.created.isEmpty() && client.updated.isEmpty(), "creating a page only to archive it litters the mirror")
	}

	@Test
	fun `a delete archives the page named in its payload, long after the row is gone`() = runTest {
		val gone = UUID.randomUUID()
		jobs.enqueue(
			Destination.NOTION,
			dev.kanso.outbox.OutboundEntityType.TICKET,
			gone,
			dev.kanso.outbox.OutboundOperation.DELETE,
			payload = deletePayload("page-deleted"),
		)
		val job = claim().single { it.entityId == gone }
		val client = RecordingClient()

		handler(client).handle(job).record()

		val (pageId, properties, archived) = client.updated.single()
		assertEquals("page-deleted", pageId)
		assertNull(properties, "there is no row left to write properties from")
		assertEquals(true, archived)
	}

	@Test
	fun `with the mirror switched off the row says so instead of waiting forever`() = runTest {
		val ticket = newTicket("Nowhere to go")
		val job = claim().single { it.entityId == ticket.ticket.id }

		handler(RecordingClient(enabled = false)).handle(job).record()

		assertEquals(SyncState.DISABLED, ticketRows.findById(ticket.ticket.id)!!.mirror.syncState)
	}

	@Test
	fun `an unbootstrapped mirror waits without spending an attempt`() = runTest {
		val ticket = newTicket("No database yet")
		val job = claim().single { it.entityId == ticket.ticket.id }
		val handler = handler(RecordingClient())

		val thrown = runCatching { handler.handle(job) }.exceptionOrNull()

		assertNotNull(thrown)
		val verdict = handler.classify(thrown as Exception)
		assertTrue(verdict is Failure.Defer, "nothing is wrong with the job; the databases do not exist yet")
	}

	/**
	 * The old worker's `when` over exception types, now expressed as [Failure] values.
	 * Same three answers: wait, try again, stop.
	 */
	@Test
	fun `Notion's refusals are sorted into wait, retry and stop`() {
		val handler = handler(RecordingClient())

		assertEquals(
			Duration.ofSeconds(7),
			(handler.classify(NotionRateLimited(Duration.ofSeconds(7))) as Failure.Defer).delay,
			"a rate limit is the queue being busy, not the job being wrong",
		)
		assertTrue(handler.classify(DependencyNotReady("team has no page yet")) is Failure.Defer)
		assertTrue(handler.classify(NotionApiException(502, null, "bad gateway")) is Failure.Retry)
		assertTrue(
			handler.classify(NotionApiException(400, null, "no such property")) is Failure.Fatal,
			"eight attempts at something Notion will never accept is eight wasted minutes",
		)
	}

	@Test
	fun `giving up marks the row failed, which is what the failures tab reads`() = runTest {
		val ticket = newTicket("Refused")
		val job = claim().single { it.entityId == ticket.ticket.id }

		handler(RecordingClient()).onGivenUp(job, IllegalStateException("locked"))

		assertEquals(SyncState.FAILED, ticketRows.findById(ticket.ticket.id)!!.mirror.syncState)
	}
}

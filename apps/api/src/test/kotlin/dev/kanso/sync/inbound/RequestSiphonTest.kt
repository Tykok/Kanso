package dev.kanso.sync.inbound

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.realtime.EventPublisher
import dev.kanso.service.NotificationService
import dev.kanso.service.ScheduleService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import dev.kanso.service.TriageDecision
import dev.kanso.service.TriageService
import dev.kanso.sync.notion.NotionPage
import org.springframework.beans.factory.annotation.Autowired
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
import kotlin.test.assertTrue

/**
 * A Notion base of requests, siphoned into the triage queue that already existed.
 *
 * Built by hand like the other three inbound tests, for the reasons they give: the test
 * profile turns polling off, and the client has to be one this test controls. This one's
 * client also *refuses every write* — `createPage` and `updatePage` throw — so a siphon
 * that ever pushed to a requests base would fail here rather than be argued about.
 */
@Transactional
class RequestSiphonTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var triage: TriageService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projectRows: ProjectRepository
	@Autowired lateinit var ticketRows: TicketRepository
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
			email = "requests-${UUID.randomUUID()}@kanso.test",
			displayName = "Requests admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Requests", "R${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** A registered requests base nothing else in the run shares. */
	private fun base(): String {
		val dataSourceId = "ds-req-${UUID.randomUUID()}"
		requestBases.save(dataSourceId, "db-$dataSourceId", team.id)
		return dataSourceId
	}

	private fun page(
		id: String = "page-${UUID.randomUUID()}",
		properties: String = """{"Name":{"title":[{"plain_text":"The export is missing VAT"}]}}""",
		archived: Boolean = false,
		editedAt: OffsetDateTime = OffsetDateTime.now(),
	) = NotionPage(
		id = id,
		lastEditedTime = editedAt,
		lastEditedById = "a-salesperson",
		archived = archived,
		properties = objectMapper.readTree(properties),
		url = "https://notion.so/$id",
	)

	/**
	 * Polls [pages] as the whole of [dataSourceId], through the real poller.
	 *
	 * Through the poller rather than by calling [RequestSiphon.adopt] directly: the thing
	 * worth pinning is that a registered base is *reached* by the walk that already existed,
	 * and a test that bypassed the walk would still pass with the wiring removed.
	 */
	private fun poll(dataSourceId: String, vararg pages: NotionPage) {
		NotionPoller(
			props = KansoProperties(
				sync = KansoProperties.Sync(inbound = KansoProperties.Inbound(enabled = true)),
			),
			client = ReadOnlyClient(dataSourceId, pages.toList()),
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
		).poll()
	}

	/** Every ticket in this team's queue, by title. */
	private fun queued(): List<String> = triage.queue(team.id).items.map { it.ticket.title }

	@Test
	fun `a page created in the requests base becomes an untriaged ticket in the team's queue`() {
		val dataSourceId = base()
		val notionPage = page()

		poll(dataSourceId, notionPage)

		assertEquals(listOf("The export is missing VAT"), queued(), "it is in the queue nobody had to flag it into")
		val ticket = ticketRows.findAllById(triage.queue(team.id).items.map { it.ticket.id }).single()
		assertEquals(team.id, ticket.teamId, "filed into the team the base was registered against")
		assertNotNull(ticket.number, "so it has a number, and therefore a name people can say")
		assertEquals(DefaultStatus.TODO, ticket.status, "Kanso's default, not a status read off somebody's base")
		assertEquals(TicketPriority.NONE, ticket.priority, "and no priority a requester could have set for us")
		assertNull(ticket.createdBy, "nobody in Kanso wrote it, and V20 makes that column nullable for this")
	}

	/**
	 * The one-way promise, asserted where it is actually kept.
	 *
	 * `NotionOutboundHandler.plan` addresses a page by `tickets.notion_page_id`, so a
	 * siphoned ticket that carried the requests page there would have its every push
	 * rewrite the salesperson's own page. The proof is that the id is not on the row and no
	 * row claims it — the push that *is* queued has no page to update, so it creates one in
	 * `Kanso · Tickets` instead.
	 */
	@Test
	fun `no push can address the requests base, because nothing records its page id on the ticket`() {
		val dataSourceId = base()
		val notionPage = page()

		poll(dataSourceId, notionPage)

		val ticket = ticketRows.findAllById(triage.queue(team.id).items.map { it.ticket.id }).single()
		assertNull(ticket.mirror.notionPageId, "the requests page is not the ticket's mirror page")
		assertNull(
			ticketRows.findByNotionPageId(notionPage.id),
			"and no ticket claims it, which is the lookup a push would have to succeed at",
		)
	}

	@Test
	fun `the same page polled twice produces one ticket, not two`() {
		val dataSourceId = base()
		val notionPage = page()

		poll(dataSourceId, notionPage)
		poll(dataSourceId, notionPage)

		assertEquals(1, queued().size, "the ledger's primary key is the rule, not the poll cursor")
	}

	/**
	 * The failure this feature would be judged on. A request is triaged and closed; the
	 * person who filed it goes back to Notion and adds a sentence. The poller re-reads the
	 * page — it re-reads every page inside the overlap window — and must produce nothing.
	 */
	@Test
	fun `a page edited after adoption neither resurrects its triaged ticket nor files a second one`() {
		val dataSourceId = base()
		val notionPage = page()
		poll(dataSourceId, notionPage)
		val ticketId = triage.queue(team.id).items.single().ticket.id

		triage.decide(admin, ticketId, TriageDecision.CLOSED, duplicateOf = null)
		assertEquals(emptyList(), queued(), "closed at the gate, so out of the queue")

		poll(
			dataSourceId,
			page(
				id = notionPage.id,
				properties = """{"Name":{"title":[{"plain_text":"The export is missing VAT — still"}]}}""",
				editedAt = OffsetDateTime.now().plusMinutes(5),
			),
		)

		assertEquals(emptyList(), queued(), "the edit does not put it back")
		assertEquals(
			DefaultStatus.CANCELED,
			ticketRows.findById(ticketId)!!.status,
			"and does not undo the ruling either",
		)
		assertEquals(
			"The export is missing VAT",
			ticketRows.findById(ticketId)!!.title,
			"the ticket is Kanso's now; the page is not editing it",
		)
	}

	/**
	 * What a requester typed into columns Kanso was never told about.
	 *
	 * Preserved rather than mapped, and preserved through the section the one-shot import
	 * already builds: the alternative is a poller that guesses which column is the priority.
	 */
	@Test
	fun `the columns nobody mapped survive as prose instead of being dropped`() {
		val dataSourceId = base()

		poll(
			dataSourceId,
			page(
				properties = """
				{"Name":{"title":[{"plain_text":"Bulk CSV export"}]},
				 "Client":{"rich_text":[{"plain_text":"Acme"}]},
				 "Urgence":{"select":{"name":"Bloquant"}},
				 "Deal size":{"number":42000}}
				""".trimIndent(),
			),
		)

		val description = triage.queue(team.id).items.single().ticket.description.orEmpty()
		assertTrue(description.contains("Client: Acme"), "the customer survives: $description")
		assertTrue(description.contains("Urgence: Bloquant"), "and so does what they called urgent")
		assertTrue(description.contains("Deal size: 42000"), "and the number")
		assertEquals(
			TicketPriority.NONE,
			ticketRows.findAllById(listOf(triage.queue(team.id).items.single().ticket.id)).single().priority,
			"'Bloquant' is preserved as prose and refused as a priority — the queue's order is Kanso's",
		)
	}

	/**
	 * The duplicate detection the ticket said already existed, exercised on a siphoned row.
	 *
	 * It is `similarity()` on the title scoped to the ticket's team, so it works here only
	 * because the request was filed into a team — which is the whole of `V37`'s argument
	 * about why a team-less request would have been a dead letter box.
	 */
	@Test
	fun `a siphoned request is compared against the team's tickets by trigram, with no new detector`() {
		val existing = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "The export is missing VAT",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

		poll(base(), page())

		val siphonedId = triage.queue(team.id).items.map { it.ticket.id }.first { it != existing }
		val similar = triage.similar(siphonedId)
		assertEquals(
			listOf(existing),
			similar.map { it.detail.ticket.id },
			"the request finds the ticket it reads like",
		)
		assertTrue(similar.single().similarity >= 90, "and scores it high: ${similar.single().similarity}")
	}

	@Test
	fun `a page in Notion's trash and a page with no title are both left alone`() {
		val dataSourceId = base()

		poll(
			dataSourceId,
			page(archived = true),
			page(properties = """{"Name":{"title":[]}}"""),
		)

		assertEquals(emptyList(), queued(), "neither is a request anybody can act on")
	}
}

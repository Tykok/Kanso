package dev.kanso.publik

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screen 27: four columns, the application's own statuses, most wanted first. */
@Transactional
class PublicRoadmapTest : PostgresTest() {

	@Autowired lateinit var roadmap: PublicRoadmapService
	@Autowired lateinit var votes: VoteService
	@Autowired lateinit var publication: PublicationService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var users: UserRepository

	private val owner: User by lazy {
		users.createLocalUser(
			email = "roadmap-${UUID.randomUUID()}@kanso.test",
			displayName = "Roadmap owner",
			passwordHash = "not-a-real-hash",
			role = InstanceRole.OWNER,
		)
	}

	private val team by lazy {
		teams.insert(
			name = "Roadmap ${UUID.randomUUID()}",
			key = "R${UUID.randomUUID().toString().take(4).uppercase()}",
			parentTeamId = null,
		)
	}

	/** A published ticket, with as many votes as asked for. */
	private fun published(title: String, status: TicketStatus, voters: Int = 0): String {
		val created = tickets.create(
			actor = owner,
			teamId = team.id,
			title = title,
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		publication.publish(owner, created.ticket.id, public = true)
		repeat(voters) { votes.vote(created.teamKey, created.ticket.number, "voter-$title-$it") }
		return created.identifier
	}

	private fun groups() = roadmap.roadmap().groups.associateBy { it.status }

	@Test
	fun `the columns are the application's own statuses, canceled excluded`() {
		published("Two-level sub-tickets", TicketStatus.BACKLOG)
		published("Documents trash", TicketStatus.TODO)
		published("Plan with dependencies", TicketStatus.IN_PROGRESS)
		published("Notion import", TicketStatus.DONE)
		published("A road not taken", TicketStatus.CANCELED)

		val groups = groups()
		assertEquals(
			listOf(TicketStatus.BACKLOG, TicketStatus.TODO, TicketStatus.IN_PROGRESS, TicketStatus.DONE),
			roadmap.roadmap().groups.map { it.status },
			"four columns, in the application's own order, and only the ones holding work",
		)
		assertNull(groups[TicketStatus.CANCELED], "a roadmap does not have a 'we refused this' column")
		assertEquals(1, groups.getValue(TicketStatus.IN_PROGRESS).count)
	}

	@Test
	fun `a column's count is the number of rows under it`() {
		published("First", TicketStatus.TODO)
		published("Second", TicketStatus.TODO)
		published("Third", TicketStatus.TODO)

		val todo = groups().getValue(TicketStatus.TODO)
		assertEquals(3, todo.count)
		assertEquals(todo.tickets.size, todo.count, "the heading may not disagree with the rows")
	}

	@Test
	fun `the open columns read most wanted first, and delivered reads most recent first`() {
		published("Barely wanted", TicketStatus.BACKLOG, voters = 1)
		published("Widely wanted", TicketStatus.BACKLOG, voters = 3)
		published("Somewhat wanted", TicketStatus.BACKLOG, voters = 2)

		assertEquals(
			listOf("Widely wanted", "Somewhat wanted", "Barely wanted"),
			groups().getValue(TicketStatus.BACKLOG).tickets.map { it.title },
		)

		// `completed_at` is written by the status move, so ship them through it rather
		// than writing the column: the ordering has to hold for data the application
		// produced, not for data a test arranged.
		val older = published("Shipped first", TicketStatus.TODO)
		val newer = published("Shipped second", TicketStatus.TODO)
		for (key in listOf(older, newer)) ship(key)

		val delivered = groups().getValue(TicketStatus.DONE).tickets
		assertEquals(
			listOf("Shipped second", "Shipped first"),
			delivered.map { it.title },
			"once it has shipped the question is 'when', not 'will you'",
		)
		assertTrue(delivered.all { it.completedAt != null }, "a delivered row prints when it shipped")
	}

	private fun ship(key: String) {
		val (teamKey, number) = key.split("-")
		val detail = tickets.getByIdentifier(teamKey, number.toInt())
		tickets.patch(owner, detail.ticket.id, TicketPatch(status = TicketStatus.DONE))
	}

	@Test
	fun `unpublishing takes a ticket back out of the window`() {
		val key = published("Briefly public", TicketStatus.TODO)
		val (teamKey, number) = key.split("-")
		assertNotNull(roadmap.contributorPage(teamKey, number.toInt()))

		val detail = tickets.getByIdentifier(teamKey, number.toInt())
		publication.publish(owner, detail.ticket.id, public = false)

		assertFalse(
			roadmap.roadmap().groups.flatMap { it.tickets }.any { it.identifier == key },
			"publishing is reversible, and reversing it has to actually reverse it",
		)
	}

	@Test
	fun `an archived ticket is not in the window even while it is still marked public`() {
		val key = published("Archived but published", TicketStatus.TODO)
		val (teamKey, number) = key.split("-")
		val detail = tickets.getByIdentifier(teamKey, number.toInt())
		tickets.patch(owner, detail.ticket.id, TicketPatch(archived = true))

		assertFalse(
			roadmap.roadmap().groups.flatMap { it.tickets }.any { it.identifier == key },
			"archiving is the app saying 'not any more', and the shop window has to hear it",
		)
	}

	@Test
	fun `the contributor page carries the context around one ticket`() {
		val other = published("Documents trash", TicketStatus.TODO)
		val key = published("The seal is unreadable at 100% zoom", TicketStatus.TODO, voters = 2)
		val (teamKey, number) = key.split("-")

		val detail = tickets.getByIdentifier(teamKey, number.toInt())
		publication.whereToLook(
			owner,
			detail.ticket.id,
			listOf(
				FilePointer("packages/ui/seal.css", "the pattern"),
				FilePointer("packages/ui/tokens.css", "the grid step"),
			),
		)

		val page = roadmap.contributorPage(teamKey, number.toInt())
		assertEquals(2, page.votes)
		assertTrue(page.unclaimed, "nobody is assigned, which is the drawing's third badge")
		assertEquals(
			listOf("packages/ui/seal.css", "packages/ui/tokens.css"),
			page.whereToLook.map { it.path },
			"in the order the maintainer gave, which is the advice",
		)
		assertTrue(page.otherFirstSteps.any { it.identifier == other }, "and where to go next")
		assertFalse(page.otherFirstSteps.any { it.identifier == key }, "but not back to itself")
		assertEquals(2, page.unclaimedCount, "this one and the other one")
	}

	@Test
	fun `where to look is replaced wholesale, not merged`() {
		val key = published("Somewhere to look", TicketStatus.TODO)
		val (teamKey, number) = key.split("-")
		val id = tickets.getByIdentifier(teamKey, number.toInt()).ticket.id

		publication.whereToLook(owner, id, listOf(FilePointer("first.css", null)))
		publication.whereToLook(owner, id, listOf(FilePointer("second.css", null)))

		assertEquals(
			listOf("second.css"),
			roadmap.contributorPage(teamKey, number.toInt()).whereToLook.map { it.path },
			"a path a maintainer deliberately dropped must not survive the edit",
		)
	}
}

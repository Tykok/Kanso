package dev.kanso.publik

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.LabelService
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
	@Autowired lateinit var labels: LabelService
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
	private fun published(title: String, status: DefaultStatus, voters: Int = 0): String {
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
		repeat(voters) { votes.vote(created.teamKey!!, created.ticket.number!!, "voter-$title-$it") }
		return created.identifier!!
	}

	private fun groups() = roadmap.roadmap().groups.associateBy { it.status }

	private fun idOf(key: String): UUID {
		val (teamKey, number) = key.split("-")
		return tickets.getByIdentifier(teamKey, number.toInt()).ticket.id
	}

	private fun page(key: String): ContributorPage {
		val (teamKey, number) = key.split("-")
		return roadmap.contributorPage(teamKey, number.toInt())
	}

	@Test
	fun `the columns are the application's own statuses, canceled excluded`() {
		published("Two-level sub-tickets", DefaultStatus.BACKLOG)
		published("Documents trash", DefaultStatus.TODO)
		published("Plan with dependencies", DefaultStatus.IN_PROGRESS)
		published("Notion import", DefaultStatus.DONE)
		published("A road not taken", DefaultStatus.CANCELED)

		val groups = groups()
		assertEquals(
			listOf(DefaultStatus.BACKLOG, DefaultStatus.TODO, DefaultStatus.IN_PROGRESS, DefaultStatus.DONE),
			roadmap.roadmap().groups.map { it.status },
			"four columns, in the application's own order, and only the ones holding work",
		)
		assertNull(groups[DefaultStatus.CANCELED], "a roadmap does not have a 'we refused this' column")
		assertEquals(1, groups.getValue(DefaultStatus.IN_PROGRESS).count)
	}

	@Test
	fun `a column's count is the number of rows under it`() {
		published("First", DefaultStatus.TODO)
		published("Second", DefaultStatus.TODO)
		published("Third", DefaultStatus.TODO)

		val todo = groups().getValue(DefaultStatus.TODO)
		assertEquals(3, todo.count)
		assertEquals(todo.tickets.size, todo.count, "the heading may not disagree with the rows")
	}

	@Test
	fun `the open columns read most wanted first, and delivered reads most recent first`() {
		published("Barely wanted", DefaultStatus.BACKLOG, voters = 1)
		published("Widely wanted", DefaultStatus.BACKLOG, voters = 3)
		published("Somewhat wanted", DefaultStatus.BACKLOG, voters = 2)

		assertEquals(
			listOf("Widely wanted", "Somewhat wanted", "Barely wanted"),
			groups().getValue(DefaultStatus.BACKLOG).tickets.map { it.title },
		)

		// `completed_at` is written by the status move, so ship them through it rather
		// than writing the column: the ordering has to hold for data the application
		// produced, not for data a test arranged.
		val older = published("Shipped first", DefaultStatus.TODO)
		val newer = published("Shipped second", DefaultStatus.TODO)
		for (key in listOf(older, newer)) ship(key)

		val delivered = groups().getValue(DefaultStatus.DONE).tickets
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
		tickets.patch(owner, detail.ticket.id, TicketPatch(status = DefaultStatus.DONE))
	}

	@Test
	fun `unpublishing takes a ticket back out of the window`() {
		val key = published("Briefly public", DefaultStatus.TODO)
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
		val key = published("Archived but published", DefaultStatus.TODO)
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
		val other = published("Documents trash", DefaultStatus.TODO)
		val key = published("The seal is unreadable at 100% zoom", DefaultStatus.TODO, voters = 2)
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
		assertEquals(2, page.availableCount, "this one and the other one")
		assertNull(
			page.firstStepLabel,
			"nobody has defined a `good first step` label, so the eyebrow may not claim one",
		)
	}

	/**
	 * Screen 28's eyebrow reads `Good first step · 12 available`, not `Unclaimed · 12`.
	 * Once a team defines the label, the list is the tickets wearing it — strictly fewer
	 * than the unclaimed ones, which is why the old count could only ever have been too
	 * generous rather than a claim no ticket backed.
	 */
	@Test
	fun `first steps narrow to the good first step label once a team defines one`() {
		val chosen = published("Translate the status labels", DefaultStatus.TODO)
		val alsoChosen = published("Documents trash", DefaultStatus.TODO)
		val bare = published("Rewrite the synchronisation engine", DefaultStatus.TODO)
		val first = labels.create(owner, team.id, "good first step", "green")
		labels.attach(owner, idOf(chosen), first.id)
		labels.attach(owner, idOf(alsoChosen), first.id)

		val page = page(chosen)

		assertEquals("good first step", page.firstStepLabel, "and the page says what it narrowed to")
		assertEquals(2, page.availableCount, "the two wearing the label, not the three unclaimed ones")
		assertTrue(page.otherFirstSteps.any { it.identifier == alsoChosen })
		assertFalse(
			page.otherFirstSteps.any { it.identifier == bare },
			"an unclaimed ticket nobody marked is not a first step, whatever else it is",
		)
	}

	/**
	 * The narrowing is decided by the label existing, not by any published ticket wearing
	 * it: a team that defined `good first step` and marked nothing yet is saying there are
	 * none, and answering with every unclaimed ticket instead would overrule them.
	 */
	@Test
	fun `a defined label nobody has used yet leaves no first steps rather than falling back`() {
		val key = published("Rewrite the synchronisation engine", DefaultStatus.TODO)
		labels.create(owner, team.id, "good first step", "green")

		val page = page(key)

		assertEquals("good first step", page.firstStepLabel)
		assertEquals(0, page.availableCount)
		assertEquals(emptyList(), page.otherFirstSteps)
	}

	/** The drawing's badges beside the title: the ticket's own labels, then `nobody on it`. */
	@Test
	fun `the badges beside the title are the ticket's own labels, by name`() {
		val key = published("The seal is unreadable at 100% zoom", DefaultStatus.TODO)
		val design = labels.create(owner, team.id, "design system", "blue")
		val first = labels.create(owner, team.id, "good first step", "green")
		labels.attach(owner, idOf(key), design.id)
		labels.attach(owner, idOf(key), first.id)

		val page = page(key)

		assertEquals(listOf("design system", "good first step"), page.labels)
		assertTrue(page.unclaimed, "and `nobody on it` stays derived from ticket_assignees")
	}

	@Test
	fun `where to look is replaced wholesale, not merged`() {
		val key = published("Somewhere to look", DefaultStatus.TODO)
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

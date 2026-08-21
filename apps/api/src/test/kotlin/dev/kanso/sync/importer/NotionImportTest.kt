package dev.kanso.sync.importer

import dev.kanso.docs.DocBlockRepository
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.TeamRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 3: the first thing that writes.
 *
 * Every assertion here goes through the services, never through an event: the suite is
 * `@Transactional` and rolls back, so nothing in it ever reaches `pg_notify`.
 */
@Transactional
class NotionImportTest : ImportTestBase() {

	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var blockRows: DocBlockRepository
	@Autowired lateinit var dependencies: DependencyRepository

	private val engineering = FakeDatabase(
		"Engineering tasks",
		pages = listOf(
			fakePage(
				"Index the archive",
				id = "page-eng-1",
				properties = mapOf(
					"Status" to notionSelect("In Progress"),
					"Priority" to notionSelect("High"),
					"Due" to notionDate("2026-09-01"),
					"Sprint" to notionNumber(12),
					"Squad" to notionSelect("Platform"),
					"Spec" to notionRelation("page-spec-1"),
				),
			),
			fakePage("Paginate the walk", id = "page-eng-2", titleProperty = "Tâche"),
		),
	)

	private val specs = FakeDatabase("Product specs", pages = listOf(fakePage("Search spec", id = "page-spec-1")))

	private val design = FakeDatabase(
		"Design docs",
		pages = listOf(
			fakePage("Type scale", id = "page-design-1", properties = mapOf("Owner" to notionText("M. Rey"))),
		),
	)

	private val archive = FakeDatabase("Archive 2023", pages = List(4) { fakePage("Old $it") })

	private fun importer() = importerFor(engineering, specs, design, archive)

	/**
	 * What the request carries about the engineering base: which of its columns answer which
	 * field, including the one that means "waits on". Given here rather than through
	 * [NotionImportService.perform] because the wire does not carry a mapping yet — the
	 * tests that need one call the writer with the bases the service would have built.
	 */
	private val engineeringMapping = ColumnMapping(
		columns = mapOf(
			ImportField.STATUS to "Status",
			ImportField.PRIORITY to "Priority",
			ImportField.DUE to "Due",
			ImportField.BLOCKED_BY to "Spec",
		),
	)

	private fun writeMapped(vararg bases: PlannedBase) = writer.write(admin, team.id, bases.toList())

	private fun drawnPlan() = plan(
		engineering to ImportTarget.TICKETS,
		specs to ImportTarget.TICKETS,
		design to ImportTarget.DOCUMENTS,
	)

	private fun projectNamed(name: String) =
		projectRows.search(teamIds = null, includeArchived = true).single { it.name == name }

	private fun ticketsOf(projectName: String) =
		ticketRows.search(projectId = projectNamed(projectName).id, includeArchived = true, limit = 500)

	@Test
	fun `refuses a team the actor may not write to, before anything is written`() {
		// An import is a write, and the largest one in the product. `TicketAccess` gates it
		// like any other, and the refusal has to come before the first insert rather than
		// after four hundred of them.
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		val outsider = users.createLocalUser(
			email = "outsider-${UUID.randomUUID()}@kanso.test",
			displayName = "Outsider",
			passwordHash = "x",
			role = InstanceRole.MEMBER,
		)
		val before = rowCounts()

		assertFailsWith<AccessDeniedException> { importer().perform(outsider, team.id, drawnPlan()) }
		assertEquals(before, rowCounts(), "and nothing was written on the way to the refusal")
	}

	@Test
	fun `a base mapped to a project becomes that project, and its pages become tickets in it`() {
		val outcome = importer().perform(admin, team.id, drawnPlan())

		assertEquals(2, outcome.projects)
		assertEquals(3, outcome.tickets, "two engineering pages and one spec")

		val imported = ticketsOf("Engineering tasks")
		assertEquals(setOf("Index the archive", "Paginate the walk"), imported.map { it.title }.toSet())
		// The title was found by type, not by name: the second page calls its title "Tâche".
		val indexed = imported.single { it.title == "Index the archive" }
		assertEquals(team.id, indexed.teamId, "the team comes from the request, which is the only place it can")
		// Nothing in this plan said which column the status is, and a column called `Status`
		// is only the status because somebody says so — so the writer's default stands.
		assertEquals(TicketStatus.TODO, indexed.status)
		assertEquals(TicketPriority.NONE, indexed.priority)
		assertNull(indexed.due)
	}

	@Test
	fun `every imported ticket gets a number from its team, like any other`() {
		importer().perform(admin, team.id, drawnPlan())

		val numbers = ticketRows.search(teamIds = listOf(team.id), includeArchived = true, limit = 500)
			.map { it.number }
			.sorted()
		assertEquals(listOf(1, 2, 3), numbers, "no gaps and no collisions: the team's counter did the work")
	}

	@Test
	fun `a base mapped to documents becomes a folder of documents, not tickets`() {
		val outcome = importer().perform(admin, team.id, drawnPlan())

		assertEquals(1, outcome.folders)
		assertEquals(1, outcome.docs)
		val folder = folderRows.findByTeam(team.id).single { it.name == "Design docs" }
		assertEquals(listOf("Type scale"), pageRows.search(team.id, folder.id, 50).map { it.title })
		assertFalse(
			ticketRows.search(teamIds = listOf(team.id), includeArchived = true, limit = 500).any { it.title == "Type scale" },
			"a document is not also a ticket",
		)
	}

	@Test
	fun `a base nobody mapped is left alone`() {
		importer().perform(admin, team.id, drawnPlan())

		assertEquals(
			emptyList(),
			projectRows.search(teamIds = null, includeArchived = true).filter { it.name == "Archive 2023" },
			"891 pages nobody asked for is the outcome this dialog exists to prevent",
		)
		assertEquals(3, ticketRows.search(includeArchived = true, limit = 500).size)
	}

	@Test
	fun `the relation the mapping calls a dependency becomes one`() {
		writeMapped(
			planned(engineering, ImportTarget.TICKETS, engineeringMapping),
			planned(specs, ImportTarget.TICKETS),
		)

		val successor = ticketsOf("Engineering tasks").single { it.title == "Index the archive" }
		val predecessor = ticketsOf("Product specs").single()
		assertTrue(
			dependencies.exists(predecessor.id, successor.id),
			"the page holding the relation waits on the page it names, which is what `Blocked by` means",
		)
	}

	@Test
	fun `a relation nobody mapped as a dependency does not become one`() {
		// The `Spec` relation is right there on the page, and both its ends are imported.
		// Every relation used to become an arrow; a `Related` column is a link between two
		// pages, not an order to do them in, so now only the mapped column is read.
		val outcome = importer().perform(admin, team.id, drawnPlan())

		assertEquals(0, outcome.dependencies)
		assertEquals(0, outcome.droppedRelations, "and it is not dropped either: nothing tried to read it")
	}

	@Test
	fun `a mapped relation with one end outside the import is dropped and counted`() {
		// Product specs is not in this plan, so the relation has one end and nothing to be
		// a dependency between.
		val outcome = writeMapped(planned(engineering, ImportTarget.TICKETS, engineeringMapping))

		assertEquals(0, outcome.dependencies)
		assertEquals(1, outcome.droppedRelations, "counted, not guessed at")
	}

	@Test
	fun `a page Kanso cannot adopt is reported rather than invented`() {
		val awkward = FakeDatabase(
			"Field notes",
			pages = listOf(fakePage("Readable", id = "page-ok"), untitledPage("page-nameless")),
		)
		val outcome = importerFor(awkward).perform(admin, team.id, plan(awkward to ImportTarget.TICKETS))

		assertEquals(1, outcome.tickets)
		val skipped = outcome.skipped.single()
		assertEquals("page-nameless", skipped.pageId)
		assertEquals("Field notes", skipped.source)
		assertTrue(skipped.reason.contains("title"), "the report says what was wrong with it: ${skipped.reason}")
	}

	@Test
	fun `a mapped column fills the ticket's own field`() {
		writeMapped(planned(engineering, ImportTarget.TICKETS, engineeringMapping))

		val indexed = ticketsOf("Engineering tasks").single { it.title == "Index the archive" }
		assertEquals(TicketStatus.IN_PROGRESS, indexed.status)
		assertEquals(TicketPriority.HIGH, indexed.priority)
		assertEquals("2026-09-01", indexed.due?.at?.toLocalDate()?.toString())
	}

	@Test
	fun `a property the mapping did not claim stays readable on the ticket`() {
		writeMapped(planned(engineering, ImportTarget.TICKETS, engineeringMapping))

		val description = ticketsOf("Engineering tasks").single { it.title == "Index the archive" }.description
		assertTrue(description!!.contains("Imported from Notion"), "the drawing's own section heading")
		assertTrue(description.contains("Sprint: 12"), description)
		assertTrue(description.contains("Squad: Platform"), description)
		assertFalse(description.contains("Status"), "what has a column of its own is not repeated here")
	}

	@Test
	fun `a property Kanso has no column for stays readable on a document too`() {
		importer().perform(admin, team.id, drawnPlan())

		val folder = folderRows.findByTeam(team.id).single { it.name == "Design docs" }
		val page = pageRows.search(team.id, folder.id, 50).single()
		val text = blockRows.findByPage(page.id).joinToString("\n") { it.content["text"]?.toString().orEmpty() }
		assertTrue(text.contains("Imported from Notion"), text)
		assertTrue(text.contains("Owner: M. Rey"), text)
	}
}

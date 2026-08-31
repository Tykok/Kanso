package dev.kanso.sync.importer

import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Notion `people` column, and the standing correspondence that lets a mapped one become
 * an assignee or a lead — see [NotionPeople.link] for the half of this that outlives the
 * import.
 */
@Transactional
class ImportPeopleTest : ImportTestBase() {

	@Test
	fun `a mapped person becomes the assignee, and the link is remembered`() {
		// No `teamService.addMember` here, on purpose: Kanso itself does not require an
		// assignee to be a member of the ticket's team, so neither does the import — this
		// is also the shape of the importer matching their *own* Notion person to their
		// own account, who is very often not yet a member of every team they import into.
		val rey = users.createLocalUser("rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))

		importerFor(tasks).perform(
			admin, team.id,
			people = mapOf("u-1" to rey.id),
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertEquals(listOf(rey.id), ticketRows.assigneeIds(ticket.id))
		assertEquals("u-1", users.findById(rey.id)!!.notionPersonId)
	}

	@Test
	fun `a person nobody mapped leaves the ticket unassigned and the column preserved`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-9" to "Someone")))))

		importerFor(tasks).perform(
			admin, team.id,
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertTrue(ticketRows.assigneeIds(ticket.id).isEmpty())
	}

	@Test
	fun `the people a plan would meet are listed without writing anything`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))
		val importer = importerFor(tasks)
		val before = rowCounts()

		val seen = importer.peopleSeen(
			listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			)
		)

		assertEquals(listOf(NotionPerson("u-1", "M. Rey")), seen)
		assertEquals(before, rowCounts())
	}

	/**
	 * The one thing that can still make `TicketService.create` refuse an assignee: an id
	 * `people` names that `users` no longer holds, because the account was deleted between
	 * the people-matching step and this run. A workspace of four hundred pages is not made
	 * safe by letting the one page naming a ghost id take the other three hundred ninety-
	 * nine down with it — the page is still imported, just unassigned.
	 */
	@Test
	fun `a mapped person whose account no longer exists is dropped, counted, and does not fail the import`() {
		val ghost = UUID.randomUUID()
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-2" to "Gone")))))

		val outcome = importerFor(tasks).perform(
			admin, team.id,
			people = mapOf("u-2" to ghost),
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		assertEquals(1, outcome.tickets, "the page itself is still imported")
		assertEquals(1, outcome.droppedAssignees)
		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertTrue(ticketRows.assigneeIds(ticket.id).isEmpty())
	}

	/**
	 * `LEAD` can name several people the way `ASSIGNEES` can, but a project has one lead —
	 * the first *resolved* candidate wins, not the first one Notion happened to list, and
	 * [NotionImportService.peopleSeen] answers for the whole column before either name has
	 * been matched to anything.
	 */
	@Test
	fun `LEAD takes the first resolved person, and peopleSeen lists everyone the column names`() {
		val rey = users.createLocalUser("rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val projects = FakeDatabase(
			"Projects",
			listOf(fakePage("Roadmap", mapOf("Qui" to notionPeople("u-9" to "Someone", "u-1" to "M. Rey")))),
		)
		val plan = listOf(
			ImportPlanEntry(
				projects.dataSourceId, ImportTarget.PROJECTS,
				mapping = ColumnMapping(columns = mapOf(ImportField.LEAD to "Qui")),
			)
		)
		val importer = importerFor(projects)

		assertEquals(
			listOf(NotionPerson("u-9", "Someone"), NotionPerson("u-1", "M. Rey")),
			importer.peopleSeen(plan),
			"peopleSeen reads LEAD too, both names, before either has been matched to anything",
		)

		importer.perform(admin, team.id, people = mapOf("u-1" to rey.id), plan = plan)

		val created = projectRows.search(teamIds = null, includeArchived = true).single { it.name == "Roadmap" }
		assertEquals(rey.id, created.leadUserId, "u-9 is listed first but nobody mapped it; u-1 is the first that resolves")
	}
}

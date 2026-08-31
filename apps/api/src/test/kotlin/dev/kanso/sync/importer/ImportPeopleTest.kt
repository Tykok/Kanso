package dev.kanso.sync.importer

import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import org.springframework.transaction.annotation.Transactional
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
		val rey = users.createLocalUser("rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		teamService.addMember(admin, team.id, rey.id, MemberRole.MEMBER)
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
	 * `TicketService.create` will not put someone outside the destination team on a
	 * ticket, and rightly — but a workspace of four hundred pages is not made safe by
	 * letting the one page naming an outsider take the other three hundred ninety-nine
	 * down with it. The mapping still gets remembered; only the assignment is dropped.
	 */
	@Test
	fun `a mapped person outside the destination team is dropped, counted, and does not fail the import`() {
		val stranger = users.createLocalUser(
			"stranger@kanso.test", "Stranger", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER,
		)
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-2" to "Stranger")))))

		val outcome = importerFor(tasks).perform(
			admin, team.id,
			people = mapOf("u-2" to stranger.id),
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
		assertEquals("u-2", users.findById(stranger.id)!!.notionPersonId, "the mapping still outlives the import")
	}
}

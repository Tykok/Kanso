package dev.kanso.sync.importer

import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.User
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
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
	 * nine down with it — the page is still imported, with the ghost dropped and the real
	 * assignee kept, which is also what shows the correspondence for the one that survives
	 * still outlives the import even though the same run dropped somebody else on the very
	 * same page.
	 */
	@Test
	fun `a mapped person whose account no longer exists is dropped, counted, and does not fail the import`() {
		val ghost = UUID.randomUUID()
		val rey = users.createLocalUser("rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val tasks = FakeDatabase(
			"Tasks",
			listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey", "u-2" to "Gone")))),
		)

		val outcome = importerFor(tasks).perform(
			admin, team.id,
			people = mapOf("u-1" to rey.id, "u-2" to ghost),
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
		assertEquals(listOf(rey.id), ticketRows.assigneeIds(ticket.id), "the ghost is dropped, the real assignee is kept")
		assertEquals(
			"u-1", users.findById(rey.id)!!.notionPersonId,
			"the mapping still outlives the import, even alongside a drop on the same page",
		)
	}

	/**
	 * `LEAD` can name several people the way `ASSIGNEES` can, but a project has one lead —
	 * the first *resolved and still-existing* candidate wins, not the first one Notion
	 * happened to list, and [NotionImportService.peopleSeen] answers for the whole column
	 * before either name has been matched to anything.
	 *
	 * `u-9` is mapped to a ghost id on purpose, not left unmapped: `firstNotNullOfOrNull`
	 * over `people[id]` alone — the shape this resolved to before `LEAD` was made to go
	 * through the same existence filter as `ASSIGNEES` — would treat a ghost id as a
	 * perfectly good non-null answer and hand it straight to `ProjectService.create`,
	 * which would raise and roll back the whole base. Only the existence check standing
	 * between resolution and `leadUserId` is what makes this page fall through to `u-1`
	 * instead.
	 */
	@Test
	fun `LEAD takes the first resolved person, and peopleSeen lists everyone the column names`() {
		val ghost = UUID.randomUUID()
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

		importer.perform(admin, team.id, people = mapOf("u-9" to ghost, "u-1" to rey.id), plan = plan)

		val created = projectRows.search(teamIds = null, includeArchived = true).single { it.name == "Roadmap" }
		assertEquals(
			rey.id, created.leadUserId,
			"u-9 is listed first and is mapped, but to an account that no longer exists; u-1 is the first that resolves and still exists",
		)
	}

	/**
	 * The rule `NotionImportService.perform` states and used to get wrong: an import that
	 * changes no correspondence must not suddenly need configurator rights.
	 *
	 * The people map is *not* the set of matches the reader just made — the people step
	 * re-sends every already-confirmed link for every row it left alone — so guarding on
	 * "the map is non-empty" refused the whole import of anyone who was not a configurator
	 * and whose workspace's people were already matched. Unreachable through the dialog,
	 * which gates both entry points on `canConfigure`, and reachable by API, which is what
	 * a closed door is supposed to mean.
	 */
	@Test
	fun `a member importing a correspondence that is already true is not refused`() {
		val rey = member("rey@kanso.test", "M. Rey")
		users.setNotionPersonId(rey.id, "u-1")
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))

		val outcome = importerFor(tasks).perform(
			rey, team.id,
			people = mapOf("u-1" to rey.id),
			plan = listOf(
				ImportPlanEntry(
					tasks.dataSourceId, ImportTarget.TICKETS,
					mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
				)
			),
		)

		assertEquals(1, outcome.tickets, "the map restates what the table already says, so nothing was configured")
		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertEquals(listOf(rey.id), ticketRows.assigneeIds(ticket.id), "and the assignment still resolves")
	}

	@Test
	fun `a member importing a correspondence that would change is still refused`() {
		// The other half of the same rule: the guard moved from "is the map empty" to "would
		// the table change", and it is still a guard.
		val rey = member("rey@kanso.test", "M. Rey")
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it", mapOf("Qui" to notionPeople("u-1" to "M. Rey")))))
		val before = rowCounts()

		assertThrows<AccessDeniedException> {
			importerFor(tasks).perform(
				rey, team.id,
				people = mapOf("u-1" to rey.id),
				plan = listOf(
					ImportPlanEntry(
						tasks.dataSourceId, ImportTarget.TICKETS,
						mapping = ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")),
					)
				),
			)
		}
		assertEquals(before, rowCounts(), "and nothing was written on the way to the refusal")
	}

	/**
	 * A plain member of [team] — the actor the two tests above are about. Added to the team
	 * so `TicketAccess` lets them import into it, and `admin` with them so the "an empty
	 * team is open to everyone" rule is not what is being tested.
	 */
	private fun member(email: String, name: String): User {
		val account = users.createLocalUser(email, name, encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		teamRows.addMember(team.id, account.id, MemberRole.MEMBER)
		return account
	}
}

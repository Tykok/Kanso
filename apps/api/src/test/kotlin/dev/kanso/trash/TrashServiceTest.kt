package dev.kanso.trash

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.DocRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.NotFoundException
import dev.kanso.service.ProjectService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trash, end to end, for the one kind that has a table on this branch.
 *
 * Asserted through the services rather than through events: the suite is
 * `@Transactional` and rolls back, so `EventPublisher`'s `afterCommit` never fires and
 * an event assertion here could only ever pass vacuously — `follow-ups.md` records why.
 */
@Transactional
class TrashServiceTest : PostgresTest() {

	@Autowired lateinit var trash: TrashService
	@Autowired lateinit var entries: TrashRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRows: TeamRepository
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var docs: DocRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "trash-${UUID.randomUUID()}@kanso.test",
		displayName = "Trash ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "T${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun newTeam(name: String = "Core") = teams.create(admin, name, key(), null)

	private fun newProject(name: String, teamId: UUID?) = projects.create(
		actor = admin,
		name = name,
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newTicket(teamId: UUID, title: String = "Old composer", projectId: UUID? = null) =
		tickets.create(
			actor = admin,
			teamId = teamId,
			title = title,
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = projectId,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

	private fun trashRow(id: UUID) = trash.load().trash.single { it.id == id }

	// --- the distinction the slice turns on -----------------------------------

	@Test
	fun `deleting a ticket moves it to the trash instead of destroying it`() {
		val team = newTeam()
		val ticket = newTicket(team.id).ticket

		tickets.delete(admin, ticket.id)

		assertFailsWith<NotFoundException>("a ticket in the trash is not live work") {
			tickets.get(ticket.id)
		}
		assertTrue(
			tickets.search(team.id, false, null, emptyList(), null, true, 200, 0).isEmpty(),
			"and it is gone from the list even with archived shown — deleted is not archived",
		)
		val row = trashRow(ticket.id)
		assertEquals(TrashKind.TICKET, row.kind)
		assertEquals(admin.id, row.deletedBy?.id)
	}

	@Test
	fun `deleted and archived are two different facts with two different tabs`() {
		val team = newTeam()
		val thrown = newTicket(team.id, "Thrown away").ticket
		val put = newTicket(team.id, "Put away").ticket
		tickets.patch(admin, put.id, TicketPatch(archived = true))

		tickets.delete(admin, thrown.id)

		val view = trash.load()
		assertEquals(listOf(thrown.id), view.trash.map { it.id })
		assertEquals(listOf(put.id), view.archives.filter { it.id == put.id }.map { it.id })
		assertTrue(view.archives.none { it.id == thrown.id }, "a deleted ticket is not archived by that")
		assertNull(view.archives.single { it.id == put.id }.daysLeft, "an archive has no countdown at all")
	}

	// --- the countdown -------------------------------------------------------

	@Test
	fun `the countdown starts at thirty days and shrinks by the day`() {
		val team = newTeam()
		val fresh = newTicket(team.id, "Fresh").ticket
		val stale = newTicket(team.id, "Two days old").ticket
		tickets.delete(admin, fresh.id)
		tickets.delete(admin, stale.id)
		entries.backdate(TrashKind.TICKET, stale.id, OffsetDateTime.now().minusDays(2))

		assertEquals(30, trashRow(fresh.id).daysLeft)
		assertEquals(28, trashRow(stale.id).daysLeft, "the drawing's own `28 j`")
	}

	@Test
	fun `the sweep empties what is past thirty days and leaves the rest`() {
		val team = newTeam()
		val expired = newTicket(team.id, "Expired").ticket
		val surviving = newTicket(team.id, "One day left").ticket
		tickets.delete(admin, expired.id)
		tickets.delete(admin, surviving.id)
		entries.backdate(TrashKind.TICKET, expired.id, OffsetDateTime.now().minusDays(31))
		entries.backdate(TrashKind.TICKET, surviving.id, OffsetDateTime.now().minusDays(29))

		assertEquals(1, trash.empty())

		assertEquals(listOf(surviving.id), trash.load().trash.map { it.id })
		assertNull(
			entries.find(TrashKind.TICKET, expired.id),
			"emptied means gone for good, entry and row alike",
		)
	}

	// --- the three exits -----------------------------------------------------

	@Test
	fun `restore names the parent it puts the ticket back into`() {
		val team = newTeam("Core")
		val product = newProject("Product", team.id)
		val ticket = newTicket(team.id, "SVG seal", product.id).ticket
		tickets.delete(admin, ticket.id)

		assertEquals("Product", trashRow(ticket.id).parent?.name, "the parent is named, not implied")

		trash.restore(admin, TrashKind.TICKET, ticket.id)

		assertEquals(product.id, tickets.get(ticket.id).ticket.projectId)
		assertTrue(trash.load().trash.isEmpty())
	}

	@Test
	fun `a ticket with no project is restored into its team, which it always has`() {
		val team = newTeam("Core")
		val ticket = newTicket(team.id).ticket
		tickets.delete(admin, ticket.id)

		assertEquals("Core", trashRow(ticket.id).parent?.name)
	}

	@Test
	fun `archiving instead leaves the trash and lands in the archives`() {
		val team = newTeam()
		val ticket = newTicket(team.id).ticket
		tickets.delete(admin, ticket.id)

		trash.archiveInstead(admin, TrashKind.TICKET, ticket.id)

		val view = trash.load()
		assertTrue(view.trash.isEmpty(), "the countdown is off: somebody made a decision instead")
		assertEquals(listOf(ticket.id), view.archives.filter { it.id == ticket.id }.map { it.id })
		assertTrue(tickets.get(ticket.id).ticket.archived)
	}

	@Test
	fun `deleting for good is the only thing that destroys the row`() {
		val team = newTeam()
		val ticket = newTicket(team.id).ticket
		tickets.delete(admin, ticket.id)

		trash.purge(admin, TrashKind.TICKET, ticket.id)

		assertTrue(trash.load().trash.isEmpty())
		assertNull(entries.find(TrashKind.TICKET, ticket.id))
		assertFailsWith<NotFoundException> { tickets.get(ticket.id) }
	}

	/**
	 * The drawing's own load-bearing sentence, read from the end this branch can reach:
	 * a document and a ticket that mention each other are two things, and destroying one
	 * takes the reference, never the other thing. Slice B's `doc_pages` will assert the
	 * other direction; `ticket_docs` and `notion_docs` are what exist here.
	 */
	@Test
	fun `purging a ticket that mentioned two documents deletes neither document`() {
		val team = newTeam()
		val first = docs.upsert("page-${UUID.randomUUID()}", "Cycle notes 22", null)
		val second = docs.upsert("page-${UUID.randomUUID()}", "Pricing draft", null)
		val ticket = newTicket(team.id).ticket
		tickets.setDocs(admin, ticket.id, listOf(first.id, second.id))
		tickets.delete(admin, ticket.id)

		assertEquals(
			2,
			trashRow(ticket.id).holds.single { it.kind == TrashHoldingKind.LINKED_DOCS }.count,
			"the pane has to say what goes with it before anyone presses the red button",
		)
		assertTrue(
			trashRow(ticket.id).holds.none { it.cascades },
			"and that none of it is deleted along with the ticket",
		)

		trash.purge(admin, TrashKind.TICKET, ticket.id)

		assertEquals(
			listOf("Cycle notes 22", "Pricing draft"),
			docs.findAllById(listOf(first.id, second.id)).map { it.title },
			"only the reference goes",
		)
	}

	// --- who may do it -------------------------------------------------------

	@Test
	fun `a stranger cannot restore, archive or purge a claimed team's ticket`() {
		val team = newTeam("Mobile")
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = newTicket(team.id).ticket
		tickets.delete(admin, ticket.id)
		val stranger = user(InstanceRole.MEMBER)

		assertFailsWith<AccessDeniedException> { trash.restore(stranger, TrashKind.TICKET, ticket.id) }
		assertFailsWith<AccessDeniedException> { trash.archiveInstead(stranger, TrashKind.TICKET, ticket.id) }
		assertFailsWith<AccessDeniedException> { trash.purge(stranger, TrashKind.TICKET, ticket.id) }
		assertEquals(listOf(ticket.id), trash.load().trash.map { it.id }, "and nothing moved")
	}

	// --- the seams ------------------------------------------------------------

	/**
	 * `trash_entries.entity_id` carries no foreign key — it points at four tables — so an
	 * entry can name a row that is not there. The two disposition paths now clean up after
	 * themselves, so this is no longer a state the application produces; the read's answer
	 * to it is still omission, because a blank row offering three exits on nothing would be
	 * worse than no row at all, and nothing else in the schema can promise it never happens.
	 */
	@Test
	fun `an entry with nothing behind it is skipped, not drawn blank`() {
		entries.add(TrashKind.TICKET, UUID.randomUUID(), admin.id)

		assertTrue(trash.load().trash.isEmpty(), "no blank row offering three exits on nothing")
	}

	/**
	 * The team disposition destroys its tickets outright, under its own consent model — a
	 * retyped team name rather than a countdown — and `V11` has no foreign key to take
	 * their entries with them. So the path forgets them itself: a countdown left running on
	 * something already destroyed is a row the sweep will one day try to purge twice, and a
	 * name nobody can restore in the meantime.
	 */
	@Test
	fun `a hard delete forgets the entry it would otherwise orphan`() {
		val team = newTeam()
		val ticket = newTicket(team.id).ticket
		tickets.delete(admin, ticket.id)

		teams.delete(
			admin,
			team.id,
			DispositionPlan(tickets = DispositionChoice.TAKE, counts = teams.contents(team.id).direct),
		)

		assertTrue(trash.load().trash.isEmpty())
		assertNull(
			entries.find(TrashKind.TICKET, ticket.id),
			"the entry went with the row it was a deletion of",
		)
	}

	@Test
	fun `the kinds are closed, and the database is what refuses a fifth`() {
		val error = assertFailsWith<Exception> { entries.addRaw("sprint", UUID.randomUUID(), admin.id) }
		assertTrue(
			error.toString().contains("trash_entries_entity_type_chk"),
			"refused by the CHECK, not by a Kotlin enum a raw INSERT would walk past: $error",
		)
	}
}

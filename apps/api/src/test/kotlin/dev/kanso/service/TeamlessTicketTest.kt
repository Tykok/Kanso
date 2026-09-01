package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.SyncState
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ticket that belongs to nobody yet, and the two writes that end that.
 *
 * The identifier is the reason this is not one nullable column: `KAN-142` is a team key
 * and a per-team counter, so a ticket outside every team has no name to print, no row in
 * any team-scoped list, and — the part with consequences — no team membership to decide
 * who may touch it. Each of those has an answer here.
 */
@Transactional
class TeamlessTicketTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var access: TicketAccess
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var encoder: PasswordEncoder

	/** Straight at the outbox: no repository read answers "is this one entity queued". */
	private fun queuedPushes(ticketId: UUID): Int = jdbc
		.sql("SELECT count(*) FROM sync_jobs WHERE entity_type = 'ticket' AND entity_id = :id")
		.param("id", ticketId).query(Int::class.java).single()

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "tl-${UUID.randomUUID()}@kanso.test",
		displayName = "Draft ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }
	private val author: User by lazy { user(InstanceRole.MEMBER) }
	private val stranger: User by lazy { user(InstanceRole.MEMBER) }

	private fun newTeam(name: String) =
		teams.create(admin, name, "T${UUID.randomUUID().toString().take(5).uppercase()}", null)

	private fun newProject(teamId: UUID?) = projects.create(
		name = "Project ${UUID.randomUUID().toString().take(4)}",
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun draft(actor: User = author, projectId: UUID? = null) = tickets.create(
		actor = actor,
		teamId = null,
		title = "A thought typed in a meeting",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = projectId,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	// --- it exists, and it has no name ---------------------------------------

	@Test
	fun `a ticket is created with no team and round-trips`() {
		val created = draft()

		assertNull(created.ticket.teamId)
		assertNull(created.ticket.number, "a number is a team's counter speaking; no team, no number")
		assertNull(created.identifier, "nothing to print, and an invented name would be one that rots")

		val reloaded = tickets.get(author, created.ticket.id)
		assertEquals(created.ticket.id, reloaded.ticket.id)
		assertNull(reloaded.ticket.teamId)
		assertNull(reloaded.identifier)
		assertEquals("A thought typed in a meeting", reloaded.ticket.title)
	}

	@Test
	fun `the author is recorded, because the team that would have decided access is missing`() {
		assertEquals(author.id, draft().ticket.createdBy)
	}

	@Test
	fun `two drafts coexist under the unique index that pairs team and number`() {
		val first = draft()
		val second = draft()

		assertNull(first.ticket.number)
		assertNull(second.ticket.number)
		assertTrue(first.ticket.id != second.ticket.id)
	}

	@Test
	fun `a draft is not pushed to the mirror, having neither identifier nor team relation`() {
		val created = draft()

		assertEquals(
			0,
			queuedPushes(created.ticket.id),
			"a Notion page with no identifier and no team is a row nothing could reconcile",
		)
	}

	/**
	 * The badge on every row reads the state, and `pending` is the word for "queued for
	 * Notion". A draft that is never enqueued must not wear it — that is a row claiming to
	 * be waiting for a push nothing will make.
	 */
	@Test
	fun `and its mirror is switched off rather than left saying it is queued`() {
		assertEquals(SyncState.DISABLED, draft().ticket.mirror.syncState)
	}

	@Test
	fun `attaching a team is what puts it on the mirror for the first time`() {
		val team = newTeam("Core")
		val created = draft()

		val attached = tickets.patch(author, created.ticket.id, TicketPatch(teamId = team.id))

		assertEquals(1, queuedPushes(created.ticket.id))
		assertEquals(
			SyncState.PENDING,
			attached.ticket.mirror.syncState,
			"the response has to carry the state the push it just queued will act on",
		)
	}

	// --- who may see and edit it ---------------------------------------------

	@Test
	fun `its author may edit it`() {
		assertTrue(access.mayEdit(author, draft().ticket))
	}

	@Test
	fun `an instance admin may edit it`() {
		assertTrue(access.mayEdit(admin, draft().ticket))
	}

	@Test
	fun `another member may not edit it, even though no team is claiming it`() {
		val ticket = draft().ticket

		assertFalse(
			access.mayEdit(stranger, ticket),
			"the open-chain clause answers for unclaimed *teams*; a draft has no chain to be silent",
		)
		assertFailsWith<AccessDeniedException> { access.require(stranger, ticket) }
	}

	@Test
	fun `another member may not even see it`() {
		val ticket = draft().ticket

		assertFailsWith<NotFoundException>("a 404, so a draft's existence is not something to probe for") {
			tickets.get(stranger, ticket.id)
		}
	}

	@Test
	fun `another member may not patch it`() {
		val ticket = draft().ticket

		assertFailsWith<AccessDeniedException> {
			tickets.patch(stranger, ticket.id, TicketPatch(title = "Mine now"))
		}
	}

	// --- the surfaces that exclude it ----------------------------------------

	@Test
	fun `a draft is in no team-scoped list, and in no unscoped one either`() {
		val team = newTeam("Core")
		val theirs = tickets.create(
			actor = admin, teamId = team.id, title = "Team work", description = null,
			status = TicketStatus.TODO, priority = TicketPriority.NONE, start = null, due = null,
			projectId = null, assigneeIds = emptyList(), docIds = emptyList(),
		)
		val mine = draft()

		val everything = tickets.list(
			teamId = null,
			includeDescendants = false,
			includeArchived = false,
			filters = TicketFilters(),
			sortBy = ViewSortBy.UPDATED,
			limit = 500,
			offset = 0,
		).map { it.ticket.id }

		assertTrue(theirs.ticket.id in everything)
		assertFalse(mine.ticket.id in everything, "one predicate serves every list; a draft is in none of them")
	}

	@Test
	fun `the drafts list is where it is, and it holds only the caller's own`() {
		val mine = draft(author)
		val theirs = draft(stranger)

		val seen = tickets.drafts(author).map { it.ticket.id }

		assertTrue(mine.ticket.id in seen)
		assertFalse(theirs.ticket.id in seen)
	}

	@Test
	fun `an instance admin sees every draft, being the fallback owner of the ones with no author left`() {
		val mine = draft(author)

		assertTrue(mine.ticket.id in tickets.drafts(admin).map { it.ticket.id })
	}

	// --- write one: attaching a team -----------------------------------------

	@Test
	fun `attaching a team gives it a number and an identifier`() {
		val team = newTeam("Core")
		val squatter = tickets.create(
			actor = admin, teamId = team.id, title = "Already here", description = null,
			status = TicketStatus.TODO, priority = TicketPriority.NONE, start = null, due = null,
			projectId = null, assigneeIds = emptyList(), docIds = emptyList(),
		)
		assertEquals("${team.key}-1", squatter.identifier)
		val ticket = draft()

		val attached = tickets.patch(author, ticket.ticket.id, TicketPatch(teamId = team.id))

		assertEquals(team.id, attached.ticket.teamId)
		assertEquals(2, attached.ticket.number, "the counter is what speaks, and it had already said 1")
		assertEquals("${team.key}-2", attached.identifier)
	}

	@Test
	fun `once attached, the ordinary team rule decides who may edit it`() {
		val team = newTeam("Core")
		val attached = tickets.patch(author, draft().ticket.id, TicketPatch(teamId = team.id))

		assertTrue(
			access.mayEdit(stranger, attached.ticket),
			"an unclaimed team is open to everyone, and authorship stops deciding the moment a team does",
		)
	}

	@Test
	fun `attaching it to a team the actor may not edit is refused`() {
		val team = newTeam("Closed")
		teams.addMember(admin, team.id, admin.id, dev.kanso.domain.MemberRole.MEMBER)
		val ticket = draft()

		assertFailsWith<AccessDeniedException> {
			tickets.patch(author, ticket.ticket.id, TicketPatch(teamId = team.id))
		}
		assertNull(tickets.get(author, ticket.ticket.id).ticket.teamId, "a refused attach writes nothing")
	}

	@Test
	fun `a ticket that has a team cannot be sent back to having none`() {
		val team = newTeam("Core")
		val attached = tickets.patch(author, draft().ticket.id, TicketPatch(teamId = team.id))

		assertFailsWith<BadRequestException> {
			tickets.patch(admin, attached.ticket.id, TicketPatch(unset = setOf("teamId")))
		}
	}

	// --- write two: attaching a project, and the team that follows it --------

	@Test
	fun `attaching a project attaches the team the project belongs to`() {
		val team = newTeam("Core")
		val project = newProject(team.id)
		val ticket = draft()

		val attached = tickets.patch(author, ticket.ticket.id, TicketPatch(projectId = project.id))

		assertEquals(project.id, attached.ticket.projectId)
		assertEquals(team.id, attached.ticket.teamId, "a ticket's project belongs to its team; the team follows")
		assertNotNull(attached.ticket.number)
		assertEquals("${team.key}-1", attached.identifier)
	}

	@Test
	fun `attaching a project cannot smuggle the ticket into a team the actor may not edit`() {
		val team = newTeam("Closed")
		teams.addMember(admin, team.id, admin.id, dev.kanso.domain.MemberRole.MEMBER)
		val project = newProject(team.id)
		val ticket = draft()

		assertFailsWith<AccessDeniedException> {
			tickets.patch(author, ticket.ticket.id, TicketPatch(projectId = project.id))
		}
		val unchanged = tickets.get(author, ticket.ticket.id).ticket
		assertNull(unchanged.teamId, "a refused attach writes neither the project nor the team")
		assertNull(unchanged.projectId)
	}

	@Test
	fun `a transverse project leaves the ticket without a team, having none of its own to lend`() {
		val project = newProject(null)
		val ticket = draft()

		val attached = tickets.patch(author, ticket.ticket.id, TicketPatch(projectId = project.id))

		assertEquals(project.id, attached.ticket.projectId)
		assertNull(attached.ticket.teamId, "transverse belongs everywhere, which is not the same as somewhere")
		assertNull(attached.identifier)
	}

	@Test
	fun `naming a team and a project of another team in one patch is still refused`() {
		val core = newTeam("Core")
		val growth = newTeam("Growth")
		val growthProject = newProject(growth.id)
		val ticket = draft()

		val failure = assertFailsWith<BadRequestException> {
			tickets.patch(admin, ticket.ticket.id, TicketPatch(teamId = core.id, projectId = growthProject.id))
		}
		assertTrue(failure.message!!.contains(growth.id.toString()), failure.message!!)
		assertNull(tickets.get(admin, ticket.ticket.id).ticket.teamId, "a refused patch must not partially apply")
	}

	@Test
	fun `creating a draft straight into a project takes that project's team with it`() {
		val team = newTeam("Core")
		val project = newProject(team.id)

		val created = draft(projectId = project.id)

		assertEquals(team.id, created.ticket.teamId)
		assertEquals("${team.key}-1", created.identifier)
	}

	@Test
	fun `creating a draft into a transverse project leaves it team-less`() {
		val created = draft(projectId = newProject(null).id)

		assertNull(created.ticket.teamId)
		assertNull(created.identifier)
	}
}

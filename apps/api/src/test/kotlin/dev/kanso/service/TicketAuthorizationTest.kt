package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Transactional
class TicketAuthorizationTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "taz-${UUID.randomUUID()}@kanso.test",
		displayName = "Authz ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "K${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun ticketIn(teamId: UUID) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = "Work",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	private fun day(d: Int) = dev.kanso.domain.KansoInstant(
		java.time.OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, java.time.ZoneOffset.UTC),
		false,
	)

	private fun dated(teamId: UUID, start: Int, due: Int) = tickets.create(
		actor = admin,
		teamId = teamId,
		title = "Dated",
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = day(start),
		due = day(due),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket

	@Test
	fun `a stranger cannot file a ticket on a claimed team's board, and the message names it`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.create(
				actor = stranger,
				teamId = team.id,
				title = "Filed anyway",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a member of an ancestor may create a ticket in a descendant team`() {
		val parent = teams.create(admin, "Product", key(), null)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		val parentMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(parent.id, parentMember.id, MemberRole.MEMBER)

		val created = tickets.create(
			actor = parentMember,
			teamId = child.id,
			title = "Filed from above",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		assertEquals(child.id, created.ticket.teamId)
	}

	@Test
	fun `nobody is refused creating in a team whose whole chain is unclaimed`() {
		val root = teams.create(admin, "Org", key(), null)
		val child = teams.create(admin, "Product", key(), root.id)
		val stranger = user(InstanceRole.MEMBER)

		val created = tickets.create(
			actor = stranger,
			teamId = child.id,
			title = "Unclaimed board, open door",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		assertEquals(child.id, created.ticket.teamId)
	}

	@Test
	fun `a stranger cannot patch a claimed team's ticket`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(team.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.patch(stranger, ticket.id, TicketPatch(title = "Hijacked"))
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a ticket cannot be moved into a team the actor is not in`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(mine.id)

		// The escape hatch: without the destination check, this moves someone else's
		// board into reach and the rule is defeated in two requests.
		assertFailsWith<AccessDeniedException> {
			tickets.patch(member, ticket.id, TicketPatch(teamId = theirs.id))
		}
	}

	@Test
	fun `linking asks for the successor, not the predecessor`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		// Declaring that my ticket waits on theirs commits nobody but me.
		schedule.link(member, predecessorId = ticketIn(theirs.id).id, successorId = ticketIn(mine.id).id)

		// The mirror case imposes a constraint on a ticket that is not mine.
		assertFailsWith<AccessDeniedException> {
			schedule.link(member, predecessorId = ticketIn(mine.id).id, successorId = ticketIn(theirs.id).id)
		}
	}

	@Test
	fun `a stranger cannot delete a claimed team's ticket`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(team.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.delete(stranger, ticket.id)
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a stranger cannot unlink an arrow whose successor is not theirs`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val predecessor = ticketIn(team.id)
		val successor = ticketIn(team.id)
		schedule.link(admin, predecessor.id, successor.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			schedule.unlink(stranger, predecessor.id, successor.id)
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a stranger cannot reassign a claimed team's ticket`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(team.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.setAssignees(stranger, ticket.id, emptyList())
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `a stranger cannot attach docs to a claimed team's ticket`() {
		val team = teams.create(admin, "Mobile", key(), null)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val ticket = ticketIn(team.id)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> {
			tickets.setDocs(stranger, ticket.id, emptyList())
		}
		assertTrue(error.message!!.contains("Mobile"))
	}

	@Test
	fun `the cascade still pushes tickets in teams the actor is not in`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		val predecessor = dated(mine.id, start = 1, due = 5)
		val successor = dated(theirs.id, start = 6, due = 10)
		schedule.link(admin, predecessor.id, successor.id)

		tickets.patch(member, predecessor.id, TicketPatch(due = day(20)))

		assertEquals(
			day(20).at,
			tickets.get(successor.id).ticket.start!!.at,
			"permission governs the gesture; the graph governs its consequences",
		)
	}
}

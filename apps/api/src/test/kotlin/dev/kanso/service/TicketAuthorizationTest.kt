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

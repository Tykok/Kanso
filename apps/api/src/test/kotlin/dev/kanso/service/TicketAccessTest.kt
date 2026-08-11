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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Transactional
class TicketAccessTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var access: TicketAccess
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "ta-${UUID.randomUUID()}@kanso.test",
		displayName = "Access ${role.wire}",
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

	@Test
	fun `an admin may edit a ticket in a team they are not in`() {
		val team = teams.create(admin, "Alone", key(), null)
		val ticket = ticketIn(team.id)
		teamRepo.addMember(team.id, user(InstanceRole.MEMBER).id, MemberRole.MEMBER)

		assertTrue(access.mayEdit(admin, ticket))
	}

	@Test
	fun `a team with no members is open to everyone`() {
		val team = teams.create(admin, "Unclaimed", key(), null)
		val ticket = ticketIn(team.id)

		assertTrue(
			access.mayEdit(user(InstanceRole.MEMBER), ticket),
			"every instance deploying this has an empty team_members; locking them all out is not an upgrade",
		)
	}

	@Test
	fun `a member of the ticket's own team may edit it`() {
		val team = teams.create(admin, "Owning", key(), null)
		val ticket = ticketIn(team.id)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)

		assertTrue(access.mayEdit(member, ticket))
	}

	@Test
	fun `membership is inherited downwards, never upwards`() {
		val parent = teams.create(admin, "Product", key(), null)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		val parentMember = user(InstanceRole.MEMBER)
		val childMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(parent.id, parentMember.id, MemberRole.MEMBER)
		teamRepo.addMember(child.id, childMember.id, MemberRole.MEMBER)

		assertTrue(
			access.mayEdit(parentMember, ticketIn(child.id)),
			"a member of Product may move work in Product / Mobile",
		)
		assertFalse(
			access.mayEdit(childMember, ticketIn(parent.id)),
			"the other direction is an escalation: joining the smallest team would grant the largest",
		)
	}

	@Test
	fun `a refusal names the team, not its uuid`() {
		val team = teams.create(admin, "Mobile", key(), null)
		val ticket = ticketIn(team.id)
		teamRepo.addMember(team.id, admin.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)

		val error = assertFailsWith<AccessDeniedException> { access.require(stranger, ticket) }

		assertTrue(error.message!!.contains("Mobile"), "a UUID in a dialog footer is not actionable")
		assertFalse(error.message!!.contains(team.id.toString()))
	}

	@Test
	fun `editableTeams answers the same question in bulk`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		assertEquals(setOf(mine.id), access.editableTeams(member, setOf(mine.id, theirs.id)))
	}
}

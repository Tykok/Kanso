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

	@Test
	fun `an admin may edit a ticket in a team they are not in`() {
		val team = teams.create(admin, "Alone", key(), null)
		val ticket = ticketIn(team.id)
		teamRepo.addMember(team.id, user(InstanceRole.MEMBER).id, MemberRole.MEMBER)

		assertTrue(access.mayEdit(admin, ticket))
	}

	@Test
	fun `a root team with no members is open to everyone`() {
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

	@Test
	fun `editableTeams short-circuits to everything for an admin`() {
		val first = teams.create(admin, "First", key(), null)
		val second = teams.create(admin, "Second", key(), null)
		val someone = user(InstanceRole.MEMBER)
		teamRepo.addMember(first.id, someone.id, MemberRole.MEMBER)
		teamRepo.addMember(second.id, someone.id, MemberRole.MEMBER)

		assertEquals(
			setOf(first.id, second.id),
			access.editableTeams(admin, setOf(first.id, second.id)),
			"an admin edits both teams whether or not they belong to either",
		)
	}

	@Test
	fun `an empty child under a populated parent is closed to strangers and open to the parent's members`() {
		val parent = teams.create(admin, "Product", key(), null)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		teamRepo.addMember(parent.id, admin.id, MemberRole.MEMBER)
		val parentMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(parent.id, parentMember.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)
		val ticket = ticketIn(child.id)

		assertTrue(
			access.mayEdit(parentMember, ticket),
			"the child is governed from the instant it exists, by whoever governs the parent",
		)
		assertFalse(
			access.mayEdit(stranger, ticket),
			"a populated ancestor closes the child even though the child itself has no members",
		)
	}

	@Test
	fun `an empty child under an empty parent resolves to a populated grandparent`() {
		val grandparent = teams.create(admin, "Org", key(), null)
		val parent = teams.create(admin, "Product", key(), grandparent.id)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		val grandparentMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(grandparent.id, grandparentMember.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)
		val ticket = ticketIn(child.id)

		assertTrue(
			access.mayEdit(grandparentMember, ticket),
			"the walk climbs past the empty parent to the grandparent that claimed the whole chain",
		)
		assertFalse(
			access.mayEdit(stranger, ticket),
			"the grandparent's claim closes the chain to everyone else",
		)
	}

	@Test
	fun `a fully empty chain, root included, is open to everyone`() {
		val root = teams.create(admin, "Org", key(), null)
		val child = teams.create(admin, "Product", key(), root.id)
		val grandchild = teams.create(admin, "Mobile", key(), child.id)
		val stranger = user(InstanceRole.MEMBER)

		assertTrue(
			access.mayEdit(stranger, ticketIn(grandchild.id)),
			"nobody in the chain has claimed the work, root included, so it stays open",
		)
	}

	@Test
	fun `a populated team is unaffected, and a member of a descendant is still refused on it`() {
		val parent = teams.create(admin, "Product", key(), null)
		val child = teams.create(admin, "Mobile", key(), parent.id)
		teamRepo.addMember(parent.id, admin.id, MemberRole.MEMBER)
		val childMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(child.id, childMember.id, MemberRole.MEMBER)
		val stranger = user(InstanceRole.MEMBER)
		val ticket = ticketIn(parent.id)

		assertFalse(access.mayEdit(stranger, ticket), "a populated team never opens for lack of ancestors")
		assertFalse(
			access.mayEdit(childMember, ticket),
			"a member of a descendant still cannot reach upwards into the parent",
		)
	}

	@Test
	fun `teamsWithMembers returns exactly the teams holding a row, and nothing for an empty input`() {
		val claimed = teams.create(admin, "Claimed", key(), null)
		val unclaimed = teams.create(admin, "Unclaimed", key(), null)
		teamRepo.addMember(claimed.id, admin.id, MemberRole.MEMBER)

		assertEquals(setOf(claimed.id), teamRepo.teamsWithMembers(setOf(claimed.id, unclaimed.id)))
		assertEquals(emptySet(), teamRepo.teamsWithMembers(emptySet()))
	}

	@Test
	fun `editableTeams agrees with mayEdit across every chain shape`() {
		val populatedParent = teams.create(admin, "Product", key(), null)
		val emptyChild = teams.create(admin, "Mobile", key(), populatedParent.id)
		teamRepo.addMember(populatedParent.id, admin.id, MemberRole.MEMBER)

		val populatedGrandparent = teams.create(admin, "Org", key(), null)
		val emptyParent = teams.create(admin, "Design", key(), populatedGrandparent.id)
		val emptyGrandchild = teams.create(admin, "iOS", key(), emptyParent.id)
		teamRepo.addMember(populatedGrandparent.id, admin.id, MemberRole.MEMBER)

		val emptyRoot = teams.create(admin, "Unclaimed", key(), null)
		val emptyChain = teams.create(admin, "Nested", key(), emptyRoot.id)

		val populatedRoot = teams.create(admin, "Owning", key(), null)
		val rootMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(populatedRoot.id, rootMember.id, MemberRole.MEMBER)

		val productMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(populatedParent.id, productMember.id, MemberRole.MEMBER)
		val orgMember = user(InstanceRole.MEMBER)
		teamRepo.addMember(populatedGrandparent.id, orgMember.id, MemberRole.MEMBER)

		val allTeamIds = setOf(
			emptyChild.id,
			emptyGrandchild.id,
			emptyChain.id,
			populatedRoot.id,
		)
		val ticketsByTeam = allTeamIds.associateWith { ticketIn(it) }

		for (actor in listOf(admin, productMember, orgMember, rootMember, user(InstanceRole.MEMBER))) {
			val editable = access.editableTeams(actor, allTeamIds)
			for (teamId in allTeamIds) {
				assertEquals(
					access.mayEdit(actor, ticketsByTeam.getValue(teamId)),
					teamId in editable,
					"editableTeams and mayEdit must agree on $teamId for ${actor.displayName}",
				)
			}
		}
	}
}

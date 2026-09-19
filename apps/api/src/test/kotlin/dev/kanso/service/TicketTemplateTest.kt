package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.Team
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

/**
 * The two levels, and who may write at each.
 *
 * The guard is not new code and these tests are here to prove it: `requireTeam` already lets
 * an instance admin through and already refuses a viewer before it asks about membership, so
 * what is being asserted is that this service reached for the existing boundary rather than
 * inventing a parallel one.
 */
@Transactional
class TicketTemplateTest : PostgresTest() {

	@Autowired lateinit var templates: TicketTemplateService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "tpl-${UUID.randomUUID()}@kanso.test",
		displayName = "Template ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "T${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun team(owner: User = admin): Team = teams.create(admin, "Templates", key(), null)
		.also { teamRepo.addMember(it.id, owner.id, MemberRole.MEMBER) }

	private fun body() = TemplateBody(description = "## What happens\n")

	private fun name() = "Spike ${UUID.randomUUID()}"

	@Test
	fun `an instance admin may write at instance level`() {
		val made = templates.create(admin, null, name(), null, body(), listOf("Engineering"))
		assertEquals(null, made.teamId)
		assertEquals(listOf("Engineering"), made.categories)
	}

	@Test
	fun `a member may not write at instance level`() {
		val member = user(InstanceRole.MEMBER)
		team(member)
		assertFailsWith<AccessDeniedException> {
			templates.create(member, null, name(), null, body(), emptyList())
		}
	}

	@Test
	fun `a member may write in their own team`() {
		val member = user(InstanceRole.MEMBER)
		val team = team(member)
		val made = templates.create(member, team.id, "Support", null, body(), emptyList())
		assertEquals(team.id, made.teamId)
	}

	@Test
	fun `a member may not write in a team that is not theirs`() {
		val member = user(InstanceRole.MEMBER)
		team(member)
		val other = team(admin)
		assertFailsWith<AccessDeniedException> {
			templates.create(member, other.id, "Trespass", null, body(), emptyList())
		}
	}

	@Test
	fun `a viewer is refused at both levels`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = team(viewer)
		assertFailsWith<AccessDeniedException> {
			templates.create(viewer, null, name(), null, body(), emptyList())
		}
		assertFailsWith<AccessDeniedException> {
			templates.create(viewer, team.id, "Read only", null, body(), emptyList())
		}
	}

	@Test
	fun `a name may repeat across levels but not inside one`() {
		val team = team()
		val shared = name()
		templates.create(admin, null, shared, null, body(), emptyList())
		// The same word one level down is a team deliberately writing its own.
		templates.create(admin, team.id, shared, null, body(), emptyList())

		assertFailsWith<ConflictException> {
			templates.create(admin, team.id, shared, null, body(), emptyList())
		}
	}

	@Test
	fun `a team sees the instance catalogue and its own, and not another team's`() {
		val mine = team()
		val theirs = team()
		templates.create(admin, mine.id, "Mine", null, body(), emptyList())
		templates.create(admin, theirs.id, "Theirs", null, body(), emptyList())

		val names = templates.list(mine.id).map { it.name }
		assertTrue(names.contains("Bug"), "the shipped catalogue is always offered")
		assertTrue(names.contains("Mine"))
		assertTrue(!names.contains("Theirs"))
	}

	@Test
	fun `Kanso's templates are offered first`() {
		val team = team()
		templates.create(admin, team.id, "Aardvark", null, body(), emptyList())

		val offered = templates.list(team.id)
		// Sorted by level before name, so a team template starting with A does not lead the
		// picker ahead of the catalogue every instance has.
		assertEquals(null, offered.first().teamId)
	}

	@Test
	fun `reading is open to anybody who is not writing`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = team(viewer)
		// No actor argument at all: a template's name is a word a screen prints, like a label or
		// a status, and a reader who could not have it would see an empty picker.
		assertTrue(templates.list(team.id).isNotEmpty())
	}
}

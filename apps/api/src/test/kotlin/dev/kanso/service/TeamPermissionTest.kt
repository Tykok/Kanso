package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shape of the organisation is an admin decision. The daily work — projects,
 * tickets — deliberately is not, and nothing here asserts otherwise.
 */
@Transactional
class TeamPermissionTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "user-${UUID.randomUUID()}@kanso.test",
		displayName = "Test ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "T${UUID.randomUUID().toString().take(5).uppercase()}"

	@Test
	fun `a member cannot create a team`() {
		val member = user(InstanceRole.MEMBER)
		assertFailsWith<AccessDeniedException> { teams.create(member, "Core", key(), null) }
	}

	@Test
	fun `an admin creates and renames a team`() {
		val admin = user(InstanceRole.ADMIN)

		val created = teams.create(admin, "Core", key(), null)
		val renamed = teams.update(admin, created.id, "Core Platform", created.key, null)

		assertEquals("Core Platform", renamed.name)
	}

	@Test
	fun `the owner may configure teams too`() {
		val owner = user(InstanceRole.OWNER)
		assertEquals("Owned", teams.create(owner, "Owned", key(), null).name)
	}

	@Test
	fun `a member cannot rename a team an admin created`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val team = teams.create(admin, "Growth", key(), null)

		assertFailsWith<AccessDeniedException> {
			teams.update(member, team.id, "Hijacked", team.key, null)
		}
	}

	@Test
	fun `only a configurator changes the membership list`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val team = teams.create(admin, "Mobile", key(), null)

		assertFailsWith<AccessDeniedException> {
			teams.addMember(member, team.id, member.id, MemberRole.MEMBER)
		}
		assertEquals(1, teams.addMember(admin, team.id, member.id, MemberRole.MEMBER).size)

		assertFailsWith<AccessDeniedException> { teams.removeMember(member, team.id, member.id) }
		teams.removeMember(admin, team.id, member.id)
		assertEquals(0, teams.members(team.id).size)
	}
}

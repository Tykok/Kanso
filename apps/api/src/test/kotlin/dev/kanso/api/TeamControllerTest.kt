package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `TeamController` reads its actor off the security context through `CurrentUser`,
 * not a parameter — every other test in this suite calls the service layer directly
 * and never needs one standing. `editable` is the one field the controller computes
 * itself from that identity, so this is the one place that has to actually
 * authenticate as somebody rather than hand an actor in.
 */
@Transactional
class TeamControllerTest : PostgresTest() {

	@Autowired lateinit var controller: TeamController
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "tc-${UUID.randomUUID()}@kanso.test",
		displayName = "Team controller ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "K${UUID.randomUUID().toString().take(4).uppercase()}"

	/** Stands in for the auth filter, which has no servlet request here to run inside. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	@Test
	fun `editable is true and false in one response for a non-admin actor, and all-true for an admin`() {
		val mine = teams.create(admin, "Mine", key(), null)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val member = user(InstanceRole.MEMBER)
		teamRepo.addMember(mine.id, member.id, MemberRole.MEMBER)
		teamRepo.addMember(theirs.id, admin.id, MemberRole.MEMBER)

		actAs(member)
		val asMember = controller.list(includeArchived = false).associateBy { it.id }
		assertTrue(asMember.getValue(mine.id).editable, "a member of the team's own board may create there")
		assertFalse(asMember.getValue(theirs.id).editable, "claimed by someone else, so the select must not offer it")

		actAs(admin)
		val asAdmin = controller.list(includeArchived = false).associateBy { it.id }
		assertTrue(asAdmin.getValue(mine.id).editable, "an admin edits everywhere")
		assertTrue(asAdmin.getValue(theirs.id).editable, "an admin edits everywhere")
	}
}

package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
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
import kotlin.test.assertEquals

/**
 * The endpoints a team's words are edited through, and the payload that carries them —
 * `KAN-28`.
 *
 * The second half is the one worth a test of its own: `TeamResponse.statuses` is what
 * spares every screen a second request to learn what the words are, and a payload that
 * quietly stopped carrying them would leave the interface printing keys.
 */
@Transactional
class TeamStatusRoutesTest : PostgresTest() {

	@Autowired lateinit var statuses: TeamStatusController
	@Autowired lateinit var teamRoutes: TeamController
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private lateinit var owner: User

	private fun signIn(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	private fun ownerUser() = users.createLocalUser(
		email = "routes-${UUID.randomUUID()}@kanso.test",
		displayName = "Owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.OWNER,
	).also { owner = it; signIn(it) }

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	@Test
	fun `the list answers the six, in order, with their categories`() {
		val actor = ownerUser()
		val team = teams.create(actor, "Support ${UUID.randomUUID()}", null, null)

		val answered = statuses.list(team.id)

		assertEquals(
			listOf("backlog", "todo", "in_progress", "in_review", "done", "canceled"),
			answered.map { it.key },
		)
		assertEquals("started", answered.single { it.key == "in_review" }.category)
		assertEquals(listOf(0, 1, 2, 3, 4, 5), answered.map { it.position })
	}

	@Test
	fun `a rename answers the row it wrote`() {
		val actor = ownerUser()
		val team = teams.create(actor, "Support ${UUID.randomUUID()}", null, null)

		val answered = statuses.rename(team.id, "done", TeamStatusRenameRequest("Livré"))

		assertEquals("done", answered.key)
		assertEquals("Livré", answered.label)
	}

	@Test
	fun `a reorder answers the whole list in its new order`() {
		val actor = ownerUser()
		val team = teams.create(actor, "Support ${UUID.randomUUID()}", null, null)

		val answered = statuses.reorder(
			team.id,
			TeamStatusOrderRequest(listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled")),
		)

		assertEquals(listOf("todo", "backlog"), answered.take(2).map { it.key })
		assertEquals(listOf(0, 1, 2, 3, 4, 5), answered.map { it.position })
	}

	@Test
	fun `a team carries its words, so no screen needs a second request to learn them`() {
		val actor = ownerUser()
		val team = teams.create(actor, "Support ${UUID.randomUUID()}", null, null)
		statuses.rename(team.id, "done", TeamStatusRenameRequest("Livré"))

		val listed = teamRoutes.list(includeArchived = false).single { it.id == team.id }

		assertEquals("Livré", listed.statuses.single { it.key == "done" }.label)
		// Required and not optional, for the reason `TicketResponse.customFields` is: a key
		// that appears later is a key that breaks a reader, and this one appears now.
		assertEquals(6, listed.statuses.size)
	}

	@Test
	fun `one team's rename does not reach another's words`() {
		val actor = ownerUser()
		val mine = teams.create(actor, "Mine ${UUID.randomUUID()}", null, null)
		val theirs = teams.create(actor, "Theirs ${UUID.randomUUID()}", null, null)

		statuses.rename(mine.id, "done", TeamStatusRenameRequest("Livré"))

		assertEquals("Done", statuses.list(theirs.id).single { it.key == "done" }.label)
	}
}

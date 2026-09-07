package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
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
 * A team's words, and their order — `KAN-28`.
 *
 * Adding and removing are `KAN-90`: `Ticket.status` is an enum across the domain, and the
 * six keys staying fixed is what lets this ticket leave twenty semantic sites alone.
 * Everything here therefore writes `label` or `position` and never the set of keys.
 */
@Transactional
class TeamStatusServiceTest : PostgresTest() {

	@Autowired lateinit var service: TeamStatusService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "statuses-${UUID.randomUUID()}@kanso.test",
		displayName = "Status ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun team(actor: dev.kanso.domain.User) =
		teams.create(actor, "Support ${UUID.randomUUID()}", null, null)

	@Test
	fun `a rename writes the word and leaves the key`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		service.rename(actor, team.id, "done", "Livré")

		val row = service.list(actor, team.id).single { it.key == "done" }
		assertEquals("Livré", row.label)
		// Saved views, the filter grammar and every bookmarked URL hold keys. A rename
		// that moved one would empty a saved view somebody built.
		assertEquals("done", row.key)
	}

	@Test
	fun `a second spelling of one word is refused, by the word`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		assertEquals(
			"""This team already has a status called "in progress"""",
			assertFailsWith<ConflictException> {
				service.rename(actor, team.id, "todo", "in progress")
			}.message,
		)
	}

	@Test
	fun `renaming a status to what it already reads is not a conflict with itself`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		service.rename(actor, team.id, "done", "Done")

		assertEquals("Done", service.list(actor, team.id).single { it.key == "done" }.label)
	}

	@Test
	fun `a reorder writes the order the team reads in`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		service.reorder(actor, team.id, listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"))

		assertEquals(
			listOf("todo", "backlog"),
			service.list(actor, team.id).take(2).map { it.key },
		)
	}

	@Test
	fun `a reorder that does not name every status is refused rather than merged`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		// A client sending five keys for a six-status team has not seen the sixth, and
		// merging would put it somewhere nobody chose.
		assertEquals(
			"A reorder has to name every status of the team, exactly once",
			assertFailsWith<BadRequestException> {
				service.reorder(actor, team.id, listOf("todo", "backlog", "in_progress", "in_review", "done"))
			}.message,
		)
	}

	@Test
	fun `a reorder naming one status twice is refused`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		assertFailsWith<BadRequestException> {
			service.reorder(actor, team.id, listOf("todo", "todo", "backlog", "in_progress", "in_review", "done"))
		}
	}

	@Test
	fun `renaming a status the team does not have is a malformed request`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		assertFailsWith<BadRequestException> { service.rename(actor, team.id, "shipped", "Shipped") }
	}

	@Test
	fun `a member reads the words and cannot change them`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)
		val member = user(InstanceRole.MEMBER)

		// Reading is open to anybody who can see the team: a status is a word every screen
		// prints. Changing the vocabulary is the team's shape, like renaming the team.
		assertEquals(6, service.list(member, team.id).size)
		assertFailsWith<AccessDeniedException> { service.rename(member, team.id, "done", "Livré") }
		assertFailsWith<AccessDeniedException> {
			service.reorder(member, team.id, listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"))
		}
	}
}

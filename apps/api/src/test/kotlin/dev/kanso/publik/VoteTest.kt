package dev.kanso.publik

import dev.kanso.PostgresTest
import dev.kanso.db.Votes
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TicketService
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Open voting: no account, one vote per visitor per day, and no address stored. */
@Transactional
class VoteTest : PostgresTest() {

	@Autowired lateinit var votes: VoteService
	@Autowired lateinit var voterKeys: VoterKeys
	@Autowired lateinit var publication: PublicationService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var users: UserRepository

	private val owner: User by lazy {
		users.createLocalUser(
			email = "votes-${UUID.randomUUID()}@kanso.test",
			displayName = "Vote owner",
			passwordHash = "not-a-real-hash",
			role = InstanceRole.OWNER,
		)
	}

	private fun publishedTicket(): Pair<String, Int> {
		val team = teams.insert(
			name = "Votes ${UUID.randomUUID()}",
			key = "V${UUID.randomUUID().toString().take(4).uppercase()}",
			parentTeamId = null,
		)
		val created = tickets.create(
			actor = owner,
			teamId = team.id,
			title = "Read-only public API",
			description = null,
			status = "backlog",
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		publication.publish(owner, created.ticket.id, public = true)
		return team.key to created.ticket.number!!
	}

	@Test
	fun `two visitors count twice, and one visitor voting twice counts once`() {
		val (teamKey, number) = publishedTicket()

		assertEquals(1, votes.vote(teamKey, number, "visitor-a").votes)
		assertEquals(2, votes.vote(teamKey, number, "visitor-b").votes)

		val again = votes.vote(teamKey, number, "visitor-a")
		assertEquals(2, again.votes, "a second click is a no-op, not a second vote")
		assertTrue(again.voted, "and it still answers 'you have voted', not an error")
	}

	@Test
	fun `what is stored is a keyed hash, not the address it was derived from`() {
		val (teamKey, number) = publishedTicket()
		val address = "203.0.113.7"

		votes.vote(teamKey, number, voterKeys.keyFor(address))

		val stored = Votes.selectAll().map { it[Votes.voterKey] }
		assertEquals(1, stored.size)
		assertFalse(stored.single().contains(address), "the address itself must not reach the column")
		assertFalse(stored.single().contains("203"), "nor any octet of it")
	}

	@Test
	fun `the same visitor hashes the same way twice, and two visitors do not collide`() {
		// Determinism is what makes the second click a no-op at all: a fresh salt per
		// request would turn every reload into a new voter.
		assertEquals(voterKeys.keyFor("198.51.100.4"), voterKeys.keyFor("198.51.100.4"))
		assertNotEquals(voterKeys.keyFor("198.51.100.4"), voterKeys.keyFor("198.51.100.5"))
	}
}

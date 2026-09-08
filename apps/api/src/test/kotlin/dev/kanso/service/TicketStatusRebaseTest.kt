package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ticket crossing into another team takes that team's word for where it is — `KAN-90`.
 *
 * Not a nicety. `tickets_status_fk` is a composite key onto `(team_id, key)`, so a move
 * that carried the source team's status would be refused by the database; and since
 * `TicketService.patch` validates the status against the destination, an unrebased move
 * would answer 400 for a gesture `KAN-9` made ordinary. The question is only *which* of
 * the destination's statuses, and the answer is the narrowest true one available.
 */
@Transactional
class TicketStatusRebaseTest : PostgresTest() {

	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var ticketRows: TicketRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var statuses: TeamStatusService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var activity: ActivityRepository
	@Autowired lateinit var objectMapper: ObjectMapper

	private val owner by lazy {
		users.createLocalUser(
			email = "rebase-${UUID.randomUUID()}@kanso.test",
			displayName = "Rebase owner",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.OWNER,
		)
	}

	private fun team(actor: User) = teams.create(actor, "Team ${UUID.randomUUID()}", null, null)

	private fun ticket(teamId: UUID, status: String) = tickets.create(
		actor = owner,
		teamId = teamId,
		title = "A ticket in $status",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	private fun moveTo(id: UUID, teamId: UUID) = tickets.patch(owner, id, TicketPatch(teamId = teamId))

	@Test
	fun `the same key, when the destination has it`() {
		val from = team(owner)
		val to = team(owner)
		val id = ticket(from.id, "in_review")

		moveTo(id, to.id)

		// Both teams were seeded with the six, which is the overwhelmingly common case: a
		// move between two teams that renamed nothing must not shuffle a ticket's status.
		assertEquals("in_review", ticketRows.findById(id)!!.status)
	}

	@Test
	fun `a rename is not a different status, because a rename never moves a key`() {
		val from = team(owner)
		val to = team(owner)
		statuses.rename(owner, to.id, "in_review", "Relecture")
		val id = ticket(from.id, "in_review")

		moveTo(id, to.id)

		assertEquals("in_review", ticketRows.findById(id)!!.status)
	}

	@Test
	fun `the destination's first status of the same category, when it has no such key`() {
		val from = team(owner)
		val to = team(owner)
		// The destination stops having `in_review` at all, but still has started work.
		statuses.remove(owner, to.id, "in_review", into = null)
		val id = ticket(from.id, "in_review")

		moveTo(id, to.id)

		// `in_progress`, because it is the destination's first STARTED status by position.
		// The meaning is what survives a move: the ticket is still work in flight, and
		// putting it back in the backlog would be the destination team inventing a
		// decision nobody made.
		assertEquals("in_progress", ticketRows.findById(id)!!.status)
	}

	@Test
	fun `the destination's very first status, when it has nothing of that category`() {
		val from = team(owner)
		val to = team(owner)
		for (key in listOf("in_progress", "in_review")) statuses.remove(owner, to.id, key, into = null)
		val id = ticket(from.id, "in_progress")

		moveTo(id, to.id)

		// Last resort, and the honest one: the destination has no way to say "in flight",
		// so the ticket lands where that team's work starts and somebody moves it. A
		// refusal here would make `KAN-9`'s ordinary gesture fail on a team's own
		// configuration, which is worse than a status a reader can see and correct.
		assertEquals("backlog", ticketRows.findById(id)!!.status)
	}

	@Test
	fun `an invented word is reached by its category, not by its spelling`() {
		val from = team(owner)
		val to = team(owner)
		statuses.remove(owner, to.id, "in_review", into = null)
		statuses.remove(owner, to.id, "in_progress", into = null)
		statuses.add(owner, to.id, "En chantier", StatusCategory.STARTED)
		val id = ticket(from.id, "in_review")

		moveTo(id, to.id)

		// The whole ticket in one case: a word `DefaultStatus` has never heard of, reached
		// because the ticket's *meaning* crossed the boundary rather than its spelling.
		assertEquals("en_chantier", ticketRows.findById(id)!!.status)
	}

	@Test
	fun `a rebase writes the line, because the ticket did change status`() {
		val from = team(owner)
		val to = team(owner)
		statuses.remove(owner, to.id, "in_review", into = null)
		val id = ticket(from.id, "in_review")

		moveTo(id, to.id)

		val line = activity.forEntity(ActivityEntity.TICKET, id, limit = 50)
			.single { it.kind == ActivityKind.STATUS_CHANGED }
		assertEquals("in_review", objectMapper.readTree(line.payload).path("from").asText())
		assertEquals("in_progress", objectMapper.readTree(line.payload).path("to").asText())
	}

	@Test
	fun `a status named in the same patch as the move is the destination's to validate`() {
		val from = team(owner)
		val to = team(owner)
		val id = ticket(from.id, "todo")

		// Not rebased: somebody said where it goes, and the rebase is only for the status
		// nobody spoke about. It is still validated against the destination — the patch
		// would be refused if `to` had no `done`.
		tickets.patch(owner, id, TicketPatch(teamId = to.id, status = "done"))

		assertEquals("done", ticketRows.findById(id)!!.status)
	}
}

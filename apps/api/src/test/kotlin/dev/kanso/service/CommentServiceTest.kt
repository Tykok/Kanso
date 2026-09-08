package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
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
class CommentServiceTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var comments: CommentService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(email: String, name: String, role: InstanceRole = InstanceRole.MEMBER) =
		users.createLocalUser(
			email = email,
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = role,
		)

	private val actor: User by lazy {
		user("author-${UUID.randomUUID()}@kanso.test", "Comment Author", InstanceRole.ADMIN)
	}

	/** A fixed local part, because `@lea` is what the body has to resolve against. */
	private val lea: User by lazy { user("lea@kanso.test", "Lea Martin") }

	/**
	 * Claimed on purpose: an unclaimed chain is open to everyone, so a team with no
	 * members could not refuse the outsider this file needs.
	 */
	private val team by lazy {
		teams.create(actor, "Commented", "C${UUID.randomUUID().toString().take(4).uppercase()}", null)
			.also { teamRepo.addMember(it.id, actor.id, MemberRole.ADMIN) }
	}

	private val ticket: Ticket by lazy {
		tickets.create(
			actor = actor,
			teamId = team.id,
			title = "Queue on disk",
			description = null,
			status = "todo",
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket
	}

	@Test
	fun `an at-handle in the body becomes a stored mention`() {
		// Named before the write, because resolution happens at write time and a handle
		// whose holder does not exist yet is a handle nobody holds.
		val recipient = lea
		val comment = comments.create(
			actor,
			CreateComment(ticketId = ticket.id, body = "@lea — queue on disk or in memory?"),
		)

		assertEquals(listOf(recipient.id), comment.mentions.map { it.id })
		assertEquals(
			listOf(recipient.id),
			comments.forTicket(ticket.id).single().mentions.map { it.id },
			"resolved once, on write — a read never re-parses the body",
		)
	}

	@Test
	fun `an unresolved handle stays plain text rather than failing the write`() {
		val recipient = lea
		val comment = comments.create(
			actor,
			CreateComment(ticketId = ticket.id, body = "@nobody-here should this go to @lea?"),
		)

		assertEquals(listOf(recipient.id), comment.mentions.map { it.id })
		assertTrue(
			comment.body.contains("@nobody-here"),
			"an address nobody holds is a sentence, not an error",
		)
	}

	@Test
	fun `a comment on another team's ticket is refused`() {
		val outsider = user("outsider-${UUID.randomUUID()}@kanso.test", "Outsider")

		assertFailsWith<AccessDeniedException> {
			comments.create(outsider, CreateComment(ticketId = ticket.id, body = "hello"))
		}
		assertEquals(emptyList(), comments.forTicket(ticket.id), "and nothing was written on the way out")
	}

	@Test
	fun `commenting records one activity row`() {
		comments.create(actor, CreateComment(ticketId = ticket.id, body = "reproduced"))

		assertEquals(ActivityKind.COMMENTED, activity.forEntity(ActivityEntity.TICKET, ticket.id).first().kind)
	}

	@Test
	fun `a comment belongs to one thing, and the service says so before the check does`() {
		assertFailsWith<BadRequestException> { comments.create(actor, CreateComment(body = "orphan")) }
		assertFailsWith<BadRequestException> {
			comments.create(actor, CreateComment(ticketId = ticket.id, docId = UUID.randomUUID(), body = "both"))
		}
		assertFailsWith<BadRequestException> {
			comments.create(actor, CreateComment(ticketId = ticket.id, body = "   "))
		}
	}

	@Test
	fun `a thread reads oldest first, and only its own ticket's`() {
		val other = tickets.create(
			actor = actor,
			teamId = team.id,
			title = "Elsewhere",
			description = null,
			status = "todo",
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket

		comments.create(actor, CreateComment(ticketId = ticket.id, body = "first"))
		comments.create(actor, CreateComment(ticketId = ticket.id, body = "second"))
		comments.create(actor, CreateComment(ticketId = other.id, body = "somewhere else"))

		assertEquals(listOf("first", "second"), comments.forTicket(ticket.id).map { it.body })
		assertEquals(listOf("somewhere else"), comments.forTicket(other.id).map { it.body })
	}

	@Test
	fun `only the author, or somebody who configures the instance, may retract a comment`() {
		val member = user("member-${UUID.randomUUID()}@kanso.test", "Team Member")
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)
		val comment = comments.create(actor, CreateComment(ticketId = ticket.id, body = "mine"))

		assertFailsWith<AccessDeniedException> { comments.delete(member, comment.id) }

		comments.delete(actor, comment.id)
		assertEquals(emptyList(), comments.forTicket(ticket.id))
	}

	@Test
	fun `commenting on a document is refused while documents have no team to scope against`() {
		assertFailsWith<BadRequestException> {
			comments.create(actor, CreateComment(docId = UUID.randomUUID(), body = "on a page"))
		}
	}
}

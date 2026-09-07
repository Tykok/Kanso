package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who gets told, and by which write.
 *
 * `NotificationServiceTest` pins what the inbox does with a row by calling
 * [NotificationService.record] itself. This file pins the other half — that the writes
 * which change somebody else's work actually call it — and it exists because of the
 * defect it was written against: a screen with four tabs, two of which could not hold a
 * row, because `record` had no caller anywhere in the API.
 *
 * Asserted through [NotificationService.inbox], never through an event: this suite is
 * `@Transactional` and rolls back, so `EventPublisher`'s `afterCommit` never fires.
 */
@Transactional
class NotificationCallSitesTest : PostgresTest() {

	@Autowired lateinit var notifications: NotificationService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var comments: CommentService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(
		name: String,
		email: String = "$name-${UUID.randomUUID()}@kanso.test",
		role: InstanceRole = InstanceRole.MEMBER,
	): User = users.createLocalUser(
		email = email,
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { person("Admin", role = InstanceRole.ADMIN) }

	/** A fixed local part, because `@inbox-lea` is what a body has to resolve against. */
	private val mentioned: User by lazy { person("Lea Martin", email = "inbox-lea@kanso.test") }

	/**
	 * Left unclaimed on purpose: nothing here is about who may touch the ticket, and an
	 * unclaimed chain lets the people this file notifies also write on it.
	 */
	private val team by lazy {
		teams.create(admin, "Inbox ${UUID.randomUUID()}", "N${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun newTicket(
		title: String = "Echo suppression drops our own writes",
		assignees: List<UUID> = emptyList(),
	) = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
	)

	@Test
	fun `creating a ticket with assignees tells them, and never tells the actor`() {
		val lea = person("Lea")

		val ticket = newTicket(assignees = listOf(lea.id, admin.id))

		val row = notifications.inbox(lea.id).rows.single()
		assertEquals("assigned", row.kind)
		assertEquals(ticket.identifier, row.reference)
		assertEquals("Admin", row.actor?.displayName)
		assertEquals(
			0,
			notifications.inbox(admin.id).rows.size,
			"being told about your own action is noise, and the actor assigned this to themselves",
		)
	}

	@Test
	fun `assigning somebody later tells the person added and nobody else`() {
		val lea = person("Lea")
		val rey = person("Rey")
		val ticket = newTicket(assignees = listOf(lea.id))

		tickets.setAssignees(admin, ticket.ticket.id, listOf(lea.id, rey.id))

		assertEquals(listOf("assigned"), notifications.inbox(rey.id).rows.map { it.kind })
		assertEquals(
			1,
			notifications.inbox(lea.id).rows.size,
			"she already had the ticket; nothing changed for her",
		)
	}

	@Test
	fun `moving the status tells the assignees, carrying both ends of the move`() {
		val lea = person("Lea")
		val ticket = newTicket(assignees = listOf(lea.id))

		tickets.patch(admin, ticket.ticket.id, TicketPatch(status = DefaultStatus.IN_REVIEW))

		val rows = notifications.inbox(lea.id).rows
		assertEquals(listOf("status_moved", "assigned"), rows.map { it.kind })
		assertEquals("todo", rows.first().payload["from"])
		assertEquals("in_review", rows.first().payload["to"])
	}

	@Test
	fun `a patch that moves no status says nothing`() {
		val lea = person("Lea")
		val ticket = newTicket(assignees = listOf(lea.id))

		tickets.patch(admin, ticket.ticket.id, TicketPatch(title = "Echo suppression, restated"))

		assertEquals(
			listOf("assigned"),
			notifications.inbox(lea.id).rows.map { it.kind },
			"a rename is the activity log's business, not the inbox's",
		)
	}

	@Test
	fun `the actor is not told about a status they moved themselves`() {
		val ticket = newTicket(assignees = listOf(admin.id))

		tickets.patch(admin, ticket.ticket.id, TicketPatch(status = DefaultStatus.DONE))

		assertEquals(0, notifications.inbox(admin.id).rows.size)
	}

	@Test
	fun `a mention tells the person named, quoting the sentence that named them`() {
		val lea = mentioned
		val ticket = newTicket()

		comments.create(
			admin,
			CreateComment(ticketId = ticket.ticket.id, body = "@inbox-lea does the queue survive a restart?"),
		)

		val row = notifications.inbox(lea.id).rows.single()
		assertEquals("mentioned", row.kind)
		assertEquals("@inbox-lea does the queue survive a restart?", row.payload["excerpt"])
		assertEquals("Admin", row.actor?.displayName)
	}

	@Test
	fun `commenting where somebody else already did tells them their comment got a reply`() {
		val lea = person("Lea")
		val ticket = newTicket()
		comments.create(lea, CreateComment(ticketId = ticket.ticket.id, body = "reproduced on 16.4"))

		comments.create(admin, CreateComment(ticketId = ticket.ticket.id, body = "the poller was dropping it"))

		assertEquals(listOf("comment_replied"), notifications.inbox(lea.id).rows.map { it.kind })
	}

	@Test
	fun `somebody who is both mentioned and replied to is told once`() {
		val lea = mentioned
		val ticket = newTicket()
		comments.create(lea, CreateComment(ticketId = ticket.ticket.id, body = "reproduced on 16.4"))

		comments.create(admin, CreateComment(ticketId = ticket.ticket.id, body = "@inbox-lea it was the poller"))

		assertEquals(
			listOf("mentioned"),
			notifications.inbox(lea.id).rows.map { it.kind },
			"two rows for one sentence is the noise the reply heuristic has to avoid",
		)
	}

	@Test
	fun `nobody is told about their own comment`() {
		val ticket = newTicket()
		comments.create(admin, CreateComment(ticketId = ticket.ticket.id, body = "first"))

		comments.create(admin, CreateComment(ticketId = ticket.ticket.id, body = "and second"))

		assertEquals(0, notifications.inbox(admin.id).rows.size)
	}
}

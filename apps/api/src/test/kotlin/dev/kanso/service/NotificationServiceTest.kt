package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inbox, asserted through the service.
 *
 * Never through an event: this suite is `@Transactional` and rolls back, so
 * `EventPublisher` defers to an `afterCommit` that never fires — `docs/follow-ups.md`
 * records why. A notification is a row, and a row is what these tests read.
 */
@Transactional
class NotificationServiceTest : PostgresTest() {

	@Autowired lateinit var notifications: NotificationService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var jobs: SyncJobRepository
	@Autowired lateinit var trash: TrashService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String, role: InstanceRole = InstanceRole.MEMBER): User =
		users.createLocalUser(
			email = "$name-${UUID.randomUUID()}@kanso.test",
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = role,
		)

	private val admin: User by lazy { person("Admin", InstanceRole.ADMIN) }

	private fun newTicket(title: String = "Echo suppression drops our own writes") = tickets.create(
		actor = admin,
		teamId = teams.create(admin, "Core ${UUID.randomUUID()}", "K${UUID.randomUUID().toString().take(4).uppercase()}", null).id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	@Test
	fun `a notification reaches its recipient, newest first, naming the ticket`() {
		val lea = person("Lea")
		val ticket = newTicket()

		notifications.record(
			recipients = listOf(lea.id),
			kind = NotificationKind.ASSIGNED,
			entityType = "ticket",
			entityId = ticket.ticket.id,
			actorId = admin.id,
		)
		notifications.record(
			recipients = listOf(lea.id),
			kind = NotificationKind.STATUS_MOVED,
			entityType = "ticket",
			entityId = ticket.ticket.id,
			actorId = admin.id,
			payload = mapOf("from" to "todo", "to" to "in_review"),
		)

		val inbox = notifications.inbox(lea.id)

		assertEquals(listOf("status_moved", "assigned"), inbox.rows.map { it.kind })
		val newest = inbox.rows.first()
		assertEquals(ticket.identifier, newest.reference)
		assertEquals("Echo suppression drops our own writes", newest.subject)
		assertEquals("Admin", newest.actor?.displayName)
		assertEquals("in_review", newest.payload["to"])
		assertNull(newest.readAt)
	}

	@Test
	fun `the actor is never told what the actor just did`() {
		val ticket = newTicket()

		val written = notifications.record(
			recipients = listOf(admin.id),
			kind = NotificationKind.ASSIGNED,
			entityType = "ticket",
			entityId = ticket.ticket.id,
			actorId = admin.id,
		)

		assertEquals(0, written)
		assertEquals(0, notifications.inbox(admin.id).rows.size)
	}

	@Test
	fun `one inbox is not another`() {
		val lea = person("Lea")
		val rey = person("Rey")
		val ticket = newTicket()

		notifications.record(listOf(lea.id), NotificationKind.MENTIONED, "ticket", ticket.ticket.id, rey.id)

		assertEquals(1, notifications.inbox(lea.id).rows.size)
		assertEquals(0, notifications.inbox(rey.id).rows.size)
	}

	@Test
	fun `marking all read empties the count and keeps the rows`() {
		val lea = person("Lea")
		val ticket = newTicket()
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)
		notifications.record(listOf(lea.id), NotificationKind.MENTIONED, "ticket", ticket.ticket.id, admin.id)

		assertEquals(2, notifications.inbox(lea.id).counts.unread)
		assertEquals(2, notifications.markAllRead(lea.id))

		val after = notifications.inbox(lea.id)
		assertEquals(0, after.counts.unread)
		assertEquals(2, after.rows.size)
		assertTrue(after.rows.all { it.readAt != null })
	}

	@Test
	fun `a reply to your comment is counted as a mention, not as its own tab`() {
		val lea = person("Lea")
		val ticket = newTicket()
		notifications.record(listOf(lea.id), NotificationKind.MENTIONED, "ticket", ticket.ticket.id, admin.id)
		notifications.record(listOf(lea.id), NotificationKind.COMMENT_REPLIED, "ticket", ticket.ticket.id, admin.id)
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)

		val inbox = notifications.inbox(lea.id)
		assertEquals(2, inbox.counts.mentions)
		assertEquals(1, inbox.counts.assigned)
		assertEquals(2, notifications.inbox(lea.id, InboxTab.MENTIONS).rows.size)
		assertEquals(1, notifications.inbox(lea.id, InboxTab.ASSIGNED).rows.size)
	}

	/**
	 * The failures tab is derived from the outbox rather than stored beside the other
	 * kinds. `sync_jobs` is where "the mirror refused this write" is already true, and a
	 * stored copy would keep claiming it after the job was retried and pushed.
	 */
	@Test
	fun `a refused mirror push appears in the failures tab with nobody having recorded it`() {
		val lea = person("Lea")
		val ticket = newTicket("The mirror refused two writes")
		val job = jobs.claimBatch(10, "test").single { it.entityId == ticket.ticket.id }
		jobs.markFailed(job.id, "The target page is locked by another workspace")

		val inbox = notifications.inbox(lea.id, InboxTab.FAILURES)

		val row = inbox.rows.single()
		assertEquals("sync_failed", row.kind)
		assertEquals(ticket.identifier, row.reference)
		assertEquals("The mirror refused two writes", row.subject)
		assertEquals("The target page is locked by another workspace", row.payload["error"])
		assertEquals(job.id, (row.payload["jobId"] as Number).toLong())
		assertEquals(1, inbox.counts.failures)
		// It is unread while it is broken: there is no "dismiss", only Retry.
		assertNull(row.readAt)
		assertEquals(1, notifications.inbox(lea.id).counts.unread)
	}

	@Test
	fun `marking all read does not pretend a failed push was dealt with`() {
		val lea = person("Lea")
		val ticket = newTicket()
		val job = jobs.claimBatch(10, "test").single { it.entityId == ticket.ticket.id }
		jobs.markFailed(job.id, "locked")

		notifications.markAllRead(lea.id)

		assertEquals(1, notifications.inbox(lea.id).counts.unread)
	}

	@Test
	fun `a conflict carries both versions of the field it is about`() {
		val lea = person("Lea")
		val ticket = newTicket()

		notifications.record(
			recipients = listOf(lea.id),
			kind = NotificationKind.CONFLICT,
			entityType = "ticket",
			entityId = ticket.ticket.id,
			actorId = null,
			payload = mapOf(
				"field" to "title",
				"mine" to "Echo suppression drops our own writes",
				"theirs" to "Echo suppression: our own writes are dropped",
				"theirActor" to "M. Rey",
			),
		)

		val row = notifications.inbox(lea.id).rows.single()
		assertEquals("title", row.payload["field"])
		assertEquals("Echo suppression: our own writes are dropped", row.payload["theirs"])
		assertNull(row.actor)
	}

	@Test
	fun `another person's notification cannot be marked read`() {
		val lea = person("Lea")
		val rey = person("Rey")
		val ticket = newTicket()
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)
		val id = assertNotNull(notifications.inbox(lea.id).rows.single().id)

		assertFailsWith<NotFoundException> { notifications.markRead(rey.id, id) }
		assertNull(notifications.inbox(lea.id).rows.single().readAt)
	}

	/**
	 * `entity_id` carries no foreign key — it points at whichever of three tables
	 * `entity_type` names — so nothing cascades and the row outlives its subject. It is
	 * kept rather than dropped: being told a ticket was assigned to you is true whether
	 * or not the ticket survived, and dropping it would make the count disagree with the
	 * list it is counting.
	 */
	@Test
	fun `a notification about a trashed ticket still names it`() {
		val lea = person("Lea")
		val ticket = newTicket()
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)

		tickets.delete(admin, ticket.ticket.id)

		// Deleting is a thirty-day countdown, not a destruction, so the row it points at
		// is still there and still restorable — and a notification you cannot read the
		// name of is one you cannot act on. This assertion was the other way round until
		// the trash landed: `delete` used to destroy, and the name went with it.
		val inbox = notifications.inbox(lea.id)
		val row = inbox.rows.single()
		assertEquals(ticket.identifier, row.reference)
		assertNotNull(row.subject)
		assertEquals("Admin", row.actor?.displayName)
		assertEquals(1, inbox.counts.unread)
	}

	@Test
	fun `a notification about a purged ticket keeps its place and loses its name`() {
		val lea = person("Lea")
		val ticket = newTicket()
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)

		tickets.delete(admin, ticket.ticket.id)
		trash.purge(admin, TrashKind.TICKET, ticket.ticket.id)

		// The row survives its subject: an inbox that dropped entries when the thing they
		// were about went away would silently change the count somebody already read.
		val inbox = notifications.inbox(lea.id)
		val row = inbox.rows.single()
		assertNull(row.reference)
		assertNull(row.subject)
		assertEquals("Admin", row.actor?.displayName)
		assertEquals(1, inbox.counts.unread)
	}

	@Test
	fun `an unknown kind is refused by the database, not by the service`() {
		val lea = person("Lea")
		val ticket = newTicket()

		// The vocabulary is closed by a CHECK, following `user_preferences`. The enum
		// above is the same closed set said twice; this asserts the second one is real.
		assertFailsWith<IllegalArgumentException> { NotificationKind.from("nudged") }
		assertEquals(
			NotificationKind.ASSIGNED,
			NotificationKind.from("assigned"),
		)
		notifications.record(listOf(lea.id), NotificationKind.ASSIGNED, "ticket", ticket.ticket.id, admin.id)
	}
}

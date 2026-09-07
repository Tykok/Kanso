package dev.kanso.auth

import dev.kanso.MockMvcTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ticket no team has claimed belongs to whoever wrote it, and every read that hangs off
 * its id has to say so.
 *
 * `TicketAccess.mayRead` has enforced that since drafts existed, but only two callers ever
 * asked it — `GET /api/tickets/{id}` and the drafts list. The other four took no actor at
 * all, so a draft's comments, its feed, its labels and its duration answered anybody who
 * held the UUID. `CommentController` was the plainest: its `create` took the actor and its
 * `list` did not.
 *
 * The list below is typed out rather than enumerated, and that is the one place this test
 * differs from `ReadOnlySeatLeakTest` and `PublicLeakTest`. Those two can enumerate because
 * *every* unsafe mapping is refused; here only the reads that answer about a ticket are,
 * and Spring cannot be asked which of its `@GetMapping`s means "about this ticket" —
 * `by-key`, `/grouped` and `/api/teams/{id}/labels` all take an id that is not a ticket's.
 *
 * So be honest about what this pins and what it does not. It proves these four hold, in
 * all four directions that matter. It will **not** notice a fifth read added next month;
 * nothing here can, short of a convention Spring could be asked about. That gap is the
 * reason the rule lives in `TicketAccess.requireReadable` rather than being written out
 * four times — the next author has one thing to find, not four to imitate.
 */
@Transactional
class DraftReadLeakTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teamRows: TeamRepository

	/** Every read that answers about one ticket, as a path with `%s` for its id. */
	private val reads = listOf(
		"/api/comments?ticketId=%s",
		"/api/activity?entityType=ticket&entityId=%s",
		"/api/tickets/%s/labels",
		"/api/tickets/%s/duration",
	)

	private fun user(name: String) = users.createLocalUser(
		email = "$name-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "x",
		role = InstanceRole.MEMBER,
	)

	private fun draft(actor: User) = tickets.create(
		actor = actor,
		teamId = null,
		title = "A draft nobody else should read",
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	/** A team nobody belongs to, which `claimedBy` leaves open — the ordinary-ticket case. */
	private fun teamOf(): UUID =
		teamRows.insert("Team ${UUID.randomUUID().toString().take(4)}", "T${UUID.randomUUID().toString().take(3).uppercase()}", null).id

	@Test
	fun `a stranger reading somebody's draft is told it does not exist`() {
		val author = user("Author")
		val stranger = user("Stranger")
		val id = draft(author).ticket.id

		assertTrue(reads.isNotEmpty(), "an empty list would make this a green light")
		for (path in reads) {
			val response = mvc.perform(get(path.format(id)).header("X-Kanso-User", stranger.email))
				.andReturn().response
			assertEquals(
				404,
				response.status,
				"$path leaked a draft — and 404, not 403: a refusal that confirms the row " +
					"is there is what lets somebody walk UUIDs",
			)
		}
	}

	@Test
	fun `the author reads their own draft on every one of them`() {
		val author = user("Author")
		val id = draft(author).ticket.id

		for (path in reads) {
			assertEquals(
				200,
				mvc.perform(get(path.format(id)).header("X-Kanso-User", author.email)).andReturn().response.status,
				"$path refused the person who wrote it",
			)
		}
	}

	@Test
	fun `a ticket that has a team is unchanged for everybody`() {
		val author = user("Author")
		val reader = user("Reader")
		val team = teamOf()
		val id = tickets.create(
			actor = author,
			teamId = team,
			title = "Ordinary work",
			description = null,
			status = DefaultStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

		// The leak was the draft case escaping. Anything else must answer exactly as it did.
		for (path in reads) {
			assertEquals(
				200,
				mvc.perform(get(path.format(id)).header("X-Kanso-User", reader.email)).andReturn().response.status,
				"$path stopped answering about an ordinary ticket",
			)
		}
	}

	@Test
	fun `a missing ticket and a hidden one are indistinguishable`() {
		val author = user("Author")
		val stranger = user("Stranger")
		val hidden = draft(author).ticket.id
		val missing = UUID.randomUUID()

		for (path in reads) {
			val a = mvc.perform(get(path.format(hidden)).header("X-Kanso-User", stranger.email)).andReturn().response
			val b = mvc.perform(get(path.format(missing)).header("X-Kanso-User", stranger.email)).andReturn().response
			assertEquals(a.status, b.status, "$path: the two answers differ by status")
			assertEquals(
				a.contentAsString.replace(hidden.toString(), "ID").replace(missing.toString(), "ID"),
				b.contentAsString.replace(hidden.toString(), "ID").replace(missing.toString(), "ID"),
				"$path: the two answers differ by body, which is the tell",
			)
		}
	}
}

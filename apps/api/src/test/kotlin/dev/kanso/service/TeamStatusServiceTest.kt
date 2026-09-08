package dev.kanso.service

import tools.jackson.databind.ObjectMapper
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.StatusCategory
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TicketRepository
import kotlin.test.assertNull
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
 * A team's words, their order, and — since `KAN-90` — which words exist at all.
 *
 * The `KAN-28` cases write `label` or `position` and never the set of keys, because
 * `Ticket.status` was an enum then. `KAN-90` made it the key of one of the team's own
 * statuses, so the set is editable and the cases below it are about what happens to the
 * tickets when it changes.
 */
@Transactional
class TeamStatusServiceTest : PostgresTest() {

	@Autowired lateinit var service: TeamStatusService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var ticketService: TicketService
	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var activity: ActivityRepository
	@Autowired lateinit var objectMapper: ObjectMapper

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "statuses-${UUID.randomUUID()}@kanso.test",
		displayName = "Status ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun team(actor: dev.kanso.domain.User) =
		teams.create(actor, "Support ${UUID.randomUUID()}", null, null)

	private fun ticket(actor: dev.kanso.domain.User, teamId: UUID, status: String) = ticketService.create(
		actor = actor,
		teamId = teamId,
		title = "A ticket in $status",
		description = null,
		status = status,
		priority = dev.kanso.domain.TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

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

	// --- KAN-90: which words exist at all -----------------------------------

	@Test
	fun `an added status lands last, and means what the team said it means`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		val added = service.add(actor, team.id, "Devis", StatusCategory.BACKLOG)

		assertEquals("devis", added.key, "a key is derived from the label, never typed")
		assertEquals(StatusCategory.BACKLOG, added.category)
		val all = service.list(actor, team.id)
		assertEquals(7, all.size)
		// Last, so adding one never restacks a board somebody is looking at. Where it
		// belongs is a reorder, which is a separate gesture the team already has.
		assertEquals("devis", all.last().key)
	}

	@Test
	fun `a second spelling of an existing word is refused on the way in, by the word`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		// The same refusal `rename` gives, and it has to be: `team_statuses_label_uniq`
		// compares case-insensitively, so a pre-check that did not would let the driver
		// answer an opaque 409 for a mistake with a sentence available.
		assertEquals(
			"""This team already has a status called "in progress"""",
			assertFailsWith<ConflictException> {
				service.add(actor, team.id, "In Progress", StatusCategory.STARTED)
			}.message,
		)
	}

	@Test
	fun `a label with no letter or digit in it cannot be added`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		assertEquals(
			"A status needs a letter or a digit in its name",
			assertFailsWith<BadRequestException> { service.add(actor, team.id, "…", StatusCategory.BACKLOG) }.message,
		)
	}

	@Test
	fun `a removal moves the tickets it held, and writes a line for each`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)
		val moving = ticket(actor, team.id, "in_review")

		service.remove(actor, team.id, "in_review", into = "in_progress")

		assertEquals(6 - 1, service.list(actor, team.id).size)
		assertEquals("in_progress", tickets.findById(moving)!!.status)
		// A burndown that saw the number move with no line behind it is a burndown nobody
		// trusts, so the move writes the same `status_changed` an edit would.
		val line = activity.forEntity(ActivityEntity.TICKET, moving, limit = 50)
			.single { it.kind == ActivityKind.STATUS_CHANGED }
		assertEquals("in_review", objectMapper.readTree(line.payload).path("from").asText())
		assertEquals("in_progress", objectMapper.readTree(line.payload).path("to").asText())
	}

	@Test
	fun `a removal that names no destination is refused before anything moves`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)
		val held = ticket(actor, team.id, "in_review")

		assertEquals(
			"""Removing "in_review" has to say where its 1 ticket goes""",
			assertFailsWith<BadRequestException> {
				service.remove(actor, team.id, "in_review", into = null)
			}.message,
		)
		assertEquals("in_review", tickets.findById(held)!!.status, "nothing moved")
		assertEquals(6, service.list(actor, team.id).size)
	}

	@Test
	fun `a status holding nothing is removed without naming a destination`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)

		service.remove(actor, team.id, "in_review", into = null)

		assertNull(service.list(actor, team.id).firstOrNull { it.key == "in_review" })
	}

	@Test
	fun `a team may not remove its last status`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)
		for (key in listOf("backlog", "todo", "in_progress", "in_review", "done")) {
			service.remove(actor, team.id, key, into = null)
		}

		// A team with no statuses could hold no tickets at all — `tickets_status_fk` has
		// nothing to point at — so the screen would refuse every write with a foreign key
		// error instead of a sentence.
		assertEquals(
			"A team keeps at least one status",
			assertFailsWith<BadRequestException> { service.remove(actor, team.id, "canceled", into = null) }.message,
		)
	}

	@Test
	fun `a destination the team does not have is refused, and so is removing into itself`() {
		val actor = user(InstanceRole.OWNER)
		val team = team(actor)
		ticket(actor, team.id, "in_review")

		assertEquals(
			"This team has no status 'devis'",
			assertFailsWith<BadRequestException> {
				service.remove(actor, team.id, "in_review", into = "devis")
			}.message,
		)
		// Into itself would leave the rows pointing at a key that no longer exists, which
		// is the one outcome `tickets_status_fk` cannot express and the driver would.
		assertEquals(
			"""Removing "in_review" cannot move its tickets into itself""",
			assertFailsWith<BadRequestException> {
				service.remove(actor, team.id, "in_review", into = "in_review")
			}.message,
		)
	}

	@Test
	fun `adding and removing are a configurator's, like renaming`() {
		val owner = user(InstanceRole.OWNER)
		val member = user(InstanceRole.MEMBER)
		val team = team(owner)

		assertFailsWith<AccessDeniedException> { service.add(member, team.id, "Devis", StatusCategory.BACKLOG) }
		assertFailsWith<AccessDeniedException> { service.remove(member, team.id, "in_review", into = null) }
	}
}

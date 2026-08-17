package dev.kanso.trash

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP edge of screen 26.
 *
 * `TrashServiceTest` covers the behaviour; what only this file can reach is the edge
 * itself — the wire strings the client's own union is written against, the actor coming off
 * the security context rather than a parameter, and the `kind` path variable, which is a
 * *string* and therefore the one place the closed vocabulary has to be refused at runtime.
 * The Playwright scenario would cover all of it, and cannot run on a branch that must not
 * touch the shared compose stack.
 */
@Transactional
class TrashControllerTest : PostgresTest() {

	@Autowired lateinit var controller: TrashController
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "trashc-${UUID.randomUUID()}@kanso.test",
			displayName = "M. Rey",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	/** Stands in for the auth filter, which has no servlet request to run inside here. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	private fun thrownAwayTicket(): Pair<UUID, String> {
		val team = teams.create(admin, "Core", "C${UUID.randomUUID().toString().take(4).uppercase()}", null)
		val product = projects.create(
			name = "Product",
			status = ProjectStatus.PLANNED,
			start = null,
			end = null,
			leadUserId = null,
			teamId = team.id,
			docIds = emptyList(),
		).project
		val ticket = tickets.create(
			actor = admin,
			teamId = team.id,
			title = "SVG seal",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = product.id,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)
		tickets.delete(admin, ticket.ticket.id)
		return ticket.ticket.id to ticket.identifier
	}

	@Test
	fun `the response carries the wire strings the client is written against`() {
		actAs(admin)
		val (id, identifier) = thrownAwayTicket()

		val body = controller.load()

		assertEquals(30, body.retentionDays, "the screen prints the rule, it does not hardcode it")
		val row = body.trash.single { it.id == id }
		assertEquals("ticket", row.kind, "`TrashKind.wire`, not the enum's name")
		assertEquals("$identifier · SVG seal", row.label)
		assertEquals("project", row.parent?.kind)
		assertEquals("Product", row.parent?.name)
		assertEquals(30, row.daysLeft)
		assertEquals("M. Rey", row.deletedBy?.displayName)
		assertTrue(body.archives.none { it.id == id })
	}

	/**
	 * `kind` arrives as a string, so this is the only place the closed vocabulary can be
	 * broken by a caller. A 400 rather than a 500 or an enum-conversion failure nobody
	 * mapped: an unknown kind is a malformed request, the same class as an unknown status.
	 */
	@Test
	fun `an unknown kind is refused as a bad request`() {
		actAs(admin)
		val (id, _) = thrownAwayTicket()

		val error = assertFailsWith<BadRequestException> { controller.restore("sprint", id) }
		assertTrue(error.message!!.contains("ticket, doc, view, folder"), error.message!!)
	}

	@Test
	fun `the three exits read their actor off the security context`() {
		actAs(admin)
		val (restored, _) = thrownAwayTicket()
		val (archived, _) = thrownAwayTicket()
		val (destroyed, _) = thrownAwayTicket()

		controller.restore("ticket", restored)
		controller.archiveInstead("ticket", archived)
		controller.purge("ticket", destroyed)

		val body = controller.load()
		assertTrue(body.trash.isEmpty(), "all three left the trash by a different door")
		assertNull(tickets.get(restored).ticket.completedAt, "and the restored one is readable again")
		assertTrue(tickets.get(archived).ticket.archived)
		assertTrue(body.archives.any { it.id == archived })
		assertTrue(body.archives.none { it.id == destroyed }, "destroyed is not archived")
	}
}

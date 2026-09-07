package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.CustomFieldService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketFieldService
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Who may shape a team's fields, and who may fill them in.
 *
 * Two acts, two guards, and the whole point of the file is that neither of them is new:
 * defining reaches `TicketAccess.requireTeam` like `LabelService.create`, valuing reaches
 * `TicketAccess.require` like `LabelService.attach`, and the read-only seat falls out of
 * both because `requireSeatThatWrites` is asked first inside each. So this pins three
 * properties — the seat, the team, and the fact that the seat's refusal is *the same
 * sentence* the rest of the application gives, because two sentences would be the first
 * visible symptom of two rules.
 */
@Transactional
class CustomFieldSeatTest : PostgresTest() {

	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var values: TicketFieldService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "fieldseat-${UUID.randomUUID()}@kanso.test",
		displayName = "Field seat ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "S${UUID.randomUUID().toString().take(4).uppercase()}"

	/**
	 * A team with somebody in it. The membership matters: `TicketAccess`'s open-chain clause
	 * leaves a team nobody has claimed editable by everybody, so a test that skipped this
	 * would prove nothing about the team rule.
	 */
	private fun teamWith(member: User): Team = teams.create(admin, "Seats", key(), null).also {
		teamRepo.addMember(it.id, member.id, MemberRole.MEMBER)
	}

	private fun ticketIn(teamId: UUID): Ticket = tickets.create(
		actor = admin, teamId = teamId, title = "Work", description = null,
		status = DefaultStatus.TODO, priority = TicketPriority.NONE,
		start = null, due = null, projectId = null,
		assigneeIds = emptyList(), docIds = emptyList(),
	).ticket

	// --- the seat ------------------------------------------------------------

	/**
	 * A viewer inside the team is still refused both acts, and told about the seat rather
	 * than about the team — the distinction `TicketAccess.requireSeatThatWrites` exists for:
	 * "Seats is not one of your teams" is true and useless, because being added to Seats
	 * would change nothing.
	 */
	@Test
	fun `a viewer may define no field and value none, and hears about the seat`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teamWith(viewer)
		val ticket = ticketIn(team.id)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		val refusals = mapOf<String, () -> Any?>(
			"define a field" to { fields.define(viewer, team.id, "New", "text", false, emptyList()) },
			"rename a field" to { fields.redefine(viewer, severity.id, "Impact", null, false, emptyList()) },
			"delete a field" to { fields.remove(viewer, severity.id) },
			"value a field" to { values.setValues(viewer, ticket.id, mapOf(severity.id.toString() to "high")) },
		)

		for ((what, attempt) in refusals) {
			val refused = assertFailsWith<AccessDeniedException>("a viewer was allowed to $what") { attempt() }
			assertEquals(
				TicketAccess.READS_NOT_WRITES,
				refused.message,
				"`$what` refused a viewer with a sentence of its own, which means a rule of its own",
			)
		}
	}

	/** The seat takes nothing away from reading, which is the whole premise of the seat. */
	@Test
	fun `a viewer reads a team's fields and a ticket's values`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teamWith(viewer)
		val ticket = ticketIn(team.id)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())
		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))

		assertEquals(listOf("Severity"), fields.list(team.id).map { it.field.name })
		assertEquals("high", values.valuesOf(viewer, ticket.id)[severity.id])
	}

	// --- the team ------------------------------------------------------------

	/**
	 * A writing member of another team is refused, and *that* refusal names the team — the
	 * other half of the two-part message. It is a different sentence from the viewer's above,
	 * on purpose: this one invites a useful next step.
	 */
	@Test
	fun `a member of another team may not define a field on this one`() {
		val stranger = user(InstanceRole.MEMBER)
		val team = teamWith(admin)

		val refused = assertFailsWith<AccessDeniedException> {
			fields.define(stranger, team.id, "Severity", "text", false, emptyList())
		}

		assertTrue(refused.message!!.contains("Seats"), "the refusal must name the team; got: ${refused.message}")
		assertTrue(
			refused.message != TicketAccess.READS_NOT_WRITES,
			"a writing member refused by the team rule must not be told their seat cannot write",
		)
	}

	@Test
	fun `a member of another team may not value a field on this one's ticket`() {
		val stranger = user(InstanceRole.MEMBER)
		val team = teamWith(admin)
		val ticket = ticketIn(team.id)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		val refused = assertFailsWith<AccessDeniedException> {
			values.setValues(stranger, ticket.id, mapOf(severity.id.toString() to "high"))
		}

		assertTrue(refused.message!!.contains("Seats"), "got: ${refused.message}")
	}

	@Test
	fun `a member of the team may do both`() {
		val member = user(InstanceRole.MEMBER)
		val team = teamWith(member)
		val ticket = ticketIn(team.id)

		val severity = fields.define(member, team.id, "Severity", "text", false, emptyList())
		values.setValues(member, ticket.id, mapOf(severity.id.toString() to "high"))

		assertEquals("high", values.valuesOf(member, ticket.id)[severity.id])
	}

	/**
	 * The read that four unguarded reads hanging off a ticket id already taught this codebase
	 * to check: a draft is private to whoever wrote it, and its field values are as much of
	 * its content as its comments are. A 404 rather than a 403, so walking UUIDs learns
	 * nothing.
	 */
	@Test
	fun `somebody else's draft does not answer with its field values`() {
		val stranger = user(InstanceRole.MEMBER)
		val author = user(InstanceRole.MEMBER)
		val draft = tickets.create(
			actor = author, teamId = null, title = "Mine", description = null,
			status = DefaultStatus.TODO, priority = TicketPriority.NONE,
			start = null, due = null, projectId = null,
			assigneeIds = emptyList(), docIds = emptyList(),
		).ticket

		assertFailsWith<dev.kanso.service.NotFoundException> { values.valuesOf(stranger, draft.id) }
		// The author still reads their own.
		assertEquals(emptyMap(), values.valuesOf(author, draft.id))
	}
}

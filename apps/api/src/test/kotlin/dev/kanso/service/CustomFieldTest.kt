package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.CustomFieldType
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Defining a team's fields — the shape half of `V35`.
 *
 * The values are [TicketFieldValueTest]'s and the seat is [dev.kanso.auth.CustomFieldSeatTest]'s;
 * this file is about what a definition may be, and about the two gestures that would have
 * left a stored value disagreeing with its definition if they had been allowed.
 */
@Transactional
class CustomFieldTest : PostgresTest() {

	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var values: TicketFieldService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "field-${UUID.randomUUID()}@kanso.test",
		displayName = "Field ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "F${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun team(): Team = teams.create(admin, "Fields", key(), null).also {
		teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER)
	}

	private fun ticketIn(teamId: UUID?) = tickets.create(
		actor = admin, teamId = teamId, title = "Work", description = null,
		status = TicketStatus.TODO, priority = TicketPriority.NONE,
		start = null, due = null, projectId = null,
		assigneeIds = emptyList(), docIds = emptyList(),
	).ticket

	// --- what a definition may be --------------------------------------------

	@Test
	fun `a field is defined once for a team, and read back with its type`() {
		val team = team()

		val severity = fields.define(admin, team.id, "Severity", "select", false, listOf("low", "high"))

		assertEquals(CustomFieldType.SELECT, severity.type)
		assertEquals(listOf("low", "high"), severity.options)
		assertEquals(team.id, severity.teamId)
		assertEquals(listOf("Severity"), fields.list(team.id).map { it.field.name })
	}

	@Test
	fun `the type vocabulary is closed, and an unknown one is a bad request not a constraint violation`() {
		val team = team()

		val refused = assertFailsWith<BadRequestException> {
			fields.define(admin, team.id, "When", "date", false, emptyList())
		}

		// The message lists what is served, so the caller's next attempt is their last.
		assertTrue(refused.message!!.contains("text"), "got: ${refused.message}")
		assertTrue(refused.message!!.contains("select"), "got: ${refused.message}")
	}

	@Test
	fun `every type in the vocabulary is actually definable, so the enum and the CHECK agree`() {
		val team = team()

		for (type in CustomFieldType.entries) {
			val options = if (type.hasOptions) listOf("a", "b") else emptyList()
			val defined = fields.define(admin, team.id, "F ${type.wire}", type.wire, false, options)
			assertEquals(type, defined.type, "${type.wire} is in the enum but the database refused it")
		}
	}

	/**
	 * Both directions of `custom_fields_options_chk`, refused in the service so each is a 400
	 * naming the field rather than a 500 from the constraint.
	 */
	@Test
	fun `a choice field needs choices, and every other type refuses them`() {
		val team = team()

		assertFailsWith<BadRequestException>("a select with no options is a control nobody can satisfy") {
			fields.define(admin, team.id, "Severity", "select", false, emptyList())
		}
		assertFailsWith<BadRequestException>("a text field with options is a definition that believes it is a dropdown") {
			fields.define(admin, team.id, "Note", "text", false, listOf("low"))
		}
	}

	@Test
	fun `a second field of the same name in one team is a conflict, and another team may reuse it`() {
		val team = team()
		val other = team()
		fields.define(admin, team.id, "Severity", "text", false, emptyList())

		assertFailsWith<ConflictException> {
			fields.define(admin, team.id, "Severity", "text", false, emptyList())
		}
		// The whole point of team scope: `Severity` means one thing inside a team and nothing
		// across them.
		fields.define(admin, other.id, "Severity", "number", false, emptyList())
		assertEquals(CustomFieldType.NUMBER, fields.list(other.id).single().field.type)
	}

	@Test
	fun `a nameless field is refused, and a name is trimmed`() {
		val team = team()

		assertFailsWith<BadRequestException> { fields.define(admin, team.id, "   ", "text", false, emptyList()) }
		assertEquals("Severity", fields.define(admin, team.id, "  Severity ", "text", false, emptyList()).name)
	}

	// --- the two gestures that would have stranded a value -------------------

	/**
	 * Retyping is refused rather than ignored. Allowing it would leave every existing value
	 * disagreeing with its definition — the one state the write path exists to make
	 * impossible — and ignoring it would be a settings screen that accepted a change and did
	 * not make it.
	 */
	@Test
	fun `a field cannot be retyped, and the refusal says what to do instead`() {
		val team = team()
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		val refused = assertFailsWith<BadRequestException> {
			fields.redefine(admin, severity.id, "Severity", "number", false, emptyList())
		}

		assertTrue(refused.message!!.contains("delete"), "got: ${refused.message}")
		assertEquals(CustomFieldType.TEXT, fields.list(team.id).single().field.type, "the type moved anyway")
	}

	@Test
	fun `sending back the same type is not a retype, so a rename goes through`() {
		val team = team()
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		val renamed = fields.redefine(admin, severity.id, "Impact", "text", true, emptyList())

		assertEquals("Impact", renamed.name)
		assertTrue(renamed.required, "required is part of a redefinition")
	}

	/**
	 * The standing invariant, not merely the write-time one: **every stored value satisfies
	 * its definition**. Shrinking the options under a value that holds one would break it
	 * retroactively, so it is refused with the option and where it is.
	 */
	@Test
	fun `an option still in use cannot be removed from a choice field`() {
		val team = team()
		val severity = fields.define(admin, team.id, "Severity", "select", false, listOf("low", "high"))
		val ticket = ticketIn(team.id)
		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))

		val refused = assertFailsWith<ConflictException> {
			fields.redefine(admin, severity.id, "Severity", "select", false, listOf("low"))
		}

		assertTrue(refused.message!!.contains("high"), "the refusal must name the option; got: ${refused.message}")
		// And an option nobody holds comes off without complaint, or the rule would be
		// "options are immutable", which is not the rule.
		fields.redefine(admin, severity.id, "Severity", "select", false, listOf("high", "urgent"))
		assertEquals(listOf("high", "urgent"), fields.list(team.id).single().field.options)
	}

	// --- deleting a definition -----------------------------------------------

	/**
	 * The cascade, and the number that makes it offerable.
	 *
	 * `valueCount` is derived on read and exists for exactly this gesture: a confirmation that
	 * cannot say how many tickets are about to lose a value is asking for a signature on a
	 * blank cheque.
	 */
	@Test
	fun `deleting a definition takes its values with it, and the count says how many first`() {
		val team = team()
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())
		val first = ticketIn(team.id)
		val second = ticketIn(team.id)
		values.setValues(admin, first.id, mapOf(severity.id.toString() to "high"))
		values.setValues(admin, second.id, mapOf(severity.id.toString() to "low"))

		assertEquals(2, fields.list(team.id).single().valueCount, "the confirmation had nothing to print")

		fields.remove(admin, severity.id)

		assertEquals(emptyList(), fields.list(team.id))
		// Not orphaned. A value whose definition is gone has no type, so it cannot be parsed,
		// rendered or exported — it would be bytes every read had to learn to skip.
		assertEquals(emptyMap(), values.valuesOf(admin, first.id))
		assertEquals(emptyMap(), values.valuesOf(admin, second.id))
	}

	@Test
	fun `a field nobody has filled counts zero rather than being absent from the list`() {
		val team = team()
		fields.define(admin, team.id, "Severity", "text", false, emptyList())

		assertEquals(0, fields.list(team.id).single().valueCount)
	}

}

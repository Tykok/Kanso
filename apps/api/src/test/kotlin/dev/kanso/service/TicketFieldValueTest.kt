package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a ticket's fields are worth — the value half of `V32`.
 *
 * `FieldValueCodecTest` already pins the type rules without a database. This file is about
 * the three things that need one: the team boundary, the partial write, and the fact that a
 * value actually survives a round trip through jsonb as the type it went in as.
 */
@Transactional
class TicketFieldValueTest : PostgresTest() {

	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var values: TicketFieldService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var jdbc: JdbcClient

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "value-${UUID.randomUUID()}@kanso.test",
		displayName = "Value ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun key() = "V${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun team(): Team = teams.create(admin, "Values", key(), null).also {
		teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER)
	}

	private fun ticketIn(teamId: UUID?): Ticket = tickets.create(
		actor = admin, teamId = teamId, title = "Work", description = null,
		status = TicketStatus.TODO, priority = TicketPriority.NONE,
		start = null, due = null, projectId = null,
		assigneeIds = emptyList(), docIds = emptyList(),
	).ticket

	// --- a value survives jsonb as the type it went in as --------------------

	/**
	 * The round trip, per type. This is what a `TEXT` column holding a stringified value
	 * would have failed: `true` would come back as the word "true", and nothing downstream
	 * could tell it from a text field somebody typed that into.
	 */
	@Test
	fun `every type round-trips through jsonb as itself`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val note = fields.define(admin, team.id, "Note", "text", false, emptyList())
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())
		val flag = fields.define(admin, team.id, "Regression", "boolean", false, emptyList())
		val severity = fields.define(admin, team.id, "Severity", "select", false, listOf("low", "high"))

		values.setValues(
			admin,
			ticket.id,
			mapOf(
				note.id.toString() to "a thought",
				size.id.toString() to 3,
				flag.id.toString() to true,
				severity.id.toString() to "high",
			),
		)

		val read = values.valuesOf(admin, ticket.id)
		assertEquals("a thought", read[note.id])
		assertEquals(BigDecimal("3"), read[size.id])
		assertEquals(true, read[flag.id], "a boolean came back as something else, so the type is not real")
		assertEquals("high", read[severity.id])
	}

	@Test
	fun `a decimal keeps its scale rather than going through binary floating point`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())

		values.setValues(admin, ticket.id, mapOf(size.id.toString() to BigDecimal("3.50")))

		assertEquals(BigDecimal("3.50"), values.valuesOf(admin, ticket.id)[size.id])
	}

	/**
	 * The validator, reached *through the write path* rather than called directly.
	 *
	 * `FieldValueCodecTest` proves the rules; this proves they are actually on the road
	 * between a request and the column. The two are not the same claim, and only this one
	 * fails if somebody stores `rawValue` instead of the validated one — at which point a
	 * number field would hold the string `"three"`, the jsonb CHECK would accept it happily
	 * as a scalar, and every reader would find a type the definition does not name.
	 */
	@Test
	fun `a wrong-typed value is refused on the way in, for every type, and nothing lands`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val note = fields.define(admin, team.id, "Note", "text", false, emptyList())
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())
		val flag = fields.define(admin, team.id, "Regression", "boolean", false, emptyList())
		val severity = fields.define(admin, team.id, "Severity", "select", false, listOf("low", "high"))

		val wrong = mapOf(
			"a string in a number field" to (size to "three"),
			"a numeral string in a number field" to (size to "3"),
			"a number in a boolean field" to (flag to 1),
			"a string in a boolean field" to (flag to "true"),
			"a number in a text field" to (note to 7),
			"a choice outside the options" to (severity to "urgent"),
		)

		for ((what, pair) in wrong) {
			val (field, value) = pair
			assertFailsWith<BadRequestException>("$what was accepted, so the column is a bag") {
				values.setValues(admin, ticket.id, mapOf(field.id.toString() to value))
			}
		}

		assertEquals(emptyMap(), values.valuesOf(admin, ticket.id), "a refused value was written anyway")
	}

	// --- the team boundary ---------------------------------------------------

	/**
	 * Without this, a team-scoped vocabulary is a global one rebuilt by hand, one write at a
	 * time — and the value would be stored against a field the only screen that can render it
	 * never fetches.
	 */
	@Test
	fun `a ticket cannot take a value for a field defined in another team`() {
		val mine = team()
		val theirs = team()
		val ticket = ticketIn(mine.id)
		val foreign = fields.define(admin, theirs.id, "Severity", "text", false, emptyList())

		val refused = assertFailsWith<ConflictException> {
			values.setValues(admin, ticket.id, mapOf(foreign.id.toString() to "high"))
		}

		assertTrue(refused.message!!.contains("Severity"), "got: ${refused.message}")
		assertEquals(emptyMap(), values.valuesOf(admin, ticket.id))
	}

	/**
	 * A draft has no team, so there is no set of definitions to check against — and it gets a
	 * sentence of its own rather than "belongs to another team", which would be a confusing
	 * way to say "belongs to no team".
	 */
	@Test
	fun `a draft holds no field values, and is told why`() {
		val team = team()
		val draft = ticketIn(null)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		val refused = assertFailsWith<ConflictException> {
			values.setValues(admin, draft.id, mapOf(severity.id.toString() to "high"))
		}

		assertTrue(refused.message!!.contains("no team"), "got: ${refused.message}")
	}

	@Test
	fun `an id that names no field, and one that is not an id at all, are both refused`() {
		val team = team()
		val ticket = ticketIn(team.id)

		assertFailsWith<BadRequestException> {
			values.setValues(admin, ticket.id, mapOf(UUID.randomUUID().toString() to "high"))
		}
		assertFailsWith<BadRequestException> {
			values.setValues(admin, ticket.id, mapOf("severity" to "high"))
		}
	}

	// --- the partial write ---------------------------------------------------

	/**
	 * The shape that differs from `PUT /api/tickets/{id}/labels` beside it. A whole-set
	 * replace here would make "I changed the severity" indistinguishable from "clear every
	 * other field", which is what a client built against a stale definition list would send.
	 */
	@Test
	fun `a field the request does not name is left alone, and a null one is cleared`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val note = fields.define(admin, team.id, "Note", "text", false, emptyList())
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())
		values.setValues(admin, ticket.id, mapOf(note.id.toString() to "kept", size.id.toString() to 3))

		// Only `size` is named, so only `size` moves.
		values.setValues(admin, ticket.id, mapOf(size.id.toString() to 5))
		assertEquals("kept", values.valuesOf(admin, ticket.id)[note.id], "an unnamed field was disturbed")
		assertEquals(BigDecimal("5"), values.valuesOf(admin, ticket.id)[size.id])

		// A null clears, and clearing is the absence of a row rather than a stored null.
		values.setValues(admin, ticket.id, mapOf(size.id.toString() to null))
		val after = values.valuesOf(admin, ticket.id)
		assertTrue(size.id !in after, "a cleared field kept a key, so 'no value' now has two spellings")
		assertEquals("kept", after[note.id])
	}

	/**
	 * Refused whole. A request setting three fields of which one is wrong must not leave two
	 * written and nobody able to say what the ticket holds.
	 */
	@Test
	fun `one bad value in a request refuses the whole request, writing none of it`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val note = fields.define(admin, team.id, "Note", "text", false, emptyList())
		val size = fields.define(admin, team.id, "Size", "number", false, emptyList())

		assertFailsWith<BadRequestException> {
			values.setValues(
				admin,
				ticket.id,
				mapOf(note.id.toString() to "fine", size.id.toString() to "not a number"),
			)
		}

		assertEquals(emptyMap(), values.valuesOf(admin, ticket.id), "half the request landed")
	}

	// --- the feed ------------------------------------------------------------

	/**
	 * Two claims in one test, and the first is about the schema: `field_set` reaches the
	 * `activity` table, which is only true if `V32` restated `activity_kind_chk` correctly. A
	 * list copied from `V8` instead of `V23` would still pass every other test in this suite
	 * and fail right here.
	 */
	@Test
	fun `setting a field writes one activity row, and re-sending the same value writes none`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())

		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))
		assertEquals(1, fieldSetRows(ticket.id))

		// A form that re-submits every input on every save must not fill the feed with rows
		// saying a field is still what it already was.
		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))
		assertEquals(1, fieldSetRows(ticket.id), "an unchanged value was logged as a change")

		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "low"))
		assertEquals(2, fieldSetRows(ticket.id))
	}

	private fun fieldSetRows(ticketId: UUID): Int = jdbc.sql(
		"SELECT count(*) FROM activity WHERE entity_type = 'ticket' AND entity_id = ? AND kind = 'field_set'",
	).param(ticketId).query(Int::class.java).single()

	// --- the wire slot -------------------------------------------------------

	/**
	 * The reason this shipped ahead of demand: the slot is in the shape whether or not
	 * anything is in it. A reader that has always seen the key can learn to render it.
	 */
	@Test
	fun `a ticket with no fields defined still carries an empty map, not a missing one`() {
		val team = team()
		val ticket = ticketIn(team.id)

		assertEquals(emptyMap(), tickets.get(admin, ticket.id).customFields)
	}

	@Test
	fun `the values reach the ticket detail every screen and every reader renders`() {
		val team = team()
		val ticket = ticketIn(team.id)
		val severity = fields.define(admin, team.id, "Severity", "text", false, emptyList())
		values.setValues(admin, ticket.id, mapOf(severity.id.toString() to "high"))

		// Through `get`, through the list, and through the identifier read the MCP tool uses —
		// three doors that used to assemble a detail three ways.
		assertEquals("high", tickets.get(admin, ticket.id).customFields[severity.id])
		assertEquals(
			"high",
			tickets.list(team.id, false, false, dev.kanso.repo.TicketFilters(), ViewSortBy.UPDATED, 50, 0)
				.single().customFields[severity.id],
		)
		assertEquals(
			"high",
			tickets.getByIdentifier(team.key, ticket.number!!).customFields[severity.id],
			"the identifier read assembled its own detail and missed the fields",
		)
	}
}

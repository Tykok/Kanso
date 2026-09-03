package dev.kanso.mcp.tools

import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.mcp.stringsField
import dev.kanso.service.BadRequestException
import dev.kanso.service.CustomFieldService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketFieldService
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A status and an assignee, in **one** `TicketService.patch`.
 *
 * That is the whole design of this file. Two calls — `patch(status)` then
 * `setAssignees(…)` — would read the same and would leave a status moved and the work
 * unassigned every time the second half was refused: the ticket half-applied, the agent
 * told it failed, and a backlog holding a change nobody asked for. `TicketPatch` already
 * carries both fields and `patch` is one transaction, so there is no order in which half
 * of this survives.
 *
 * It also means the two changes reach the log, the notifications and the mirror as one
 * event — a status move and a hand-over are one decision, and a feed that showed them a
 * second apart would be describing a keystroke rather than a decision.
 */
@Service
class UpdateTicketTool(
	private val tickets: TicketService,
	private val people: McpPeople,
	private val definitions: CustomFieldService,
	private val values: TicketFieldService,
) : McpTool {

	override val name = "kanso_update_ticket"
	override val title = "Change a ticket"
	override val writes = true

	override val description = """
		Move one existing ticket's status, its assignees, or both at once. Address it by
		identifier, like `KAN-142`. Both changes are applied together or not at all.

		`assignees` replaces the list: give every email who should hold it, `[]` to take
		everybody off, or omit it to leave the assignees alone. Omitting `status` leaves the
		status alone in the same way. At least one argument that changes something is
		required — a call that changes nothing is refused rather than reported as done.

		`fields` sets the team's custom fields, by the names `kanso_get_ticket` prints: only
		the fields you name are touched, and `null` clears one. A value has to match the
		field's declared type — a number field takes `3`, not `"3"` — and a choice field takes
		one of its options.

		Do not use it to create work: `kanso_create_ticket` does that, and this refuses an
		identifier that does not exist rather than inventing it.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"ticket" to stringField("The ticket identifier, e.g. `KAN-142`."),
		"status" to stringField("The status to move it to.", STATUSES),
		"assignees" to stringsField("Who should hold it, by email or by user id. Replaces the current list."),
		// Untyped, because the shape depends on the team's own definitions and a schema
		// cannot name them: `properties` here would have to be generated per team, and the
		// tool list is one static document served to every caller. The refusal on the way in
		// names the fields that do exist, which is what a schema would have bought.
		"fields" to objectField("Custom fields to set, keyed by field name. `null` clears one."),
		required = listOf("ticket"),
	)

	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("ticket", "status", "assignees", "fields")

		val status = args.string("status")?.let(TicketStatus::from)
		val assignees = args.strings("assignees")
		val fields = args.map("fields")
		// Before the read, so a no-op costs nothing and says why. Reporting success for a
		// call that changed nothing is how an agent concludes it has done the work.
		if (status == null && assignees == null && fields.isEmpty()) {
			throw BadRequestException(
				"`$name` needs `status`, `assignees` or `fields` — this call would change nothing",
			)
		}

		val before = TicketLines.byIdentifier(args.requiredString("ticket"), tickets::getByIdentifier)
		// The fields first, and inside the same transaction as the patch, for this file's own
		// reason: a severity written and a status refused is a ticket half-moved with the
		// agent told it failed. `@Transactional` makes the pair atomic; doing the fields
		// before the patch also means their refusal — which is the one that can name a type —
		// arrives before anything has been written at all.
		val fieldSummary = setFields(actor, before, fields)
		val after = tickets.patch(
			actor = actor,
			id = before.ticket.id,
			patch = TicketPatch(status = status, assigneeIds = assignees?.let(people::resolve)),
		)

		val moved = status?.let { "status ${before.ticket.status.wire} → ${it.wire}" }
		val handed = assignees?.let {
			"assignees ${people.emailsOf(after.assigneeIds).values.joinToString().ifEmpty { "nobody" }}"
		}
		return "Updated ${after.identifier} — ${listOfNotNull(moved, handed, fieldSummary).joinToString(", ")}"
	}

	/**
	 * The team's fields, addressed by name and resolved to ids here.
	 *
	 * By name because that is the only handle an agent reliably has — the same argument
	 * `TicketLines.teamByKey` makes for addressing a team by `KAN` rather than by a UUID, and
	 * the reason `kanso_get_ticket` prints the names in the first place. Case-insensitively,
	 * because `severity` and `Severity` are the same field to everybody except a map lookup.
	 *
	 * The refusal lists the fields that do exist, which ends the exchange in one more call
	 * instead of sending the agent guessing — the discipline `McpErrors` describes. It is also
	 * where the untyped `fields` schema is paid for.
	 *
	 * Nothing about the *values* is validated here: `FieldValueCodec` owns that, it is
	 * reached through `TicketFieldService`, and a second opinion in this file would be a
	 * second answer to "what may this field hold", free to disagree with the first.
	 */
	private fun setFields(actor: User, ticket: TicketDetail, wanted: Map<String, Any?>): String? {
		if (wanted.isEmpty()) return null
		val teamId = ticket.ticket.teamId ?: throw BadRequestException(
			"${ticket.ticket.title} belongs to no team yet, and a custom field is a team's",
		)
		val defined = definitions.list(teamId).map { it.field }
		val byName = defined.associateBy { it.name.lowercase() }

		val byId = wanted.entries.associate { (rawName, value) ->
			val field = byName[rawName.trim().lowercase()] ?: throw BadRequestException(
				"No custom field `$rawName` on ${ticket.teamKey}." +
					" Its fields: ${defined.joinToString { it.name }.ifEmpty { "none yet" }}",
			)
			field.id.toString() to value
		}
		values.setValues(actor, ticket.ticket.id, byId)
		return "fields ${wanted.keys.joinToString()}"
	}

	private companion object {
		val STATUSES = TicketStatus.entries.map { it.wire }
	}
}

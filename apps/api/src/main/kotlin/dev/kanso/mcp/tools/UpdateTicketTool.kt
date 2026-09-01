package dev.kanso.mcp.tools

import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.mcp.stringsField
import dev.kanso.service.BadRequestException
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
) : McpTool {

	override val name = "kanso_update_ticket"
	override val title = "Change a ticket"
	override val writes = true

	override val description = """
		Move one existing ticket's status, its assignees, or both at once. Address it by
		identifier, like `KAN-142`. Both changes are applied together or not at all.

		`assignees` replaces the list: give every email who should hold it, `[]` to take
		everybody off, or omit it to leave the assignees alone. Omitting `status` leaves the
		status alone in the same way. At least one of the two is required — a call that
		changes nothing is refused rather than reported as done.

		Do not use it to create work: `kanso_create_ticket` does that, and this refuses an
		identifier that does not exist rather than inventing it.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"ticket" to stringField("The ticket identifier, e.g. `KAN-142`."),
		"status" to stringField("The status to move it to.", STATUSES),
		"assignees" to stringsField("Who should hold it, by email or by user id. Replaces the current list."),
		required = listOf("ticket"),
	)

	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("ticket", "status", "assignees")

		val status = args.string("status")?.let(TicketStatus::from)
		val assignees = args.strings("assignees")
		// Before the read, so a no-op costs nothing and says why. Reporting success for a
		// call that changed nothing is how an agent concludes it has done the work.
		if (status == null && assignees == null) {
			throw BadRequestException("`$name` needs `status`, `assignees`, or both — this call would change nothing")
		}

		val before = TicketLines.byIdentifier(args.requiredString("ticket"), tickets::getByIdentifier)
		val after = tickets.patch(
			actor = actor,
			id = before.ticket.id,
			patch = TicketPatch(status = status, assigneeIds = assignees?.let(people::resolve)),
		)

		val moved = status?.let { "status ${before.ticket.status.wire} → ${it.wire}" }
		val handed = assignees?.let {
			"assignees ${people.emailsOf(after.assigneeIds).values.joinToString().ifEmpty { "nobody" }}"
		}
		return "Updated ${after.identifier} — ${listOfNotNull(moved, handed).joinToString(", ")}"
	}

	private companion object {
		val STATUSES = TicketStatus.entries.map { it.wire }
	}
}

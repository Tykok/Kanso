package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The expensive read, kept separate from the cheap one.
 *
 * `kanso_list_tickets` answers a hundred tickets in twenty tokens each; this answers one
 * in as many as it takes. Two tools rather than a `verbose` flag on one, because the
 * choice is the agent's whole context budget and a flag makes it an afterthought.
 *
 * Deliberately smaller than the spec's `kanso_context`: no comments, no dependencies, no
 * linked documents, no activity. Those are four more services and four more shapes to
 * describe, and this ticket's read tools are about tickets. What is here is what the
 * writing tools need to be used correctly — the identifier, the status vocabulary in use,
 * and the assignees by the email the write tool takes back.
 */
@Service
class GetTicketTool(
	private val tickets: TicketService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_get_ticket"
	override val title = "Read a ticket"
	override val writes = false

	override val description = """
		Read one ticket in full — title, description, status, priority, estimate, dates,
		assignees, team and project — addressed by the identifier a person would type, like
		`KAN-142`.

		Do not use it to survey a backlog: it answers about exactly one ticket, and
		`kanso_list_tickets` answers about a hundred for the cost of five of these.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"ticket" to stringField("The ticket identifier, e.g. `KAN-142`."),
		required = listOf("ticket"),
	)

	@Transactional(readOnly = true)
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("ticket")

		val detail = TicketLines.byIdentifier(args.requiredString("ticket"), tickets::getByIdentifier)
		val ticket = detail.ticket
		val emails = people.emailsOf(detail.assigneeIds)

		return buildString {
			appendLine("${detail.identifier}  ${ticket.title}")
			appendLine("status: ${ticket.status.wire}    priority: ${ticket.priority.wire}")
			appendLine("estimate: ${ticket.estimate?.toString() ?: "not sized"}")
			appendLine("assignees: ${detail.assigneeIds.mapNotNull { emails[it] }.joinToString().ifEmpty { "nobody" }}")
			appendLine("team: ${detail.teamKey}    project: ${ticket.projectId?.toString() ?: "none"}")
			appendLine("start: ${ticket.start?.at?.toLocalDate()?.toString() ?: "none"}    due: ${ticket.due?.at?.toLocalDate()?.toString() ?: "none"}")
			if (ticket.archived) appendLine("archived: yes")
			appendLine()
			// Last, and unbounded: everything a caller needs to act is above it, so a long
			// description truncates the reading rather than the facts.
			append(ticket.description?.takeIf { it.isNotBlank() } ?: "(no description)")
		}
	}
}

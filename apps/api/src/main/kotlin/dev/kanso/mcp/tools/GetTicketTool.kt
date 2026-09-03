package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.CustomFieldService
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
	/**
	 * The team's field definitions, so this tool can print `Severity: high` rather than a
	 * UUID and a scalar.
	 *
	 * This is the read that pays for `V35`'s decision to key `customFields` by id on the
	 * wire. A list keyed by name would have saved this lookup and cost every reader a rename;
	 * here the cost is one query, on the tool that is explicitly the expensive one.
	 */
	private val fields: CustomFieldService,
) : McpTool {

	override val name = "kanso_get_ticket"
	override val title = "Read a ticket"
	override val writes = false

	override val description = """
		Read one ticket in full — title, description, status, priority, estimate, dates,
		assignees, team and project — addressed by the identifier a person would type, like
		`KAN-142`.

		It also lists every custom field the ticket's team has defined, by name, with its
		value or `not set`. Those names are the ones `kanso_update_ticket` takes in `fields`.

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
		val defined = ticket.teamId?.let { fields.list(it) }.orEmpty()

		return buildString {
			appendLine("${detail.identifier}  ${ticket.title}")
			appendLine("status: ${ticket.status.wire}    priority: ${ticket.priority.wire}")
			appendLine("estimate: ${ticket.estimate?.toString() ?: "not sized"}")
			appendLine("assignees: ${detail.assigneeIds.mapNotNull { emails[it] }.joinToString().ifEmpty { "nobody" }}")
			appendLine("team: ${detail.teamKey}    project: ${ticket.projectId?.toString() ?: "none"}")
			appendLine("start: ${ticket.start?.at?.toLocalDate()?.toString() ?: "none"}    due: ${ticket.due?.at?.toLocalDate()?.toString() ?: "none"}")
			if (ticket.archived) appendLine("archived: yes")
			// Every field the team has defined, by name, whether or not this ticket has a
			// value for it — and the unset ones are the reason this is worth the query. The
			// same argument the assignee column makes about printing bare emails: what a
			// reading tool prints is what a writing tool takes back, so an agent that has
			// seen `severity: not set` knows both that the field exists and what to call it
			// in `kanso_update_ticket`. Printing only the filled ones would make a team's
			// vocabulary discoverable exclusively on tickets that already use it.
			//
			// Absent entirely for a team with no fields, rather than an empty heading: a
			// section that says nothing costs an agent tokens to read and rule out.
			if (defined.isNotEmpty()) {
				appendLine()
				appendLine("custom fields:")
				for (inUse in defined) {
					val value = detail.customFields[inUse.field.id]
					appendLine("  ${inUse.field.name}: ${value?.toString() ?: "not set"}")
				}
			}
			appendLine()
			// Last, and unbounded: everything a caller needs to act is above it, so a long
			// description truncates the reading rather than the facts.
			append(ticket.description?.takeIf { it.isNotBlank() } ?: "(no description)")
		}
	}
}

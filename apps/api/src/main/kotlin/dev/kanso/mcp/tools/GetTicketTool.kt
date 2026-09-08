package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.CommentService
import dev.kanso.service.CustomFieldService
import dev.kanso.service.LabelService
import dev.kanso.service.SubTicketService
import dev.kanso.service.TicketLinkService
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
 * Still smaller than the spec's `kanso_context`: no linked documents, no activity. The
 * rule that decides what is in it has not changed — **what is here is what the writing
 * tools need in order to be used correctly**, which is the identifier, the status
 * vocabulary in use, and the assignees by the email the write tool takes back.
 *
 * Dependencies *were* on that excluded list, and KAN-20 moved them off it rather than
 * relaxing the rule: `kanso_link_tickets` and `kanso_split_ticket` are writing tools whose
 * argument is the graph, so an agent that cannot read the graph cannot use them correctly.
 * It would propose an arrow that is already drawn, or split a ticket that is already in
 * parts. Two more services on the expensive read, which is where a query belongs.
 *
 * **The comment thread came off the same list for the same reason — `KAN-30`.**
 * `kanso_triage` is a writing tool whose argument is a judgement about a request, and the
 * request is not the title: it is the four comments where the reporter gave their row
 * count and somebody else said this looks like last week's ticket. An agent that cannot
 * read the thread cannot rule on the case, and would be triaging a headline. That ticket
 * also asked for "thread summaries", which is this and nothing more: Kanso prints the
 * thread, and whoever is reading does the summarising — `PlanTool` states the house rule
 * about which of those two Kanso is allowed to be.
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
	/**
	 * The thread, oldest first — `KAN-30`. `forTicket` resolves the authors, so a line can
	 * name who said it by the email every writing tool here takes back.
	 */
	private val comments: CommentService,
	/**
	 * The team's label vocabulary — `KAN-30`. All of it, not only what this ticket wears:
	 * the same argument the custom fields make about printing the unset ones.
	 */
	private val labels: LabelService,
	/**
	 * The graph, both directions. `of` filters the far end through `TicketAccess.mayRead`
	 * and drops what this actor may not see, so an edge into somebody's private draft
	 * cannot leak a title through here — the reason this goes through the service rather
	 * than through `TicketLinkRepository.of`, which would answer with every row.
	 */
	private val links: TicketLinkService,
	private val subTickets: SubTicketService,
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

		And it lists what the ticket is attached to: the links it already has in both
		directions — what it blocks, what blocks it, what it relates to or duplicates — and
		the sub-tickets under it with how many are done. Read this before recording a link
		with `kanso_link_tickets` or splitting with `kanso_split_ticket`, so you add what is
		missing instead of what is already there.

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
		val defined = ticket.teamId?.let { fields.list(it) }.orEmpty()
		val edges = links.of(actor, ticket.id)
		val children = subTickets.children(actor, ticket.id)
		// One query for every email on the page, the ticket's own assignees and its
		// children's together. Resolving the children separately would be a second round trip
		// for a map this one already has to build.
		val emails = people.emailsOf(detail.assigneeIds + children.flatMap { it.assigneeIds })

		return buildString {
			appendLine("${detail.identifier}  ${ticket.title}")
			appendLine("status: ${ticket.status}    priority: ${ticket.priority.wire}")
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
			// The team's labels, the worn ones marked. Whole vocabulary rather than only what
			// this ticket carries, for the reason the fields above give: what a reading tool
			// prints is what a writing tool takes back, so an agent that has seen `export`
			// unworn knows both that the word exists and how to spell it in
			// `kanso_update_ticket`. Printing only the worn ones would make a team's
			// vocabulary discoverable exclusively on tickets that already use it.
			labelLine(ticket.teamId, ticket.id)?.let { appendLine(it) }
			// What the ticket is attached to, above the description for the same reason the
			// fields are: these are facts a writing tool takes back, and a long description
			// between them and the header would push them out of a reader that stops early.
			TicketStructure.links(edges)?.let { appendLine(); appendLine(it) }
			TicketStructure.children(children, subTickets.progress(listOf(ticket.id))[ticket.id], emails)
				?.let { appendLine(); appendLine(it) }
			appendLine()
			// The description before the thread, because the thread is a reply to it.
			appendLine(ticket.description?.takeIf { it.isNotBlank() } ?: "(no description)")
			// Last, and unbounded, for the reason the description used to be last: everything
			// a caller needs in order to *act* is above this, so a long argument truncates
			// the reading and not the facts. Absent entirely when nobody has said anything,
			// rather than an empty heading a reader pays to rule out.
			thread(ticket.id)?.let { appendLine(); append(it) }
		}
	}

	/**
	 * `labels: bug ✓, export` — the team's vocabulary, with a tick on what is worn.
	 *
	 * One line and not a section: a team has a handful of labels, and a heading over three
	 * words costs an agent more to skip than to read. Absent for a team that has defined
	 * none, like the fields.
	 */
	private fun labelLine(teamId: java.util.UUID?, ticketId: java.util.UUID): String? {
		if (teamId == null) return null
		val defined = labels.list(teamId)
		if (defined.isEmpty()) return null
		val worn = labels.forTicket(ticketId).map { it.id }.toSet()
		return "labels: " + defined.joinToString { if (it.id in worn) "${it.name} ✓" else it.name }
	}

	/**
	 * The comments, oldest first, one paragraph each.
	 *
	 * Bodies whole and not truncated: a thread is the case, and a case cut off mid-sentence
	 * is how an agent rules on half of one. The cost is the caller's to bear — this is the
	 * expensive read, and the tool description says so.
	 */
	private fun thread(ticketId: java.util.UUID): String? {
		val said = comments.forTicket(ticketId)
		if (said.isEmpty()) return null
		return buildString {
			appendLine("thread — ${said.size} comment(s), oldest first:")
			for (row in said) {
				appendLine()
				appendLine("  ${row.author.email}  ${row.createdAt.toLocalDate()}")
				row.body.trim().lines().forEach { appendLine("  $it") }
			}
		}.trimEnd()
	}
}

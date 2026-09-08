package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.BadRequestException
import dev.kanso.service.TicketService
import dev.kanso.service.TriageDecision
import dev.kanso.service.TriageService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One ruling, recorded — `KAN-30`.
 *
 * **The decision is the client's and the consequence is Kanso's.** That division is the
 * whole design, and it is the one `PlanTool` and `TeamWorkloadTool` already made: nothing
 * here weighs whether a request is worth a cycle or which ticket it duplicates. What this
 * knows that the model does not is that `accepted` means the active cycle, that
 * `backlogged` is a status change the mirror has to learn about, and that a ruling is
 * written once and never twice.
 *
 * Through `TriageService.decide`, not around it. That method's own docstring says why:
 * every consequence hangs off `TicketService.patch`, so a decision that wrote the status
 * column itself would be the only status change in Kanso the Notion mirror never hears
 * about. An agent's ruling is therefore indistinguishable from a person's — same cycle
 * membership, same activity row, same cascade — except in `triage_decisions.decided_by`,
 * which names the member whose token was used, and in the client recorded beside it.
 *
 * The four decisions are `TriageDecision`'s and the tool invents no fifth. `accepted`
 * refuses when there is no cycle in progress, `duplicate` refuses without the ticket it
 * duplicates, and a second ruling on the same ticket refuses in the words a person is
 * refused with — all of them from the service, so an agent and a screen cannot drift into
 * two vocabularies.
 */
@Service
class TriageTool(
	private val triage: TriageService,
	private val tickets: TicketService,
) : McpTool {

	override val name = "kanso_triage"
	override val title = "Rule on a request"
	override val writes = true

	override val description = """
		Record what has been decided about one request in the triage queue, by identifier —
		`KAN-142`. Four decisions and no others:

		- `accepted` — it is work this team is taking now. It joins the cycle in progress, and
		  the call is refused if there is none.
		- `backlogged` — real work, not now. Its status becomes the backlog.
		- `duplicate` — it is already filed. `duplicateOf` names the ticket it duplicates and
		  is required for this decision and refused for the others.
		- `closed` — no action. It ends here, with no ticket to point at.

		A ticket can be ruled on once: a second call is refused and names the decision that
		already stands. Read the case with `kanso_get_ticket` first — the description, the
		custom fields and the comment thread — and use `kanso_triage_queue` to find what is
		waiting.

		It decides nothing itself. Whether a request duplicates another, or deserves a cycle,
		is a judgement made against the case and whatever else you know; this writes it down
		and applies what it implies.

		Do not use it to move a ticket already in flight — that is `kanso_update_ticket`.
		This is the queue's own door, and only tickets nobody has ruled on are behind it.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"ticket" to stringField("The identifier of the request being ruled on, e.g. `KAN-142`."),
		"decision" to stringField("What was decided.", DECISIONS),
		"duplicateOf" to stringField(
			"The identifier of the ticket this one duplicates. Required for `duplicate`, refused otherwise.",
		),
		required = listOf("ticket", "decision"),
	)

	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("ticket", "decision", "duplicateOf")

		val subject = TicketLines.byIdentifier(args.requiredString("ticket"), tickets::getByIdentifier)
		val decision = TriageDecision.from(args.requiredString("decision"))
		// Resolved here rather than passed through as a string: the service takes an id, and
		// an agent addresses tickets by identifier everywhere else in this server. The
		// refusal for a mismatch — a `duplicateOf` on a decision that is not `duplicate` —
		// is the service's, so it reads the same as the screen's.
		val duplicateOf = args.string("duplicateOf")
			?.let { TicketLines.byIdentifier(it, tickets::getByIdentifier).ticket.id }
		if (duplicateOf != null && decision != TriageDecision.DUPLICATE) {
			throw BadRequestException(
				"A duplicate names the ticket it duplicates, and no other decision does",
			)
		}

		val ruling = triage.decide(actor, subject.ticket.id, decision, duplicateOf)

		return buildString {
			append("${subject.identifier ?: subject.ticket.id} — ${ruling.decision.wire}")
			ruling.duplicateOf?.let { append(", duplicate of ${it.identifier ?: it.ticket.id}") }
			appendLine(".")
			// What the ruling *did*, not merely what it says: an agent that reported
			// "accepted" without knowing the ticket had joined a cycle would have to ask.
			when (ruling.decision) {
				TriageDecision.ACCEPTED -> appendLine("It is in the cycle in progress.")
				TriageDecision.BACKLOGGED -> appendLine("Its status is now the backlog.")
				TriageDecision.DUPLICATE, TriageDecision.CLOSED ->
					appendLine("It is closed, and out of the queue.")
			}
			append("Recorded as ${actor.email}'s decision.")
		}
	}

	private companion object {
		val DECISIONS = TriageDecision.entries.map { it.wire }
	}
}

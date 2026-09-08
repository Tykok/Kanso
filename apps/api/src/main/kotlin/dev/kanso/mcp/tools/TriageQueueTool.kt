package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.integerField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.TeamService
import dev.kanso.service.TriageService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * What is waiting for a decision — `KAN-30`.
 *
 * The one read no filter could express. `kanso_list_tickets` answers questions about a
 * ticket's *columns*, and the triage queue is defined by the absence of a row in another
 * table: everything in the team that is not settled, not archived, not in a cycle, and
 * that nobody has ruled on. `TriageRepository.queue` is that predicate and the screen has
 * read it since `KAN-21`; this is the same answer for an agent.
 *
 * **It ranks nothing and suggests nothing**, which is the same line `kanso_team_workload`
 * draws and for the same reason. The order is the one the screen uses — oldest first,
 * because a request that has waited a fortnight is the one triage is for — and what to do
 * about each is the judgement the client's model is holding, made against the case that
 * `kanso_get_ticket` now prints and recorded with `kanso_triage`.
 */
@Service
class TriageQueueTool(
	private val triage: TriageService,
	private val teams: TeamService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_triage_queue"
	override val title = "What is waiting to be triaged"
	override val writes = false

	override val description = """
		The requests nobody has ruled on yet, oldest first, one line each: the identifier, the
		status, the priority, the title and whoever is already assigned. Address the team by
		its key, the `KAN` of `KAN-142`; sub-teams are included.

		This is the only read that answers "what needs deciding". A ticket leaves this queue
		when somebody rules on it — accepted into the cycle, sent to the backlog, marked a
		duplicate or closed — and `kanso_triage` is what records that. Read the case with
		`kanso_get_ticket` before deciding: it prints the description, the custom fields and
		the comment thread, which is where the reporter and the team have already said most
		of what matters.

		It reports the queue and recommends nothing. Whether a request is a duplicate, or
		worth a cycle, is not in this database.

		Do not use it as a list of open work — most tickets were never in this queue.
		`kanso_list_tickets` is that read.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"team" to stringField("The team's key, e.g. `KAN`. Case-insensitive. Sub-teams are included."),
		"limit" to integerField("How many to name. Defaults to 20, and the total is reported either way."),
		required = listOf("team"),
	)

	@Transactional(readOnly = true)
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("team", "limit")

		val team = TicketLines.teamByKey(teams, args.requiredString("team"))
		val limit = args.integer("limit") ?: 20
		val queue = triage.queue(team.id, limit)
		if (queue.items.isEmpty()) return "Nothing is waiting to be triaged in ${team.key}."

		return buildString {
			// The total beside the page, and they are different numbers on purpose —
			// `TriageQueue` says so. An agent that read the page size as the backlog would
			// tell somebody their queue was empty when twenty more were behind it.
			appendLine(
				"${team.key} — ${queue.total} waiting, ${queue.items.size} named here, oldest first."
			)
			appendLine()
			// One lookup for the page, not one per row — the same batching `ListTicketsTool`
			// does over the same printer.
			val emails = people.emailsOf(queue.items.flatMap { it.assigneeIds })
			queue.items.forEach { row ->
				appendLine(TicketLines.line(row, row.assigneeIds.mapNotNull(emails::get)))
			}
		}.trimEnd()
	}
}

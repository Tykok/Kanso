package dev.kanso.mcp.tools

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.TeamService
import dev.kanso.service.WorkloadRow
import dev.kanso.service.WorkloadService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Who is holding what, so that somebody else can decide who should hold the next thing.
 *
 * **This is the tool KAN-20's "propose the assignments" turns into, and the proposal is
 * not in it.** A `kanso_suggest_assignee` would have to weigh a person's plate against
 * their skills, their week, the thing they said in standup and how urgent this is — none
 * of which is in the database, and all of which the model already has from the
 * conversation it is having. Any ranking Kanso shipped would be arithmetic dressed as
 * judgement: right often enough to be trusted, wrong in exactly the cases a person cares
 * about, and impossible to correct from the client. So this answers the one half Kanso
 * genuinely knows — the plates — and `kanso_update_ticket` applies whatever the model
 * concludes. Two honest tools instead of one that pretends.
 *
 * **`WorkloadService` unchanged, and no second definition of load.** Screen 23 already
 * decided what "loaded" means, down to the arguable parts: a ticket with two assignees
 * lands whole on both plates, unsized tickets are counted and never weighed as zero, and
 * `OPEN_STATUSES` is derived from the categories rather than spelled out. An agent that
 * read a different number from the one on the chart would be reading a second Kanso.
 */
@Service
class TeamWorkloadTool(
	private val workload: WorkloadService,
	private val teams: TeamService,
) : McpTool {

	override val name = "kanso_team_workload"
	override val title = "A team's open load, per person"
	override val writes = false

	override val description = """
		What every member of a team is currently carrying, heaviest first, one line each:
		open tickets, how many of those are in progress, the points somebody has sized, how
		many carry no estimate at all, the age of their oldest open ticket, and how many
		urgent tickets they have held for more than three days. The tickets nobody is
		assigned are the last line.

		Use it before handing work out — it is the only read in this server that answers "who
		has room". Address the team by its key, the `KAN` of `KAN-142`, and pass `cycleId` to
		narrow it to one cycle's commitment instead of the whole open board.

		It reports load and recommends nobody. Weigh these plates against whatever else you
		know about the people and then record the decision with `kanso_update_ticket`.

		Do not use it to find particular tickets — it counts them and does not name them.
		`kanso_list_tickets` with an `assignee` filter names them.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"team" to stringField("The team's key, e.g. `KAN`. Case-insensitive. Sub-teams are included."),
		"cycleId" to stringField("A cycle's id, to read that cycle's commitment instead of the open board."),
		required = listOf("team"),
	)

	@Transactional(readOnly = true)
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("team", "cycleId")

		val team = TicketLines.teamByKey(teams, args.requiredString("team"))
		val held = workload.forTeam(team.id, cycleId = args.string("cycleId")?.let(TicketLines::uuid))
		val scope = if (held.cycleId == null) "the open board" else "cycle ${held.cycleId}"
		if (held.rows.isEmpty()) return "Nobody is carrying anything in ${team.key} on $scope."

		return buildString {
			appendLine("${team.key} — $scope, sub-teams included. ${held.rows.size} plate(s), heaviest first.")
			appendLine()
			appendLine(HEADER)
			held.rows.forEach { appendLine(line(it)) }
		}.trimEnd()
	}

	/**
	 * One plate. Columns rather than prose, because the reader is comparing them to each
	 * other and a sentence per person makes that a parsing job.
	 *
	 * **`open` and `started` are two different questions and the house keeps them apart.**
	 * `WorkloadService.OPEN_STATUSES` is the charge — everything not yet settled, which is
	 * what somebody is answerable for; `StatusCategory.STARTED` is the work actually in
	 * flight, which is what `CycleTimeService` measures WIP over. A person with nine open
	 * and one started has room; a person with three open and three started does not, and a
	 * single column would have hidden whichever of those the reader needed. Keyed off the
	 * category, so a status added to the enum lands in the right column with no edit here.
	 */
	private fun line(row: WorkloadRow): String = buildString {
		append((row.person?.email ?: UNASSIGNED).padEnd(30))
		append(row.total.toString().padStart(5))
		append(row.byStatus.filterKeys { it.category == StatusCategory.STARTED }.values.sum().toString().padStart(9))
		append(row.points.toString().padStart(8))
		// Printed even when it is zero, unlike everything else that is absent when empty:
		// this column is the caveat on the one before it, and a blank would read as "the
		// points speak for the whole plate" — which is the claim `WorkloadRow` exists to
		// avoid making.
		append(row.unestimated.toString().padStart(8))
		append("${row.oldestOpenDays}d".padStart(8))
		append(row.urgentOverThreeDays.toString().padStart(11))
	}

	private companion object {
		/**
		 * Parenthesised, so it cannot be mistaken for an address.
		 *
		 * The person column is a bare email with no `@` sigil in front of it —
		 * `TicketLines.line`'s rule, and here for a second reason: it is what
		 * `kanso_update_ticket` takes back in `assignees`, so every row of it has to be
		 * copyable without editing. The one row that is not an address has to *look* unlike
		 * one.
		 */
		const val UNASSIGNED = "(nobody assigned)"

		val HEADER = "person".padEnd(30) +
			"open".padStart(5) +
			"started".padStart(9) +
			"points".padStart(8) +
			"unsized".padStart(8) +
			"oldest".padStart(8) +
			"urgent>3d".padStart(11)
	}
}

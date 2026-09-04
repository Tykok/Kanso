package dev.kanso.mcp.tools

import dev.kanso.domain.Team
import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.integerField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.objectsField
import dev.kanso.mcp.stringField
import dev.kanso.mcp.stringsField
import dev.kanso.service.BadRequestException
import dev.kanso.service.ProjectDetail
import dev.kanso.service.ProjectService
import dev.kanso.service.SubTicketService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketLinkService
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A plan a model has already formed, written down in **one** call and one transaction.
 *
 * **Kanso embeds no LLM, and this is the tool where that stops being a slogan.** It would
 * be very easy to write a `kanso_plan` that takes a spec and returns tickets; it would
 * need a prompt template, a model and an API key, and it would be wrong in a new way on
 * every instance while one person maintained it. So the input here is not a spec. The
 * client's model has read the spec, decided what the tickets are, how the work divides and
 * which piece waits on which — and this records that. Every judgement arrives already
 * made. What this file knows that the model does not is the *order of the writes* and the
 * fact that they are atomic.
 *
 * **What it replaces.** Nine tickets with two parents and four dependencies is nine
 * `kanso_create_ticket` calls, two `SubTicketService.setParent` calls an agent has no tool
 * for at all, and four `kanso_link_tickets` calls — fifteen round trips whose failure mode
 * is not the fifteenth but the *twelfth*. Nine tickets filed, two parented, one dependency
 * drawn, and the agent told the plan failed; the backlog then holds a shape nobody
 * designed and no one call can undo. Here the plan lands whole or the team is untouched.
 *
 * **Local references, which is why one call is possible.** Dependencies and parenthood are
 * expressed between `ref`s the caller invents — `door`, `tools` — because the identifiers
 * do not exist until the rows do. See [PlanDraft], which reads them and refuses everything
 * they cannot mean before this file writes anything.
 *
 * **No `dry_run`, and that is a decision against the spec.** The spec defaults it to true
 * and calls that the safety story. Three things argue the other way. Its promise —
 * "nothing was written" — is what one transaction and [PlanDraft] already deliver, from
 * outside and without a second code path that can drift from the first. Defaulting it to
 * true makes every real plan two identical calls, which is the habit the spec itself warns
 * against for `kanso_update`: a preview on the way to every write trains an agent to click
 * through the preview that matters. And the confirmation an agent shows a person is *this
 * tool's own answer*, which names every ticket it filed — the same list a dry run would
 * have promised, except true.
 *
 * **It creates no structure.** A plan naming a team or a project that does not exist is
 * refused with the ones that do, never with one invented — the spec's own line, and the
 * reason it keeps `kanso_organise` separate. A project is one unchained call before this
 * one; nothing about it is at risk halfway through a plan.
 */
@Service
class PlanTool(
	private val tickets: TicketService,
	private val subTickets: SubTicketService,
	private val links: TicketLinkService,
	private val teams: TeamService,
	private val projects: ProjectService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_plan"
	override val title = "Record a plan as tickets"
	override val writes = true

	override val description = """
		File a whole plan you have already worked out — several tickets at once, which of
		them are parts of which, and which of them block which — into one team, in one
		call. Returns the identifier each ticket was given, against the name you used for
		it. Either the whole plan is filed or none of it is.

		Name each ticket with a short `ref` of your own choosing, like `schema` or `door`.
		That name is local to this one call and is never stored: it exists so you can say
		`{"from":"schema","to":"screen","type":"blocks"}` about tickets that do not have
		identifiers yet. Use `parent` on a ticket to hang it under another ticket of the
		same plan — one level deep, so a ticket that is a part cannot also have parts.

		`links` joins tickets this plan files, by `ref`. It cannot reach a ticket that
		already exists; use `kanso_link_tickets` for that, one edge per call. `blocks` is
		the only type Kanso schedules on, and a set of `blocks` links that closes a loop is
		refused with the chain that closes it, before anything is filed.

		The team must already exist, and so must the project if you name one — a plan that
		names neither is refused with a list of what there is, rather than inventing it.
		Every ticket lands in the plan's team, and in its project if there is one.

		**This tool does not read a spec and does not decide anything.** It writes down a
		plan you formed. Work out the tickets, the parts and the dependencies yourself —
		`kanso_list_tickets` and `kanso_team_workload` are what you read to do that — and
		then record the result here. Use `kanso_create_ticket` for a single ticket; this one
		is for a plan with a shape.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"team" to stringField("The team's key, e.g. `KAN`. Every ticket in the plan is filed in it."),
		"project" to stringField("A project in that team, by name. Omit to file the plan into no project."),
		"tickets" to objectsField(
			"The tickets, in the order they should be filed. One to ${PlanDraft.MAX_TICKETS} of them.",
			objectSchema(
				"ref" to stringField("Your name for this ticket inside this call, e.g. `schema`. Not stored."),
				"title" to stringField("One line, what the work is."),
				"description" to stringField("The body. Markdown, optional."),
				"status" to stringField("Default `todo`.", STATUSES),
				"priority" to stringField("Default `none`.", PRIORITIES),
				"estimate" to integerField("Points. Omit if this ticket is not sized."),
				"assignees" to stringsField("Who is doing it, by email or by user id."),
				"parent" to stringField("The `ref` of another ticket in this plan, to file this one under it."),
				required = listOf("ref", "title"),
			),
		),
		"links" to objectsField(
			"Dependencies between the plan's own tickets. Up to ${PlanDraft.MAX_LINKS}.",
			objectSchema(
				"from" to stringField("The `ref` the sentence is about."),
				"to" to stringField("The `ref` at the other end."),
				"type" to stringField("Which relationship, read as `from <type> to`.", TYPES),
				required = listOf("from", "to", "type"),
			),
		),
		required = listOf("team", "tickets"),
	)

	/**
	 * One transaction, and inside it three passes in an order that is not arbitrary.
	 *
	 * Everything is read and resolved first — see [PlanDraft] — so a plan that cannot mean
	 * what it says costs nothing and says which `ref` is wrong. Then the rows, then the
	 * parents, then the edges. Parents and edges come after *all* the rows because a plan
	 * may name a parent that appears later in the list, and refusing that ordering would be
	 * a rule about the shape of the JSON rather than about the backlog.
	 */
	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("team", "project", "tickets", "links")

		val team = TicketLines.teamByKey(teams, args.requiredString("team"))
		val project = args.string("project")?.let { projectIn(team, it) }
		val draft = PlanDraft.read(name, args, people)

		val filed = draft.tickets.associate { planned ->
			planned.ref to tickets.create(
				actor = actor,
				teamId = team.id,
				title = planned.title,
				description = planned.description,
				status = planned.status,
				priority = planned.priority,
				// Absent for `CreateTicketTool`'s reason: `KansoInstant` carries whether the time
				// of day is meaningful, and an agent handing over a date has not answered that.
				// A `blocks` edge moves dates through `ScheduleService` anyway, which is the
				// scheduling this surface does offer.
				start = null,
				due = null,
				projectId = project?.project?.id,
				assigneeIds = planned.assigneeIds,
				docIds = emptyList(),
				estimate = planned.estimate,
			)
		}

		// Every id known before either of these runs, which is what a `ref` bought.
		for (planned in draft.tickets) {
			val parent = planned.parent ?: continue
			subTickets.setParent(actor, id(filed, planned.ref), id(filed, parent))
		}
		val cascaded = draft.links.sumOf { edge ->
			links.link(actor, id(filed, edge.from), id(filed, edge.to), edge.type).size
		}

		return report(team, project, draft, filed, cascaded)
	}

	/**
	 * A project of this team, by name, or the refusal that names the ones there are.
	 *
	 * By name rather than by id, `TicketLines.teamByKey`'s reason: an agent holds what a
	 * person said, and nobody says a UUID out loud. Archived projects are left out of both
	 * the match and the list — filing new work into a project somebody closed is not a thing
	 * a plan means to do, and offering it in the refusal would invite it.
	 */
	private fun projectIn(team: Team, named: String): ProjectDetail {
		val live = projects.list(teamId = team.id, includeDescendants = false, includeArchived = false)
		return live.firstOrNull { it.project.name.equals(named, ignoreCase = true) }
			?: throw BadRequestException(
				"No project named `$named` in ${team.key}." +
					" Projects in ${team.key}: ${live.joinToString { it.project.name }.ifEmpty { "none yet" }}." +
					" This tool files into a project that exists; it does not create one",
			)
	}

	/**
	 * A `ref` back to the row it named.
	 *
	 * [PlanDraft] has already refused every reference that is not in the plan, so a miss
	 * here is this file having lost a ticket between two passes rather than a caller's
	 * mistake — which is why it raises instead of returning null. `!!` would say the same
	 * thing with a stack trace that names neither the map nor the `ref`.
	 */
	private fun id(filed: Map<String, TicketDetail>, ref: String) =
		filed[ref]?.ticket?.id ?: error("`$ref` passed validation and then had no ticket")

	/**
	 * What was filed, as the two columns an agent needs and nothing else.
	 *
	 * The `ref` is printed beside the identifier, and that pairing is the whole point of the
	 * answer: the model has been reasoning in its own names for the length of the plan, and
	 * this is the one place the two vocabularies meet. Without it, an agent holding nine
	 * identifiers in filing order has to count rows to know which is `schema` — and will
	 * eventually miscount and tell somebody the wrong ticket number.
	 *
	 * The links are echoed through [TicketStructure.phrase] rather than as `from type to`,
	 * so the sentence reads the way `kanso_get_ticket` will read it back on the next call.
	 */
	private fun report(
		team: Team,
		project: ProjectDetail?,
		draft: PlanDraft,
		filed: Map<String, TicketDetail>,
		cascaded: Int,
	): String = buildString {
		val into = project?.let { ", project ${it.project.name}" }.orEmpty()
		appendLine("Filed ${filed.size} ticket(s) in ${team.key}$into:")
		val width = filed.keys.maxOf { it.length }
		for (planned in draft.tickets) {
			val detail = filed.getValue(planned.ref)
			val under = planned.parent?.let { " (part of $it)" }.orEmpty()
			appendLine("  ${planned.ref.padEnd(width)}  ${detail.identifier}  ${detail.ticket.title}$under")
		}
		if (draft.links.isNotEmpty()) {
			appendLine("links:")
			for (edge in draft.links) {
				val from = filed.getValue(edge.from).identifier
				val to = filed.getValue(edge.to).identifier
				appendLine("  $from ${TicketStructure.phrase(edge.type, outgoing = true)} $to")
			}
		}
		// Only when it happened. A plan of `todo` tickets with no dates cascades nothing, and a
		// line saying "rescheduled 0" is a line an agent pays to read and rule out.
		if (cascaded > 0) appendLine("Rescheduled $cascaded ticket(s) downstream of the new links.")
	}.trimEnd()

	private companion object {
		val STATUSES = TicketStatus.entries.map { it.wire }
		val PRIORITIES = TicketPriority.entries.map { it.wire }
		val TYPES = TicketLinkType.entries.map { it.wire }
	}
}

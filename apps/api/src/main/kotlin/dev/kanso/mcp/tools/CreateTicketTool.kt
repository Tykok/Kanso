package dev.kanso.mcp.tools

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.integerField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.mcp.stringsField
import dev.kanso.service.SubTicketService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One ticket, through `TicketService.create` and nothing else.
 *
 * Everything that makes a Kanso ticket a Kanso ticket happens in that call: the number
 * comes off the team's counter under a row lock, the access rule runs before the lock is
 * taken, the activity row is written, the sync job is enqueued in the same transaction,
 * and the realtime event fires after it commits. A tool that reached `TicketRepository`
 * would produce a row with no number, no history and no mirror — which is why the rule is
 * "every write goes through the services" and not a preference about layering.
 *
 * **Dates are deliberately absent from this surface.** `KansoInstant` carries an instant
 * *and* whether the time of day is meaningful, and an agent handing over `2026-09-04` has
 * not answered the second half. Guessing it writes a wrong granularity into the timeline
 * and into Notion. `kanso_update_ticket` does not set them either; the UI does, and a
 * later tool can once there is a shape for saying "a day, not a moment".
 */
@Service
class CreateTicketTool(
	private val tickets: TicketService,
	private val subTickets: SubTicketService,
	private val teams: TeamService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_create_ticket"
	override val title = "Create a ticket"
	override val writes = true

	override val description = """
		File one new ticket in a team. Returns the identifier it was given, like `KAN-143`.
		The ticket is created as the person who authorised this connection, in a team they
		belong to — it is refused in any other, with the same words they would be refused
		with in the app.

		Set `parent` to an existing ticket's identifier to file this one as a part of it.
		Sub-tickets are one level deep, so a ticket that is already a part cannot be named as
		a parent — name its parent instead.

		Do not use it to change something that already exists: `kanso_update_ticket` moves a
		status or an assignee, and filing a second ticket instead leaves the first one wrong.
		Do not call it in a loop to import a list — every call is one ticket, and a backlog
		filled that way is one somebody has to empty by hand. To file several tickets at once
		with a shape between them — parts, or dependencies — use `kanso_plan`.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"team" to stringField("The team's key, e.g. `KAN`. The ticket's number comes from it."),
		"title" to stringField("One line, what the work is."),
		"description" to stringField("The body. Markdown, optional."),
		"status" to stringField("Default `todo`.", STATUSES),
		"priority" to stringField("Default `none`.", PRIORITIES),
		"estimate" to integerField("Points. Omit if nobody has sized it — 0 is not the same as unsized."),
		"assignees" to stringsField("Who is doing it, by email or by user id. Omit for nobody."),
		"projectId" to stringField("The project's id, which must belong to the same team."),
		"parent" to stringField("An existing ticket to file this one under, e.g. `KAN-142`."),
		required = listOf("team", "title"),
	)

	/**
	 * One transaction around the whole call, which is what makes "refused rather than
	 * half-applied" true of everything in it — the assignee resolution included. An email
	 * nobody answers to is raised before the insert, and a service refusal after it rolls
	 * the insert back.
	 */
	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown(
			"team", "title", "description", "status", "priority", "estimate", "assignees", "projectId", "parent",
		)

		val team = TicketLines.teamByKey(teams, args.requiredString("team"))
		// Read and refused before the insert, so a parent nobody can be filed under costs no
		// row — `SplitTicketTool`'s rule about a refusal that never wrote, on one ticket.
		val parent = args.string("parent")?.let { named ->
			val found = TicketLines.byIdentifier(named, tickets::getByIdentifier)
			TicketStructure.refuseNesting(found, named, instead = "name its parent instead")
			found
		}

		val filed = tickets.create(
			actor = actor,
			teamId = team.id,
			title = args.requiredString("title"),
			description = args.string("description"),
			status = TicketStatus.from(args.string("status") ?: TicketStatus.TODO.wire),
			priority = TicketPriority.from(args.string("priority") ?: TicketPriority.NONE.wire),
			start = null,
			due = null,
			projectId = args.string("projectId")?.let(TicketLines::uuid),
			assigneeIds = people.resolve(args.strings("assignees").orEmpty()),
			docIds = emptyList(),
			estimate = args.integer("estimate"),
		)

		// Two writes rather than one, inside the transaction above, which is what
		// `SplitTicketTool` already does and why `TicketService.create` did not have to grow a
		// parameter. `SubTicketService` stays the only thing that hangs a ticket under
		// another — one answer to "may this be a part of that", reached from both tools — and
		// a refusal it raises here rolls the insert back with it.
		parent?.let { subTickets.setParent(actor, filed.ticket.id, it.ticket.id) }

		val under = parent?.let { " — part of ${it.identifier}" }.orEmpty()
		return "Created ${filed.identifier} — ${filed.ticket.title}$under"
	}

	private companion object {
		val STATUSES = TicketStatus.entries.map { it.wire }
		val PRIORITIES = TicketPriority.entries.map { it.wire }
	}
}

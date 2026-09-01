package dev.kanso.mcp.tools

import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.booleanField
import dev.kanso.mcp.integerField
import dev.kanso.mcp.objectField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.TeamService
import dev.kanso.service.TicketFilterVocabulary
import dev.kanso.service.TicketService
import dev.kanso.service.ViewSortBy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The cheap read: a team's backlog, filtered by the vocabulary the rest of Kanso filters
 * by.
 *
 * **`filters` is `TicketFilterVocabulary`, not a dialect of it.** That vocabulary was
 * unified across a query string and a saved view's jsonb at some cost, and the object
 * arriving here is handed to `parseServed` untouched — same twelve facets, same aliases,
 * same refusal for a thirteenth. A tool that re-declared them would be the third copy,
 * and the third copy is the one that drifts: a chip the UI can draw and an agent cannot
 * ask for, or the reverse.
 *
 * The split between argument and filter is `TicketController.SCOPE_PARAMS`, kept exactly:
 * `team`, `includeDescendants`, `includeArchived`, `limit`, `offset` and `sort` are the
 * walls of the room, everything else is a chip in it.
 */
@Service
class ListTicketsTool(
	private val tickets: TicketService,
	private val teams: TeamService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_list_tickets"
	override val title = "List tickets"
	override val writes = false

	override val description = """
		List a team's tickets as one compact line each: identifier, status, priority,
		title, assignees, due date. Use this to find work — to see what is in progress, what
		is unassigned, what is overdue. Address the team by its key, the `KAN` of `KAN-142`.

		Do not use it to read one ticket you already know the identifier of: `kanso_get_ticket`
		returns the description, the dates and the whole of it, and this returns a line.
		Filters are the same ones the Kanso UI offers; asking for one that is not served is
		refused with the list of those that are.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"team" to stringField("The team's key, e.g. `KAN`. Case-insensitive."),
		"filters" to objectField(
			"Facets, by the names Kanso serves: status, statusNot, priority, project," +
				" assignee, unassigned, cycle, label, openedForDays, unestimated, estimateMin," +
				" estimateMax. Ids where a facet names a thing; `status` and `priority` take" +
				" their wire values (`in_progress`, `high`). A name outside this list is refused.",
		),
		"includeDescendants" to booleanField("Include the team's sub-teams. Default false."),
		"includeArchived" to booleanField("Include archived tickets. Default false."),
		"sort" to stringField("priority, updated, created or title. Default updated.", SORTS),
		"limit" to integerField("How many lines, 1 to 200. Default 50."),
		"offset" to integerField("How many to skip, for a second page. Default 0."),
		required = listOf("team"),
	)

	/**
	 * `readOnly` is the structural half of "this tool cannot write": Spring puts the JDBC
	 * connection in read-only mode, so an insert that reached here would be refused by
	 * Postgres rather than by a reviewer. The same reason `NotionImportService.preview` is
	 * declared that way.
	 */
	@Transactional(readOnly = true)
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("team", "filters", "includeDescendants", "includeArchived", "sort", "limit", "offset")

		val team = TicketLines.teamByKey(teams, args.requiredString("team"))
		// Folded onto the names they are the singular of, not substituted — `projectId` and
		// `project` in one call ask for either, which is what every multi-valued facet does
		// with two values. `TicketController.filtersFrom` folds them the same way, and the
		// parser flattens a one-element list back to the single value it came from.
		val asked = buildMap<String, MutableList<Any?>> {
			args.map("filters").forEach { (asked, value) ->
				val slot = getOrPut(TicketFilterVocabulary.ALIASES[asked] ?: asked) { mutableListOf() }
				if (value is List<*>) slot.addAll(value) else slot.add(value)
			}
		}

		val found = tickets.list(
			teamId = team.id,
			includeDescendants = args.boolean("includeDescendants", default = false),
			includeArchived = args.boolean("includeArchived", default = false),
			filters = TicketFilterVocabulary.parseServed(asked),
			sortBy = ViewSortBy.from(args.string("sort") ?: ViewSortBy.UPDATED.wire),
			// Clamped rather than refused, exactly as `TicketController.list` clamps it: a
			// limit is a preference about the answer's size, and there is no wrong one to
			// refuse — only one to bring inside the page this endpoint will serve.
			limit = (args.integer("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
			offset = (args.integer("offset") ?: 0).coerceAtLeast(0).toLong(),
		)

		if (found.isEmpty()) return "No tickets in ${team.key} match."

		val emails = people.emailsOf(found.flatMap { it.assigneeIds }.toSet())
		return found.joinToString("\n") { detail ->
			TicketLines.line(detail, detail.assigneeIds.mapNotNull { emails[it] })
		} + "\n\n${found.size} ticket(s) in ${team.key}."
	}

	private companion object {
		val SORTS = ViewSortBy.entries.map { it.wire }
		const val DEFAULT_LIMIT = 50
		const val MAX_LIMIT = 200
	}
}

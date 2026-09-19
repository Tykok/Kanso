package dev.kanso.service

import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.repo.TicketFilters
import java.util.UUID

/**
 * The names a filter may be asked by, and the only place that turns them into a
 * [TicketFilters].
 *
 * There is one vocabulary because there is one predicate. A saved view asks in jsonb and
 * `GET /api/tickets` asks in a query string, so the values arrive as different Java types
 * for the same question — `true` against `"true"`, `3` against `"3"` — and every reader
 * below takes both. That is the entire cost of the two doors, and it is paid once here
 * rather than by a second set of clauses in a second repository.
 *
 * [SERVED] is the gate, and it is deliberately a refusal rather than a shrug. A name
 * nobody serves is a 400 on the way in, because a chip that displays and does not filter
 * is worse than one that was refused: the reader has no way to tell the list is wrong.
 * The list endpoint validates through the same gate for the same reason — `?labelColour=
 * indigo` silently ignored returns a page that looks filtered and is not.
 */
object TicketFilterVocabulary {

	/**
	 * The facets the matcher actually implements.
	 *
	 * `label` was deliberately absent while `V8` had not landed: the drawing's third chip
	 * is `Étiquette synchro`, and a chip stored and drawn but never honoured is worse than
	 * one refused. `V8` landed with `labels` and `ticket_labels`, so it is served. It holds
	 * label *ids*, like `project`, `assignee` and `cycle`: labels are team-scoped, a view
	 * reaches into descendant teams, and two of them may both own the name `sync`.
	 *
	 * The scope of a query is not in here and must not be: `teamId`, `includeDescendants`,
	 * `includeArchived`, `limit`, `offset` and `sort` are the walls of the room, not chips
	 * in it, and `dev.kanso.repo.TicketScope` says why.
	 */
	val SERVED = setOf(
		"status",
		"statusNot",
		/**
		 * "Whatever these teams call work in this state" — `KAN-90`.
		 *
		 * Beside `status` and not replacing it: a reader who picked a word off a chip asked
		 * for that status and no other. This is what a question spanning teams has to ask
		 * instead, because no single team's keys can express it — a person's own open work
		 * reaches every team they are in, and each may spell "open" differently. Without
		 * it, `use-my-work.ts` lists Kanso's four open keys and silently omits every
		 * ticket sitting in a word its team invented, under a count that includes them.
		 */
		"category",
		"priority",
		"project",
		"assignee",
		"unassigned",
		"cycle",
		"label",
		"openedForDays",
		// The three the estimate answers. `unestimated` is separate from the two bounds
		// because null is not a small number: "not sized yet" is the question a planning
		// session opens with, and no `estimateMax` can express it.
		//
		// The bounds are not checked against `EffortPoints.SCALE` on the way in, unlike
		// `status` and `priority` beside them: they are comparisons, not vocabulary, and
		// `estimateMin: 4` is the legitimate question "bigger than a 3".
		"unestimated",
		"estimateMin",
		"estimateMax",
		/**
		 * "Overdue" — the due date has gone by on work nobody has closed.
		 *
		 * A bare key like `unassigned` and `unestimated` beside it, not an `is:` prefix.
		 * This vocabulary has no prefixes, and inventing one for a single word would be a
		 * second grammar that every reader of the first has to learn.
		 *
		 * It is `TimelineService.isLate`'s definition and not a second one. That is the
		 * whole ruling behind this key: one word for the badge, the filter and the bar, so
		 * a saved view and a timeline can never disagree about which tickets are late.
		 */
		"late",
	)

	/**
	 * The two aliases `GET /api/tickets` has always accepted, mapped onto the names they
	 * were the singular of. Kept working rather than renamed: the web app sends
	 * `projectId` today and the MCP server and the public API will, and an alias over one
	 * vocabulary costs a line here, where breaking them costs every caller a release.
	 *
	 * They are folded in, not substituted — `?projectId=a&project=b` asks for either,
	 * which is what every other multi-valued facet does with two values.
	 */
	val ALIASES = mapOf("projectId" to "project", "assigneeId" to "assignee")

	/**
	 * Refuses a name nobody serves, and a value nobody can read — the second by parsing,
	 * because a parser that already refuses what it cannot read is the only honest
	 * definition of "valid" here, and a separate checker would be a second one to drift.
	 *
	 * Both are checked on the way in rather than at match time, so a bad filter is a 400
	 * on the write or the request that introduced it instead of a wrong list every time
	 * the view is opened afterwards.
	 */
	fun validate(filters: Map<String, Any?>) {
		parseServed(filters)
	}

	/** [validate] and [parse] in one pass — what a request that both checks and runs needs. */
	fun parseServed(filters: Map<String, Any?>): TicketFilters {
		val unknown = filters.keys - SERVED
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"These filters are not served: ${unknown.sorted().joinToString()}." +
					" Served: ${SERVED.sorted().joinToString()}",
			)
		}
		return parse(filters)
	}

	fun parse(filters: Map<String, Any?>): TicketFilters = TicketFilters(
		statuses = strings(filters["status"]).map(DefaultStatus::from),
		statusesExcluded = strings(filters["statusNot"]).map(DefaultStatus::from),
		categories = strings(filters["category"]).map(StatusCategory::from),
		priorities = strings(filters["priority"]).map(TicketPriority::from),
		projectIds = uuids("project", filters["project"]),
		assigneeIds = uuids("assignee", filters["assignee"]),
		unassigned = flag(filters["unassigned"]),
		cycleIds = uuids("cycle", filters["cycle"]),
		labelIds = uuids("label", filters["label"]),
		openedMoreThanDaysAgo = number("openedForDays", filters["openedForDays"]),
		unestimated = flag(filters["unestimated"]),
		late = flag(filters["late"]),
		estimateMin = number("estimateMin", filters["estimateMin"]),
		estimateMax = number("estimateMax", filters["estimateMax"]),
	)

	/** One value or a list of them, flattened: a facet asked once is a facet asked. */
	private fun strings(value: Any?): List<String> = when (value) {
		null -> emptyList()
		is List<*> -> value.mapNotNull { it?.toString() }
		else -> listOf(value.toString())
	}

	private fun uuids(name: String, value: Any?): List<UUID> = strings(value).map {
		try {
			UUID.fromString(it)
		} catch (e: IllegalArgumentException) {
			throw BadRequestException("`$name` takes ids, and `$it` is not one")
		}
	}

	/**
	 * A jsonb `true`, the string a query parameter carries, or the bare `?unassigned` a
	 * hand-written URL is likely to contain — Spring hands that one over as `""`, and
	 * refusing it would be pedantry about a request whose meaning is not in doubt.
	 *
	 * Everything else is false rather than an error, including an explicit `false`: that
	 * is a chip the reader has taken off, and "no" is a complete answer to "is this on".
	 */
	private fun flag(value: Any?): Boolean = when (value) {
		null -> false
		is Boolean -> value
		is List<*> -> value.any { flag(it) }
		else -> value.toString().let { it.isEmpty() || it.equals("true", ignoreCase = true) || it == "1" }
	}

	/**
	 * Refused rather than dropped when it cannot be read. Silently ignoring `estimateMin=
	 * soon` would widen the answer, and a list that is wider than asked for is the failure
	 * this whole gate exists to prevent.
	 */
	private fun number(name: String, value: Any?): Int? {
		val raw = when (value) {
			null -> return null
			is Number -> return value.toInt()
			is List<*> -> value.lastOrNull() ?: return null
			else -> value
		}
		if (raw is Number) return raw.toInt()
		return raw.toString().toIntOrNull()
			?: throw BadRequestException("`$name` takes a whole number, and `$raw` is not one")
	}
}

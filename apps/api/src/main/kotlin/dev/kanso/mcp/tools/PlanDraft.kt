package dev.kanso.mcp.tools

import dev.kanso.domain.EffortPoints
import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpPeople
import dev.kanso.service.BadRequestException
import java.util.UUID

/**
 * A whole plan, read and resolved, **before `kanso_plan` writes its first row**.
 *
 * This file is the answer to the spec's `dry_run`, and it is worth saying why it replaces
 * it rather than implements it. A dry run's promise is "nothing was written"; a pre-pass's
 * promise is "nothing was *writable* yet", which is the same promise obtained without a
 * second mode to keep in step with the first. `SplitTicketTool` made that argument for a
 * list of parts and this is the same argument for a graph — every name resolved, every
 * word checked against its vocabulary, every reference matched to something in the plan,
 * and the loop refused, all while the transaction below still holds nothing.
 *
 * **Local references are the reason `kanso_plan` can be one call at all.** An agent
 * planning nine tickets cannot name a dependency between them: the identifiers do not
 * exist until the rows do, so anything expressed in `KAN-…` has to wait for a second
 * round trip, and the six chained calls KAN-72 names are exactly that wait. A `ref` is
 * whatever the caller wants to call a ticket for the length of one request — `schema`,
 * `door`, `2b` — and it is the vocabulary the refusals speak back, because it is the only
 * vocabulary the caller has at the moment it is refused.
 *
 * **Nothing here decides anything.** The plan arrives already made: what the tickets are,
 * how they divide, which blocks which, what hangs under what. This reads it, refuses what
 * it cannot mean, and hands over a value. There is no template, no heuristic and nothing
 * that looks at a title — KAN-20's constraint, in the one file most tempted to break it.
 */
internal class PlanDraft private constructor(
	val tickets: List<PlannedTicket>,
	val links: List<PlannedLink>,
) {

	internal companion object {

		/**
		 * The spec's bound, refused rather than truncated for `SplitTicketTool`'s reason: a
		 * plan silently shortened to fifty is a plan whose dependencies point at tickets that
		 * were never filed, and nobody reading the backlog afterwards can see which.
		 */
		const val MAX_TICKETS = 50

		/**
		 * The spec bounds the tickets and not the edges, which leaves n² of them available to
		 * an agent in a loop — and each one walks the graph for a cycle and cascades dates, so
		 * the edges are the more expensive half. A hundred is past any plan a person would
		 * read and still one screen of them.
		 */
		const val MAX_LINKS = 100

		/**
		 * A `ref` that reads like `KAN-12`, refused.
		 *
		 * The one confusion this surface invites: `links` takes references into *this* plan,
		 * and an agent that has spent the conversation typing identifiers will eventually type
		 * one here. Left accepted, `KAN-12` would become the name of a brand-new ticket and the
		 * edge would be drawn between two rows the caller did not mean — a plan that succeeds
		 * and is wrong, which is worse than any refusal.
		 *
		 * A team key may carry digits (`Q3F9A-1` is a real identifier), so this is wider than
		 * "letters then a number" — and wide enough to also catch a `ref` a caller genuinely
		 * named `step-1`. That overlap costs nothing: this is only ever asked about an endpoint
		 * already known not to be in the plan, so both readings end in a refusal, and only the
		 * sentence explaining it differs. It says "looks like" for that reason.
		 */
		private val IDENTIFIER_SHAPED = Regex("^[A-Za-z][A-Za-z0-9]*-\\d+$")

		fun read(tool: String, args: McpArguments, people: McpPeople): PlanDraft {
			val given = args.objects("tickets")
			if (given.isEmpty()) throw BadRequestException("`$tool` needs at least one ticket in `tickets`")
			if (given.size > MAX_TICKETS) {
				throw BadRequestException("`$tool` takes at most $MAX_TICKETS tickets, and this plan has ${given.size}")
			}

			val tickets = given.map { read(it, people) }
			val byRef = tickets.associateBy { it.ref }
			// Said before anything else about references, because every refusal below names one
			// and two tickets answering to `door` would make those sentences ambiguous.
			refuseRepeatedRefs(tool, tickets)
			tickets.forEach { refuseBadParent(tool, it, byRef) }

			val links = args.objects("links").map { read(it) }
			if (links.size > MAX_LINKS) {
				throw BadRequestException("`$tool` takes at most $MAX_LINKS links, and this plan has ${links.size}")
			}
			links.forEach { refuseBadEnds(tool, it, byRef) }
			refuseLoop(tool, links)

			return PlanDraft(tickets, links)
		}

		private fun read(ticket: McpArguments, people: McpPeople): PlannedTicket {
			ticket.refuseUnknown("ref", "title", "description", "status", "priority", "estimate", "assignees", "parent")
			val ref = ticket.requiredString("ref")
			return PlannedTicket(
				ref = ref,
				title = ticket.requiredString("title"),
				description = ticket.string("description"),
				status = TicketStatus.from(ticket.string("status") ?: TicketStatus.TODO.wire),
				priority = TicketPriority.from(ticket.string("priority") ?: TicketPriority.NONE.wire),
				estimate = estimateOf(ref, ticket),
				assigneeIds = people.resolve(ticket.strings("assignees").orEmpty()),
				parent = ticket.string("parent"),
			)
		}

		/**
		 * The estimate, checked against the scale **here** rather than where it is written.
		 *
		 * `TicketService.create` already calls `EffortPoints.from`, and left to it this was the
		 * last refusal in the whole tool that could arrive after a row had been inserted: a
		 * plan whose ninth ticket is estimated `4` would have filed eight and then rolled them
		 * back. Calling the domain's own function earlier is not a second definition of the
		 * scale — it is the same function, asked before the first insert, which is what makes
		 * "a refusal wrote nothing" true of every refusal this tool has rather than most of
		 * them. It is also the difference between a guard that can be proven from outside and
		 * one that cannot: a `@Transactional` suite joins the transaction and never observes
		 * its own rollback.
		 *
		 * The message is re-raised with the `ref` in front of it because `EffortPoints` answers
		 * about a number and says nothing about which of fifty tickets carried it.
		 */
		private fun estimateOf(ref: String, ticket: McpArguments): Int? =
			runCatching { EffortPoints.from(ticket.integer("estimate")) }
				.getOrElse { offScale -> throw BadRequestException("`$ref`: ${offScale.message}") }

		private fun read(link: McpArguments): PlannedLink {
			link.refuseUnknown("from", "to", "type")
			return PlannedLink(
				from = link.requiredString("from"),
				to = link.requiredString("to"),
				type = TicketLinkType.from(link.requiredString("type")),
			)
		}

		private fun refuseRepeatedRefs(tool: String, tickets: List<PlannedTicket>) {
			val repeated = tickets.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
			if (repeated.isNotEmpty()) {
				throw BadRequestException(
					"`$tool` needs one `ref` per ticket, and ${repeated.sorted().joinToString()}" +
						" names more than one",
				)
			}
		}

		/**
		 * A parent that is not in this plan, is the ticket itself, or is a child of something
		 * else — each refused in the reference the caller typed.
		 *
		 * `SubTicketService.parentRefusal` is the authority on all three and it still runs on
		 * every child inside the transaction. This is not a second rule; it is the same verdict
		 * reached early enough to write nothing, and said in `ref`s rather than in the UUIDs
		 * that method answers with — the rewrite `SplitTicketTool.refuseNesting` performs for
		 * the same reader, and the one KAN-70 is about.
		 *
		 * The third clause reads as "the parent may not itself have a parent" and covers the
		 * rule from both ends: a plan asking for three levels is refused whichever of the two
		 * links it declared first, because the middle ticket is the one that cannot be both.
		 */
		private fun refuseBadParent(tool: String, ticket: PlannedTicket, byRef: Map<String, PlannedTicket>) {
			val parent = ticket.parent ?: return
			if (parent == ticket.ref) {
				throw BadRequestException("`$tool`: `${ticket.ref}` cannot be its own parent")
			}
			val under = byRef[parent] ?: throw BadRequestException(
				"`$tool`: `${ticket.ref}` names `$parent` as its parent, and no ticket in this plan is `$parent`." +
					" A parent is a `ref` in this same plan — to file under a ticket that already exists," +
					" use `kanso_create_ticket` with `parent`, or `kanso_split_ticket`",
			)
			if (under.parent != null) {
				throw BadRequestException(
					"`$tool`: `$parent` is itself a part of `${under.parent}`, and sub-tickets do not nest —" +
						" hang `${ticket.ref}` under `${under.parent}` instead",
				)
			}
		}

		/**
		 * Both ends of an edge have to be tickets this plan files.
		 *
		 * **This is a fence and not an oversight.** Accepting `KAN-12` at one end would make
		 * `kanso_plan` a tool that rewires an existing backlog, and it would cost the refusal
		 * above its completeness: the new `blocks` edges are disjoint from every edge already
		 * stored — the tickets they join do not exist yet — which is the whole reason
		 * [refuseLoop] can be trusted to see every loop a plan could close, and therefore the
		 * reason a plan with a cycle in it writes nothing at all. One edge into standing work
		 * is `kanso_link_tickets`, which is one call, and the refusal says so.
		 */
		private fun refuseBadEnds(tool: String, link: PlannedLink, byRef: Map<String, PlannedTicket>) {
			for (end in listOf(link.from, link.to)) {
				if (end in byRef) continue
				val why = if (IDENTIFIER_SHAPED.matches(end)) {
					"`$end` looks like a ticket identifier, and `links` joins tickets *this plan* files, by `ref`"
				} else {
					"no ticket in this plan is `$end`"
				}
				throw BadRequestException(
					"`$tool`: $why. To link work that already exists, use `kanso_link_tickets`." +
						" This plan files: ${byRef.keys.joinToString()}",
				)
			}
			if (link.from == link.to) {
				throw BadRequestException("`$tool`: `${link.from}` cannot ${link.type.wire} itself")
			}
		}

		/**
		 * The loop, refused in the references the caller typed and with the chain that closes
		 * it.
		 *
		 * `ScheduleService.linkRefusal` is the authority on cycles and it runs on every edge
		 * inside the transaction, so this is again the same verdict reached early rather than a
		 * second one. Reaching it early is what makes the guard **provable**: by the time
		 * `ScheduleService` sees the seventh edge, six tickets exist and are about to be rolled
		 * back, and the chain it names is built of identifiers that will not exist a moment
		 * later — a sentence an agent cannot act on, describing rows nobody can look up.
		 *
		 * Only [TicketLinkType.BLOCKS] is walked, because it is the only type that orders
		 * anything: `relates` and `duplicates` are notes on the graph and the scheduler has
		 * never looked at them. Reading `type` here rather than every edge is the same
		 * narrowing `DependencyRepository` does in SQL.
		 */
		private fun refuseLoop(tool: String, links: List<PlannedLink>) {
			val downstream = links.filter { it.type == TicketLinkType.BLOCKS }
				.groupBy({ it.from }, { it.to })
			val settled = mutableSetOf<String>()
			for (start in downstream.keys) {
				val loop = loopFrom(start, downstream, mutableListOf(), settled) ?: continue
				throw BadRequestException(
					"`$tool`: these `blocks` links close a loop, so nothing in the plan could ever start —" +
						" ${loop.joinToString(" -> ")}",
				)
			}
		}

		/**
		 * @return the chain that closes, from the repeated reference back round to it, or null
		 *   when nothing downstream of [ref] returns to it. [settled] is what has been walked
		 *   and cleared, so a diamond is not re-walked once per path.
		 */
		private fun loopFrom(
			ref: String,
			downstream: Map<String, List<String>>,
			chain: MutableList<String>,
			settled: MutableSet<String>,
		): List<String>? {
			val closes = chain.indexOf(ref)
			if (closes >= 0) return chain.subList(closes, chain.size) + ref
			if (ref in settled) return null

			chain.add(ref)
			for (next in downstream[ref].orEmpty()) {
				loopFrom(next, downstream, chain, settled)?.let { return it }
			}
			chain.removeAt(chain.lastIndex)
			settled.add(ref)
			return null
		}
	}
}

/**
 * One ticket of a plan, after its arguments have been read and its assignees resolved.
 *
 * Nothing on it is derived: every field is something the caller said, in the type the
 * service takes. [parent] stays a `ref` rather than becoming a `UUID` here because the
 * row it points at does not exist yet — resolving it is the tool's second pass, and the
 * reason there is one.
 */
internal data class PlannedTicket(
	val ref: String,
	val title: String,
	val description: String?,
	val status: TicketStatus,
	val priority: TicketPriority,
	val estimate: Int?,
	val assigneeIds: List<UUID>,
	val parent: String?,
)

/** One edge of a plan, both ends still `ref`s, for [PlannedTicket]'s reason. */
internal data class PlannedLink(val from: String, val to: String, val type: TicketLinkType)

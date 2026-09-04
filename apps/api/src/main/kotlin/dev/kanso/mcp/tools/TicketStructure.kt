package dev.kanso.mcp.tools

import dev.kanso.domain.TicketLinkType
import dev.kanso.service.ConflictException
import dev.kanso.service.SubTicketProgress
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketLinkView
import java.util.UUID

/**
 * The two things a ticket is attached to, drawn once for every tool that prints them.
 *
 * `TicketLines` draws a ticket; this draws what a ticket is *part of* — the edges of the
 * graph and the children under it. Separate from that file because the planning tools ask
 * a different question of it, and together in this one because `kanso_get_ticket` prints
 * both sections and the two writing tools echo them back: three callers, one shape.
 *
 * Everything here is a **read of what already exists**. Nothing decides what the graph
 * should be — that is the whole of KAN-20's constraint, and it is why the planning surface
 * is two writers with no opinion and a reader that says what is already true.
 */
internal object TicketStructure {

	/**
	 * One edge, as a sentence with the ticket in front of it as the subject.
	 *
	 * A stored row is two sentences depending on which end is asking, which is what
	 * `outgoing` carries; `symmetric` is the domain's own answer to when the direction means
	 * nothing, asked rather than re-derived from `type == RELATES` here.
	 *
	 * The web says the same thing in `views/ticket-links.ts`, in Title Case, for its pills.
	 * Not shared with it and not a dialect of it: that function renders a wire field the
	 * client is trusted with, and this renders the server's own text for a reader that never
	 * sees the wire — the same two-renderings-of-one-set that `ApiExceptionHandler` and
	 * `McpErrors` already are. What must not fork is the *vocabulary*, and that lives in
	 * [TicketLinkType].
	 */
	fun phrase(type: TicketLinkType, outgoing: Boolean): String = when {
		type.symmetric -> "relates to"
		type == TicketLinkType.BLOCKS -> if (outgoing) "blocks" else "blocked by"
		else -> if (outgoing) "duplicates" else "duplicated by"
	}

	/**
	 * A ticket's edges, or null when it has none.
	 *
	 * Null rather than an empty heading, for `kanso_get_ticket`'s own reason about custom
	 * fields: a section that says nothing still costs an agent tokens to read and rule out.
	 *
	 * `blocks` first, then the rest. Not cosmetic — an agent asked to schedule reads the
	 * ordering edges and can stop, and `blocks` is the only type the scheduler has ever
	 * looked at (`DependencyRepository` filters on it in SQL). Within a type the far end's
	 * identifier orders them, so two reads of an unchanged ticket print the same block.
	 */
	fun links(views: List<TicketLinkView>): String? {
		if (views.isEmpty()) return null
		val ordered = views.sortedWith(
			compareBy({ it.type != TicketLinkType.BLOCKS }, { it.type.wire }, { address(it.other) }),
		)
		return buildString {
			appendLine("links:")
			for (edge in ordered) {
				appendLine("  ${phrase(edge.type, edge.outgoing).padEnd(14)}${address(edge.other)}  ${edge.other.ticket.title}")
			}
		}.trimEnd()
	}

	/**
	 * The children under a parent, headed by how much of it is finished.
	 *
	 * [progress] comes from `SubTicketService.progress` rather than being counted off
	 * [children] here, and that is the point of printing a number at all: it leaves
	 * cancelled children out of both halves, so "5 of 5" can mean finished. Counting the
	 * lines below it would be a second definition of done, free to disagree with the one the
	 * parent's own panel shows — and it would disagree exactly on the tickets somebody
	 * cancelled, which is where a reader is least likely to check.
	 */
	fun children(children: List<TicketDetail>, progress: SubTicketProgress?, emails: Map<UUID, String>): String? {
		if (children.isEmpty()) return null
		val counted = progress?.let { " — ${it.done} of ${it.total} done" }.orEmpty()
		return buildString {
			appendLine("sub-tickets$counted:")
			for (child in children) {
				appendLine("  " + TicketLines.line(child, child.assigneeIds.mapNotNull { emails[it] }))
			}
		}.trimEnd()
	}

	/**
	 * The one state a ticket cannot be made a parent in, refused in the address the caller
	 * typed and before anything is written.
	 *
	 * `SubTicketService.parentRefusal` is the authority on it and it runs on every child
	 * inside the caller's transaction — so this is not the guard, it is the **sentence**:
	 * that method answers with the UUID it was handed, and an agent that typed `KAN-142`
	 * cannot match `9f3c…` to anything it has seen. Read here off the field it reads, so the
	 * refusal names the identifier — the rewrite `TicketLines.byIdentifier` performs on the
	 * lookup's own message, and the one KAN-70 made `ScheduleService` perform on the cycle.
	 *
	 * [instead] is the way out, and it differs by caller: a ticket being split is told to
	 * split its parent, a ticket being filed under one is told where to file it. The rule is
	 * shared because it is one rule; the way out is not, because a refusal an agent cannot
	 * act on costs the same round trip as no answer at all.
	 *
	 * **A ticket that already has parts is deliberately not refused here.** One level deep is
	 * the rule; "split only once" is not, and inventing it would be `mcp/` holding an opinion
	 * Kanso does not — the exact thing KAN-20 says to expose a better tool instead of.
	 */
	fun refuseNesting(parent: TicketDetail, address: String, instead: String) {
		if (parent.ticket.parentId == null) return
		throw ConflictException("$address is itself a sub-ticket, and sub-tickets do not nest — $instead")
	}

	/**
	 * How the far end of an edge is named: its identifier, falling back to its id.
	 *
	 * The same fallback `TicketLines.line` makes and for the same reason — a ticket no team
	 * has claimed has no identifier to print, and the column has to stay copyable into
	 * `kanso_get_ticket`.
	 */
	private fun address(detail: TicketDetail): String = detail.identifier ?: detail.ticket.id.toString()
}

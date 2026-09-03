package dev.kanso.mcp.tools

import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.User
import dev.kanso.mcp.McpArguments
import dev.kanso.mcp.McpTool
import dev.kanso.mcp.booleanField
import dev.kanso.mcp.objectSchema
import dev.kanso.mcp.stringField
import dev.kanso.service.NotFoundException
import dev.kanso.service.TicketLinkService
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One edge, drawn or erased, through `TicketLinkService` and nothing else.
 *
 * **This is where KAN-20's constraint is easiest to break and most important to keep.**
 * "Infer the existing dependencies between tickets" reads like a job for something inside
 * Kanso that compares titles, notices `KAN-12` quoted in a description, and proposes an
 * arrow. That thing would be a heuristic pretending to be judgement: it would be wrong
 * often, it would be wrong differently on every instance, and it would have to be
 * maintained by one person forever. So there is no inference here at all. The model does
 * the inferring — it has already read the descriptions through `kanso_get_ticket`, which
 * now prints the edges that exist so it does not propose one twice — and this tool is the
 * hand that writes down what it concluded.
 *
 * Everything that makes an arrow legal stays in the services: `ScheduleService.linkRefusal`
 * refuses a loop and **names the chain that would close**, which is the one refusal an agent
 * can genuinely act on, and `ScheduleService.link` cascades the dates in the same
 * transaction. A tool that inserted the row itself would get the cycle check, the cascade
 * and the two mirror pushes wrong in three separate ways.
 *
 * `from` is the subject of the sentence the type spells: `from` *blocks* `to`, which is
 * the predecessor and the successor `ScheduleService` takes in that order. Said in the
 * description as a sentence rather than as "predecessor/successor", because an agent
 * reading `blocks` and being handed `predecessor` has to translate, and half the time it
 * will translate backwards.
 *
 * **Known wart, deliberately not patched here.** `ScheduleService.linkRefusal` builds its
 * chain out of `DependencyRepository.pathBetween`, which answers in ticket *ids*, so the
 * loop refusal reads `… : 509a2367-… -> bddbebdc-…` rather than `PLN-1 -> PLN-2`. That is
 * poor for an agent and it is poor for the person who gets the same sentence in a toast —
 * the web has always shown it. Rewriting the ids to identifiers *in this file* would mean
 * regex surgery on another service's message, which breaks the day that message is
 * reworded; the fix belongs where the chain is built, and it is a change to the
 * scheduler's own contract with a second caller. Left for KAN-11's territory rather than
 * smuggled in here.
 */
@Service
class LinkTicketsTool(
	private val tickets: TicketService,
	private val links: TicketLinkService,
) : McpTool {

	override val name = "kanso_link_tickets"
	override val title = "Link two tickets"
	override val writes = true

	override val description = """
		Record a relationship between two tickets that already exist, addressed by
		identifier. `from` and `to` read as a sentence: `from` blocks `to`, `from` duplicates
		`to`, `from` relates to `to`.

		`blocks` is the only type Kanso schedules on — drawing one can move the dates of
		everything downstream of it, and a `blocks` edge that would close a loop is refused
		with the chain it would close. `relates` and `duplicates` order nothing; they are
		notes on the graph.

		Set `remove` to true to erase an edge instead of drawing one. Drawing an edge that
		already exists is not an error and changes nothing.

		This tool does not work out what should be linked. Read the tickets with
		`kanso_get_ticket` — which prints the edges each one already has — decide, and then
		record the decision here, one edge per call.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"from" to stringField("The ticket the sentence is about, e.g. `KAN-12`."),
		"to" to stringField("The ticket at the other end, e.g. `KAN-20`."),
		"type" to stringField("Which relationship, read as `from <type> to`.", TYPES),
		// A flag rather than a second tool. An agent correcting a graph it just misread
		// needs the eraser in the tool it already found, and the safe value is the default:
		// a caller that omits it cannot delete anything.
		"remove" to booleanField("Erase this edge instead of drawing it. Default false."),
		required = listOf("from", "to", "type"),
	)

	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("from", "to", "type", "remove")

		val type = TicketLinkType.from(args.requiredString("type"))
		val from = TicketLines.byIdentifier(args.requiredString("from"), tickets::getByIdentifier)
		val to = TicketLines.byIdentifier(args.requiredString("to"), tickets::getByIdentifier)
		val sentence = "${from.identifier} ${TicketStructure.phrase(type, outgoing = true)} ${to.identifier}"

		if (args.boolean("remove", default = false)) {
			try {
				links.unlink(actor, from.ticket.id, to.ticket.id, type)
			} catch (missing: NotFoundException) {
				// Both service paths name the two ids, which an agent has never seen and
				// cannot match to the identifiers it just typed. Rewritten rather than
				// re-derived — the same thing `TicketLines.byIdentifier` does to the lookup's
				// own refusal, for the same reader.
				throw NotFoundException("There is no `${type.wire}` edge to remove: $sentence")
			}
			return "Removed — it is no longer true that $sentence."
		}

		val moved = links.link(actor, from.ticket.id, to.ticket.id, type)
		// The end state, not the action. `TicketLinkService.link` returns an empty list for
		// an edge it drew with nothing downstream, for an edge that was already there, and
		// for every `relates` — so this tool cannot tell "drawn" from "already true", and
		// asking first would mean reaching `TicketLinkRepository.exists` past the service
		// layer for an answer that can still be stale by the time the insert runs. Reporting
		// what is now true is correct in all three cases.
		val cascaded = moved.size.takeIf { it > 0 }?.let { " Rescheduled $it ticket(s) downstream of it." }
		return "$sentence.${cascaded.orEmpty()}"
	}

	private companion object {
		val TYPES = TicketLinkType.entries.map { it.wire }
	}
}

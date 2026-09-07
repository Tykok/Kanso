package dev.kanso.mcp.tools

import dev.kanso.domain.DefaultStatus
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
import dev.kanso.service.SubTicketService
import dev.kanso.service.TicketService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A large ticket becomes a parent and its parts become sub-tickets, in **one**
 * transaction.
 *
 * That is the whole reason this is a tool and not two calls to the two that already exist.
 * `kanso_create_ticket` files a ticket and `SubTicketService.setParent` hangs it under
 * another; an agent doing a five-way split through them makes ten calls, and the failure
 * that matters is not the tenth failing — it is the *ninth*. Four children parented, one
 * loose, and the agent told the split failed. Nobody can tell from the backlog which four.
 * Here the parts either all exist under the parent or none do, and the ticket that was
 * going to be split is untouched.
 *
 * **The split itself is not decided here.** What the parts are, how the work divides, what
 * each one is worth — that is judgement, it is the model's, and it arrives in `parts`
 * already made. This file knows two things the model does not: which fields a child
 * inherits, and that the whole thing is atomic.
 *
 * Inherited rather than asked for: the team, the project and the priority. A part of an
 * urgent ticket is urgent until somebody says otherwise, and a part of a ticket in `KAN`
 * is in `KAN` — an agent that had to restate them could restate them wrongly, and a child
 * in another team is a child `SubTicketService` would still accept. Dates are inherited by
 * nobody, for `CreateTicketTool`'s reason: `KansoInstant` carries a granularity that an
 * agent handing over a date has not answered.
 */
@Service
class SplitTicketTool(
	private val tickets: TicketService,
	private val subTickets: SubTicketService,
	private val people: McpPeople,
) : McpTool {

	override val name = "kanso_split_ticket"
	override val title = "Split a ticket into sub-tickets"
	override val writes = true

	override val description = """
		Turn one existing ticket into a parent and file the parts you name underneath it, all
		in one go. Address the ticket by identifier, like `KAN-142`. Returns the identifiers
		the parts were given.

		Every part is created in the parent's team and project and at the parent's priority,
		so you do not restate them. Give each part a title, and optionally a description, an
		estimate, a status and assignees.

		Sub-tickets are one level deep, so a ticket that is already a part of something cannot
		be split again — split its parent instead. Splitting a ticket that already has parts
		adds to them rather than replacing them, so read it first if you do not know what it
		already holds. Either every part is filed or none is; a refusal leaves the ticket
		exactly as it was.

		This tool does not decide how to split anything. Read the ticket with
		`kanso_get_ticket`, work out the parts, and hand them over here.
	""".trimIndent()

	override val inputSchema = objectSchema(
		"ticket" to stringField("The ticket to split, e.g. `KAN-142`."),
		"parts" to objectsField(
			"The parts, in the order they should be filed. One to $MAX_PARTS of them.",
			objectSchema(
				"title" to stringField("One line, what this part is."),
				"description" to stringField("The body. Markdown, optional."),
				"status" to stringField("Default `todo`.", STATUSES),
				"estimate" to integerField("Points. Omit if this part is not sized."),
				"assignees" to stringsField("Who is doing this part, by email or by user id."),
				required = listOf("title"),
			),
		),
		required = listOf("ticket", "parts"),
	)

	@Transactional
	override fun call(actor: User, arguments: Map<String, Any?>): String {
		val args = McpArguments(name, arguments)
		args.refuseUnknown("ticket", "parts")

		val parts = args.objects("parts")
		// Before the read, so a call that would file nothing costs nothing and says why —
		// `kanso_update_ticket`'s rule about reporting success for a no-op, which here would
		// leave an agent believing a ticket had been broken up.
		if (parts.isEmpty()) throw BadRequestException("`$name` needs at least one part in `parts`")
		// A bound rather than a truncation, the spec's argument about an agent in a loop: a
		// silently shortened split is a parent whose parts do not add up to it, and nobody
		// reading the backlog afterwards can see that six of the nine went missing. Twenty is
		// well past any real split and still small enough to read in one screen.
		if (parts.size > MAX_PARTS) {
			throw BadRequestException("`$name` takes at most $MAX_PARTS parts, and this call has ${parts.size}")
		}

		val parent = TicketLines.byIdentifier(args.requiredString("ticket"), tickets::getByIdentifier)
		val address = parent.identifier ?: parent.ticket.id.toString()
		// The rule and the sentence both live in `TicketStructure` now that three tools ask it:
		// this one, `kanso_create_ticket`'s `parent`, and `kanso_plan`'s `ref` version over
		// tickets that do not exist yet. The way out is the half that differs.
		TicketStructure.refuseNesting(parent, address, instead = "split its parent instead")
		val teamId = parent.ticket.teamId ?: throw BadRequestException(
			"$address belongs to no team, and a part has to be filed in one",
		)

		// **Every part read and every name resolved before the first insert.** The transaction
		// below would undo a half-done split anyway, but a refusal that never wrote is a
		// better refusal than one that has to be rolled back: it is provable from outside,
		// and an agent that misspelled `titel` in the ninth part is told so without nine
		// tickets having existed for a moment in somebody's realtime feed. It is
		// `kanso_update_ticket`'s rule about refusing before reading, applied to a list.
		val described = parts.map { part ->
			part.refuseUnknown("title", "description", "status", "estimate", "assignees")
			Part(
				title = part.requiredString("title"),
				description = part.string("description"),
				status = DefaultStatus.from(part.string("status") ?: DefaultStatus.TODO.wire),
				estimate = part.integer("estimate"),
				assigneeIds = people.resolve(part.strings("assignees").orEmpty()),
			)
		}

		val filed = described.map { part ->
			val child = tickets.create(
				actor = actor,
				teamId = teamId,
				title = part.title,
				description = part.description,
				status = part.status,
				// Inherited, not asked for — see this file's header. A part of an urgent ticket
				// is urgent until somebody says otherwise.
				priority = parent.ticket.priority,
				start = null,
				due = null,
				projectId = parent.ticket.projectId,
				assigneeIds = part.assigneeIds,
				docIds = emptyList(),
				estimate = part.estimate,
			)
			// Inside the same transaction as the insert that made it, which is what is left for
			// "all or none" to cover: a service refusal the pre-pass above could not have seen —
			// an access rule, a project rule, `SubTicketService`'s own verdict on the
			// parenthood — arriving on the fifth part rolls back the four before it.
			subTickets.setParent(actor, child.ticket.id, parent.ticket.id)
			child
		}

		return "Split $address into ${filed.size} sub-ticket(s):\n" +
			filed.joinToString("\n") { "  ${it.identifier}  ${it.ticket.title}" }
	}

	/**
	 * One part, after the arguments have been read and the names resolved.
	 *
	 * A type rather than nine locals carried down the loop, and it exists so that reading
	 * the arguments and writing the rows are two passes with a value between them. There is
	 * nothing derived on it: every field is something the caller said, in the type the
	 * service takes.
	 */
	private data class Part(
		val title: String,
		val description: String?,
		val status: DefaultStatus,
		val estimate: Int?,
		val assigneeIds: List<UUID>,
	)

	private companion object {
		val STATUSES = DefaultStatus.entries.map { it.wire }

		const val MAX_PARTS = 20
	}
}

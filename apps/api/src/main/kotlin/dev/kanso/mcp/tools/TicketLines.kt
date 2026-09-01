package dev.kanso.mcp.tools

import dev.kanso.domain.Team
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import java.util.UUID

/**
 * The shapes the four tools share: how a team is named, how a ticket is addressed, and
 * what one line of a backlog looks like.
 *
 * Here rather than in each tool because two tools drawing a ticket two ways is how an
 * agent learns to parse one of them — and then breaks when it is handed the other.
 */
internal object TicketLines {

	/**
	 * A team is addressed by its key, never by its id.
	 *
	 * `KAN` is what a person says and what every ticket identifier already carries, so it
	 * is the only handle an agent reliably has. The refusal names the keys that do exist,
	 * because "no team DES" sends the agent guessing and "existing teams: KAN, OPS" ends
	 * the exchange in one more call — the discipline `McpErrors` describes.
	 */
	fun teamByKey(teams: TeamService, key: String): Team {
		val live = teams.list(includeArchived = false)
		return live.firstOrNull { it.key.equals(key, ignoreCase = true) }
			?: throw BadRequestException(
				"No team with key `$key`." +
					" Existing teams: ${live.joinToString { it.key }.ifEmpty { "none yet" }}",
			)
	}

	/**
	 * `KAN-142`, split back into the two halves `TicketService.getByIdentifier` takes.
	 *
	 * A UUID is deliberately not accepted here. It would be a second way to address one
	 * ticket, and the identifier is the one that appears in the tools' own output, in the
	 * UI, in Notion and in what a person says out loud.
	 */
	fun identifier(raw: String): Pair<String, Int> {
		val dash = raw.lastIndexOf('-')
		val number = if (dash > 0) raw.substring(dash + 1).toIntOrNull() else null
		if (number == null) {
			throw BadRequestException("`$raw` is not a ticket identifier — they look like `KAN-142`")
		}
		return raw.substring(0, dash).uppercase() to number
	}

	/** The same lookup, with the sentence a reader can act on when there is no such row. */
	fun byIdentifier(raw: String, lookup: (String, Int) -> TicketDetail): TicketDetail {
		val (key, number) = identifier(raw)
		return try {
			lookup(key, number)
		} catch (missing: NotFoundException) {
			throw NotFoundException(missing.message?.replace("$key-$number", raw) ?: "No ticket $raw")
		}
	}

	/**
	 * An id an agent copied out of a previous answer, refused rather than swallowed.
	 *
	 * `UUID.fromString` raises `IllegalArgumentException` with a message about a string
	 * length, which names neither the argument nor what a good one looks like — the same
	 * reason `TicketFilterVocabulary.uuids` rewrites it.
	 */
	fun uuid(raw: String): UUID = try {
		UUID.fromString(raw)
	} catch (notAnId: IllegalArgumentException) {
		throw BadRequestException("`$raw` is not an id")
	}

	/**
	 * One ticket, one line, about twenty tokens.
	 *
	 * Compact on purpose: a hundred results is a cheap read only if a result is cheap, and
	 * a JSON object per ticket costs five times this for facts the next call can ask for.
	 * The columns are the ones a person scans a backlog by.
	 */
	fun line(detail: TicketDetail, assignees: List<String>): String = buildString {
		append(detail.identifier.padEnd(12))
		append("  ")
		append(detail.ticket.status.wire.padEnd(12))
		append(detail.ticket.priority.wire.padEnd(7))
		append(detail.ticket.title)
		// The emails bare, with no `@` sigil in front of them. The spec's sketch of this line
		// wrote `@elie`, which reads well for a handle and badly for an address — and an
		// address is what the writing tools take back, so this column has to be copyable.
		if (assignees.isNotEmpty()) append("  ${assignees.joinToString(" ")}")
		detail.ticket.due?.let { append("  due ${it.at.toLocalDate()}") }
	}
}

package dev.kanso.mcp

import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A person, by the name an agent actually holds.
 *
 * `TicketService` takes assignees as `UUID`s, and an agent has never seen one: what it
 * has is what somebody said out loud, which is an email. Without this, the two writing
 * tools would take an identifier no caller can produce, and "change a ticket's assignee"
 * would be a tool that cannot be used.
 *
 * **This resolves a name and decides nothing.** It is a lookup, not a rule — every id it
 * hands back still goes through `TicketService.requireUsers` and every write still
 * through `TicketAccess`, so an agent can no more assign work in a team it may not touch
 * than its owner can. That is why reaching `UserRepository` here is not the repository
 * bypass the ticket forbids: nothing is written through it, and nothing is permitted by
 * it. A directory read is open in Kanso anyway — `architecture.md` says reads are — so
 * turning an email into an id reveals nothing an agent could not already ask the REST API
 * for.
 *
 * Its own service rather than a method on each tool, for the reason `TicketAccess` is its
 * own service: three callers, one answer, and the transaction has to come from somewhere
 * a proxy can put it.
 */
@Service
class McpPeople(private val users: UserRepository) {

	/**
	 * An email or an id, and nothing else.
	 *
	 * Both are accepted because both are things an agent legitimately holds — the email a
	 * person typed, and the id [emailsOf] handed back on the previous read — and refusing
	 * one of them would make a two-call loop fail on its second call. A display name is
	 * deliberately not accepted: two people can share one, and quietly picking the first is
	 * how work gets assigned to the wrong person.
	 */
	@Transactional(readOnly = true)
	fun resolve(named: List<String>): List<UUID> = named.map { one ->
		runCatching { UUID.fromString(one) }.getOrNull()?.let { return@map it }
		users.findByEmail(one)?.id
			?: throw BadRequestException("Nobody on this instance is `$one` — assignees are named by email or by id")
	}

	/** The other direction, for the text a tool prints. One query for the whole page. */
	@Transactional(readOnly = true)
	fun emailsOf(ids: Collection<UUID>): Map<UUID, String> {
		if (ids.isEmpty()) return emptyMap()
		return users.findAllById(ids.toSet()).associate { it.id to it.email }
	}
}

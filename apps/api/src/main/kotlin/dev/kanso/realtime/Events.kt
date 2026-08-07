package dev.kanso.realtime

import java.time.OffsetDateTime
import java.util.UUID

enum class ChangeKind { CREATED, UPDATED, DELETED }

/**
 * What one client tells the others. Deliberately thin: the id and enough scope to
 * decide whether a given view cares. Receivers refetch or patch their own cache —
 * shipping whole entities through the bus would mean two sources of truth for
 * shape and a payload that outgrows `pg_notify`'s 8000-byte limit.
 */
data class KansoEvent(
	val entity: String,
	val kind: ChangeKind,
	val id: UUID,
	val teamId: UUID? = null,
	val projectId: UUID? = null,
	/** "kanso" for a user action, "notion" when the inbound poller applied it. */
	val origin: String = "kanso",
	val at: OffsetDateTime = OffsetDateTime.now(),
) {
	/** Broadcast destinations. Everything lands on the entity topic; team-scoped views get a narrower one. */
	fun destinations(): List<String> = buildList {
		add("/topic/$entity")
		teamId?.let { add("/topic/teams/$it/$entity") }
	}

	companion object {
		fun ticket(kind: ChangeKind, id: UUID, teamId: UUID?, projectId: UUID?, origin: String = "kanso") =
			KansoEvent("tickets", kind, id, teamId, projectId, origin)

		fun project(kind: ChangeKind, id: UUID, teamId: UUID?, origin: String = "kanso") =
			KansoEvent("projects", kind, id, teamId, null, origin)

		fun team(kind: ChangeKind, id: UUID, origin: String = "kanso") =
			KansoEvent("teams", kind, id, id, null, origin)
	}
}

package dev.kanso.sync.importer

import dev.kanso.domain.Wire
import dev.kanso.domain.parse
import dev.kanso.repo.UserRepository
import java.util.UUID

/** A field of a Kanso row that a Notion column can fill. */
enum class ImportField(override val wire: String, val types: Set<String>) : Wire {
	STATUS("status", setOf("select", "status")),
	PRIORITY("priority", setOf("select", "status")),
	DESCRIPTION("description", setOf("rich_text")),
	START("start", setOf("date")),
	DUE("due", setOf("date")),
	END("end", setOf("date")),
	ASSIGNEES("assignees", setOf("people")),
	LEAD("lead", setOf("people")),
	PROJECT("project", setOf("relation")),
	TEAM("team", setOf("relation")),
	PARENT_TEAM("parentTeam", setOf("relation")),
	BLOCKED_BY("blockedBy", setOf("relation")),

	/**
	 * A `single_property` relation exists on one side only — `NotionSchema` relies on that
	 * for `Blocked by` — so a workspace can carry the project link on the tasks base or on
	 * the projects base, and neither is more correct. Reading only the child's side would
	 * leave half of them unlinked, so these three read the same three links again, from
	 * the parent's own column naming its children.
	 */
	TICKETS("tickets", setOf("relation")),
	PROJECTS("projects", setOf("relation")),
	SUB_TEAMS("subTeams", setOf("relation"));

	companion object {
		fun from(raw: String): ImportField = parse(entries.toTypedArray(), raw)
	}
}

/**
 * What each field of a target reads from, and what each mapped option means.
 *
 * `values` is keyed by field rather than by property name: the property is already in
 * `columns`, and two fields reading the same column would otherwise have to share one
 * option table.
 */
data class ColumnMapping(
	val columns: Map<ImportField, String> = emptyMap(),
	val values: Map<ImportField, Map<String, String>> = emptyMap(),
) {
	fun property(field: ImportField): String? = columns[field]
}

/** Where a row lands when no relation answers. All three optional; all three per base. */
data class Fallback(
	val teamId: UUID? = null,
	val parentTeamId: UUID? = null,
	val projectId: UUID? = null,
)

/**
 * Every id `people` could name across one base's column for [field], checked against
 * `users` once for the whole base rather than once per page — an account either exists or
 * it doesn't, independent of which page or team the row asking about it belongs to, so
 * there is nothing finer to key a cache by.
 *
 * Shared by `TicketImport` (`ASSIGNEES`) and `ProjectImport` (`LEAD`): same shape, same
 * field-shaped difference, so it lives with the field rather than being written twice.
 */
internal fun existingAccounts(
	users: UserRepository,
	base: PlannedBase,
	field: ImportField,
	people: Map<String, UUID?>,
): Set<UUID> {
	val candidates = base.adoptable.flatMap { page ->
		base.reader.people(page, field).mapNotNull { people[it.id] }
	}.distinct()
	return users.findAllById(candidates).mapTo(mutableSetOf()) { it.id }
}

package dev.kanso.sync.importer

import dev.kanso.domain.Wire
import dev.kanso.domain.parse
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

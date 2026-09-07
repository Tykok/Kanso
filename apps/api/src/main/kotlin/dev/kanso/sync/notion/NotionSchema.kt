package dev.kanso.sync.notion

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus

/**
 * Property names and value builders, in one place.
 *
 * The bootstrap that creates the Notion databases and the mapper that writes pages
 * into them must agree on every property name exactly — a typo shows up as a
 * silently ignored field, not an error. Sharing these constants makes that
 * impossible.
 */
object NotionProps {

	const val NAME = "Name"
	const val KANSO_ID = "Kanso ID"
	const val IDENTIFIER = "Identifier"
	const val STATUS = "Status"
	const val PRIORITY = "Priority"

	/**
	 * Effort in points, mirrored as a `number` rather than a `select` of the six values.
	 *
	 * A select would round-trip the vocabulary the way the statuses do, and would also make
	 * the column unsummable in Notion — the one thing a reader over there would want to do
	 * with it. The scale is guarded on the way back in instead, where an off-scale number
	 * is dropped exactly like an unknown status label is.
	 */
	const val ESTIMATE = "Estimate"
	const val DESCRIPTION = "Description"
	const val START = "Start"
	const val DUE = "Due"
	const val END = "End"
	const val PARENT_TEAM = "Parent team"
	const val TEAM = "Team"
	const val PROJECT = "Project"
	const val DOCS = "Docs"

	/**
	 * The dependency arrows, mirrored as a self-referencing relation on Tickets.
	 *
	 * Only the one direction exists as a property. The relation is created
	 * `single_property`, like the teams' parent relation, so Notion writes no synced
	 * reverse — a `Blocks` constant would name a property that is not there.
	 */
	const val BLOCKED_BY = "Blocked by"
	const val MEMBERS = "Members"
	const val LEAD = "Lead"
	const val ASSIGNEES = "Assignees"
	const val URL = "URL"

	/**
	 * Text mirrors of `people` properties.
	 *
	 * A Notion `people` value only accepts workspace members, so anyone without a
	 * Notion account cannot appear there. Rather than drop that information from
	 * the mirror — which is exactly what the people reading Notion need — we also
	 * write the names as plain text.
	 */
	const val MEMBERS_TEXT = "Members (Kanso)"
	const val LEAD_TEXT = "Lead (Kanso)"
	const val ASSIGNEES_TEXT = "Assignees (Kanso)"

	/** Notion rejects a rich-text fragment longer than this. */
	private const val MAX_FRAGMENT = 2000

	fun textFragment(text: String): Map<String, Any> =
		mapOf("type" to "text", "text" to mapOf("content" to text))

	fun title(text: String): Map<String, Any?> =
		mapOf("title" to chunks(text.ifBlank { "Untitled" }))

	fun richText(text: String?): Map<String, Any?> =
		mapOf("rich_text" to (text?.takeIf { it.isNotBlank() }?.let(::chunks) ?: emptyList<Any>()))

	/** Null clears the property, which is what an unestimated ticket has to write. */
	fun number(value: Int?): Map<String, Any?> = mapOf("number" to value)

	fun select(label: String?): Map<String, Any?> =
		mapOf("select" to label?.let { mapOf("name" to it) })

	/**
	 * Notion's date value. A day-granularity instant is written as a bare date so the
	 * mirror shows a day rather than a midnight, matching how Kanso renders it.
	 */
	fun date(instant: KansoInstant?): Map<String, Any?> = mapOf(
		"date" to instant?.let {
			mapOf("start" to if (it.hasTime) it.at.toString() else it.at.toLocalDate().toString())
		}
	)

	fun people(notionPersonIds: Collection<String>): Map<String, Any?> =
		mapOf("people" to notionPersonIds.distinct().map { mapOf("object" to "user", "id" to it) })

	fun relation(pageIds: Collection<String>): Map<String, Any?> =
		mapOf("relation" to pageIds.distinct().map { mapOf("id" to it) })

	fun url(value: String?): Map<String, Any?> = mapOf("url" to value)

	/** Long text has to be split; Notion concatenates the fragments on display. */
	private fun chunks(text: String): List<Map<String, Any>> =
		text.chunked(MAX_FRAGMENT).map(::textFragment)
}

/**
 * The database schemas Kanso creates at bootstrap.
 *
 * Relations are deliberately absent here: a relation needs its target to already
 * exist, so they are added in a second pass once all four databases are created.
 */
object NotionSchema {

	fun teams(): Map<String, Any?> = mapOf(
		NotionProps.NAME to mapOf("title" to emptyMap<String, Any>()),
		NotionProps.KANSO_ID to mapOf("rich_text" to emptyMap<String, Any>()),
		NotionProps.MEMBERS to mapOf("people" to emptyMap<String, Any>()),
		NotionProps.MEMBERS_TEXT to mapOf("rich_text" to emptyMap<String, Any>()),
	)

	fun projects(): Map<String, Any?> = mapOf(
		NotionProps.NAME to mapOf("title" to emptyMap<String, Any>()),
		NotionProps.KANSO_ID to mapOf("rich_text" to emptyMap<String, Any>()),
		NotionProps.STATUS to selectOf(ProjectStatus.entries.map { it.label }),
		// Two separate date properties rather than one range: a range read back
		// with only a start is ambiguous — start date or deadline? Two properties
		// round-trip without guessing, at the cost of prettier Notion timelines.
		NotionProps.START to mapOf("date" to emptyMap<String, Any>()),
		NotionProps.END to mapOf("date" to emptyMap<String, Any>()),
		NotionProps.LEAD to mapOf("people" to emptyMap<String, Any>()),
		NotionProps.LEAD_TEXT to mapOf("rich_text" to emptyMap<String, Any>()),
	)

	fun tickets(): Map<String, Any?> = mapOf(
		NotionProps.NAME to mapOf("title" to emptyMap<String, Any>()),
		NotionProps.KANSO_ID to mapOf("rich_text" to emptyMap<String, Any>()),
		NotionProps.IDENTIFIER to mapOf("rich_text" to emptyMap<String, Any>()),
		NotionProps.STATUS to selectOf(DefaultStatus.entries.map { it.label }),
		NotionProps.PRIORITY to selectOf(TicketPriority.entries.map { it.label }),
		NotionProps.ESTIMATE to mapOf("number" to emptyMap<String, Any>()),
		NotionProps.DESCRIPTION to mapOf("rich_text" to emptyMap<String, Any>()),
		NotionProps.START to mapOf("date" to emptyMap<String, Any>()),
		NotionProps.DUE to mapOf("date" to emptyMap<String, Any>()),
		NotionProps.ASSIGNEES to mapOf("people" to emptyMap<String, Any>()),
		NotionProps.ASSIGNEES_TEXT to mapOf("rich_text" to emptyMap<String, Any>()),
	)

	fun docs(): Map<String, Any?> = mapOf(
		NotionProps.NAME to mapOf("title" to emptyMap<String, Any>()),
		NotionProps.URL to mapOf("url" to emptyMap<String, Any>()),
	)

	/**
	 * Relation configs, applied after every database exists.
	 *
	 * The 2025-09-03 API moved relation targets from databases to data sources, but
	 * the published schema reference still documents `database_id`. Rather than bet
	 * on one, [dialect] produces both shapes and the bootstrap tries the newer one
	 * first, falling back on a validation error and logging which was accepted.
	 */
	fun relationTo(targetDatabaseId: String, targetDataSourceId: String, dialect: RelationDialect): Map<String, Any?> =
		when (dialect) {
			RelationDialect.DATA_SOURCE -> mapOf(
				"relation" to mapOf("data_source_id" to targetDataSourceId, "type" to "single_property", "single_property" to emptyMap<String, Any>())
			)

			RelationDialect.DATABASE -> mapOf(
				"relation" to mapOf("database_id" to targetDatabaseId, "type" to "single_property", "single_property" to emptyMap<String, Any>())
			)
		}

	private fun selectOf(labels: List<String>): Map<String, Any?> =
		mapOf("select" to mapOf("options" to labels.map { mapOf("name" to it) }))
}

enum class RelationDialect { DATA_SOURCE, DATABASE }

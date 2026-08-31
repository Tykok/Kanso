package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.Wire
import dev.kanso.sync.notion.NotionDataSource
import dev.kanso.sync.notion.NotionProps
import tools.jackson.databind.JsonNode

/**
 * One column of a base, as the schema screen needs to draw it.
 *
 * [options] is populated for a `select` or `status` column and empty for every other
 * type — nothing else here has options worth listing. [relationTo] is the data source a
 * `relation` column points at, and null for everything else; it is the whole basis of a
 * later "import the base this points at" suggestion, which is why it travels with the
 * column rather than being looked up again from the page.
 */
data class SchemaColumn(val name: String, val type: String, val options: List<String>, val relationTo: String?)

/** The columns whose type could fill one field, in the order the base declares them. */
data class FieldCandidates(val field: ImportField, val candidates: List<String>)

/**
 * What a base's schema offers, and what Kanso would map before anyone touches it.
 *
 * [suggestion] and [defaults] both answer "what would happen if the request said
 * nothing" — the first for a field the request could still fill by naming a column, the
 * second for what a filled field falls back to when its own value does not land in
 * Kanso's vocabulary, or is absent from the page. Neither is a rule: a mapping the
 * request sends replaces [suggestion] entirely, column by column.
 */
data class ImportSchemaView(
	val sourceId: String,
	val target: ImportTarget,
	val columns: List<SchemaColumn>,
	val fields: List<FieldCandidates>,
	val suggestion: ColumnMapping,
	val defaults: Map<ImportField, String?>,
)

/**
 * What a base looks like before anybody has mapped it, and Kanso's first guess at how to.
 *
 * The pages a discovery walk already read are not enough to draw this: a column left
 * empty on every page is invisible in them and present in the schema; a select's options
 * must be listed even when no page happens to use one; and a relation's target data
 * source exists nowhere else at all. So this reads [NotionDataSource.properties] — the
 * schema itself — rather than anything a page ever carried.
 *
 * Pure, and deliberately so: the plan says the suggestion is computed here rather than a
 * second time in the browser from the same rules. Two implementations of "what looks like
 * the status column" are two chances for the pre-fill to drift, and the browser needs
 * [ImportSchemaView.fields] anyway to offer the choice when the guess is wrong.
 */
object ImportSchema {

	fun of(source: NotionDataSource, target: ImportTarget): ImportSchemaView {
		val columns = columns(source.properties)
		val byName = columns.associateBy { it.name }

		val fields = target.fields.map { field ->
			FieldCandidates(field, columns.filter { it.type in field.types }.map { it.name })
		}

		val suggestedColumns = mutableMapOf<ImportField, String>()
		val suggestedValues = mutableMapOf<ImportField, Map<String, String>>()
		for (candidates in fields) {
			// A field with no entry in [NOTION_NAME] — the three that read a parent's own
			// column naming its children — has nothing here to compare a name to, so it is
			// never suggested; the request has to name it.
			val expectedName = NOTION_NAME[candidates.field] ?: continue
			val matched = candidates.candidates.firstOrNull { it.equals(expectedName, ignoreCase = true) } ?: continue
			suggestedColumns[candidates.field] = matched

			// Only a field with a closed vocabulary has option values to pre-fill at all —
			// a mapped date or relation column has nothing here for [ColumnMapping.values]
			// to say. An option outside the vocabulary is left out of the map rather than
			// guessed at: the writer's own default is what a reader sees for it instead.
			vocabulary(candidates.field, target)?.let { fromLabel ->
				suggestedValues[candidates.field] = byName.getValue(matched).options
					.mapNotNull { label -> fromLabel(label)?.let { label to it.wire } }
					.toMap()
			}
		}

		return ImportSchemaView(
			sourceId = source.id,
			target = target,
			columns = columns,
			fields = fields,
			suggestion = ColumnMapping(suggestedColumns, suggestedValues),
			defaults = defaults(target),
		)
	}

	/**
	 * Today's English name for each field the mirror itself writes — what the suggestion
	 * matches a column's name against, case-insensitively, because a workspace built by
	 * someone else is free to have typed `status` or `STATUS`.
	 *
	 * [ImportField.TICKETS], [ImportField.PROJECTS] and [ImportField.SUB_TEAMS] have no
	 * entry: they read the *parent's* own column naming its children, which is whatever a
	 * workspace built by someone who never heard of Kanso happened to call it, and there is
	 * nothing here to compare that name to.
	 */
	private val NOTION_NAME: Map<ImportField, String> = mapOf(
		ImportField.STATUS to NotionProps.STATUS,
		ImportField.PRIORITY to NotionProps.PRIORITY,
		ImportField.DESCRIPTION to NotionProps.DESCRIPTION,
		ImportField.START to NotionProps.START,
		ImportField.DUE to NotionProps.DUE,
		ImportField.END to NotionProps.END,
		ImportField.ASSIGNEES to NotionProps.ASSIGNEES,
		ImportField.LEAD to NotionProps.LEAD,
		ImportField.PROJECT to NotionProps.PROJECT,
		ImportField.TEAM to NotionProps.TEAM,
		ImportField.PARENT_TEAM to NotionProps.PARENT_TEAM,
		ImportField.BLOCKED_BY to NotionProps.BLOCKED_BY,
	)

	/**
	 * The closed vocabulary a select-backed field's options are checked against, or null
	 * for a field with none. [ImportField.STATUS] reads a different vocabulary per target:
	 * a base of tickets and a base of projects share one column name but not one set of
	 * words, so the target has to be part of the answer.
	 */
	private fun vocabulary(field: ImportField, target: ImportTarget): ((String) -> Wire?)? = when (field) {
		ImportField.STATUS -> when (target) {
			ImportTarget.TICKETS -> { label: String -> TicketStatus.fromLabel(label) }
			ImportTarget.PROJECTS -> { label: String -> ProjectStatus.fromLabel(label) }
			else -> null
		}
		ImportField.PRIORITY -> { label: String -> TicketPriority.fromLabel(label) }
		else -> null
	}

	/**
	 * What each field of [target] becomes when nothing fills it — read from the writers
	 * rather than restated from a spec, so this cannot say something the writer does not
	 * do. [TicketImport] applies [TicketStatus.TODO] and [TicketPriority.NONE];
	 * [ProjectImport] applies [ProjectStatus.IN_PROGRESS] for the same field's other
	 * vocabulary. Every other field is passed through as null, or resolved by a relation
	 * rather than defaulted to a wire value, so it names nothing here either.
	 */
	private fun defaults(target: ImportTarget): Map<ImportField, String?> = target.fields.associateWith { field ->
		when {
			field == ImportField.STATUS && target == ImportTarget.TICKETS -> TicketStatus.TODO.wire
			field == ImportField.STATUS && target == ImportTarget.PROJECTS -> ProjectStatus.IN_PROGRESS.wire
			field == ImportField.PRIORITY -> TicketPriority.NONE.wire
			else -> null
		}
	}

	/**
	 * A base's columns, in the order Notion declares them, with the `title` property left
	 * out — it is found by type when a page is read, never chosen from a list, so offering
	 * it here would be a choice with no effect.
	 */
	private fun columns(properties: JsonNode?): List<SchemaColumn> = properties?.properties()
		?.map { it.key to it.value }.orEmpty()
		.mapNotNull { (name, value) -> column(name, value) }

	private fun column(name: String, value: JsonNode): SchemaColumn? {
		val type = value.path("type").asText(null) ?: return null
		if (type == "title") return null
		val options = value.path(type).path("options")
			.mapNotNull { it.path("name").asText(null)?.takeIf(String::isNotBlank) }
		val relationTo = value.path("relation").path("data_source_id").asText(null)
		return SchemaColumn(name, type, options, relationTo)
	}
}

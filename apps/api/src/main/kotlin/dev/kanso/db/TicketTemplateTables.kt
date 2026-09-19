package dev.kanso.db

import dev.kanso.docs.JsonbColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed's view of `V43`. Its own file for the reason `CustomFieldTables.kt` is one:
 * `Tables.kt` is past the length at which the maintainer stops re-reading a file.
 *
 * [TicketTemplates.body] goes through [JsonbColumnType] rather than `text()` because it is
 * written by Exposed's own `insert` and `update`, and the driver refuses a varchar parameter
 * for a jsonb column. Same conclusion as `custom_fields.options` and `doc_blocks.content`.
 */
private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

object TicketTemplates : Table("ticket_templates") {
	val id = javaUUID("id")

	/** Null is the instance level. `V43`'s header argues why that is allowed here. */
	val teamId = javaUUID("team_id").nullable()
	val name = text("name")
	val summary = text("summary").nullable()

	/** A `TemplateBody`, encoded by `TemplateBodyCodec` and by nothing else. */
	val body = jsonb("body")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object TicketTemplateCategories : Table("ticket_template_categories") {
	val templateId = javaUUID("template_id")
	val name = text("name")
	override val primaryKey = PrimaryKey(templateId, name)
}

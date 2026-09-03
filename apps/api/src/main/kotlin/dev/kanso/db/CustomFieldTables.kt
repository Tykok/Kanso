package dev.kanso.db

import dev.kanso.docs.JsonbColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed's view of `V32`. Its own file rather than two more objects in `Tables.kt`, which
 * is 567 lines and is the length past which the maintainer stops re-reading a file.
 *
 * Both jsonb columns here are declared through [JsonbColumnType] rather than as `text()`,
 * which is the *other* of the two treatments in this codebase and the choice is forced:
 * `Tables.kt` can declare `activity.payload` as text because it is only ever written by raw
 * SQL that casts explicitly, and these are written through Exposed's own `insert` and
 * `upsert` alongside their neighbours. The driver refuses a varchar parameter for a jsonb
 * column, so the cast has to live in the column type — the same conclusion
 * `user_preferences.shortcuts` and `doc_blocks.content` reached.
 */
private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

object CustomFields : Table("custom_fields") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val name = text("name")

	/** `CustomFieldType`, closed here by `custom_fields_type_chk`. */
	val type = text("type")
	val required = bool("required")

	/**
	 * A jsonb array of strings — non-empty exactly when [type] is `select`.
	 *
	 * Named `choices` on this side and `options` in the column, which is the one place in
	 * this file the two names differ: Exposed's own `Table` already has an `options` member,
	 * so the obvious name would have had to be an `override` of something unrelated.
	 */
	val choices = jsonb("options").default("[]")
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

object TicketFieldValues : Table("ticket_field_values") {
	val ticketId = javaUUID("ticket_id")
	val fieldId = javaUUID("field_id")

	/**
	 * One jsonb scalar — a string, a number or a boolean, and never a JSON null: no value is
	 * the absence of a row, which is what `V32` argues for at length. `FieldValueCodec` is
	 * the only thing that writes this column and the only thing that reads it back.
	 */
	val value = jsonb("value")
	override val primaryKey = PrimaryKey(ticketId, fieldId)
}

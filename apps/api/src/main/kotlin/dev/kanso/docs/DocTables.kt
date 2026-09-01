package dev.kanso.docs

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

/**
 * Exposed's view of what `V9__documents.sql` created. Like `db/Tables.kt`, nothing
 * here generates DDL — the migration is the single definition of the database.
 *
 * These objects sit in the slice's own package rather than at the end of
 * `db/Tables.kt` on purpose: five branches were cut from the same commit and four of
 * them add tables, so appending to that file is four conflicts at one line for a
 * layout choice worth nothing to either reader. Move them if the tree ever wants
 * every table in one file again.
 */

/**
 * A `jsonb` column, carried as its text.
 *
 * Written through [PGobject] rather than as a plain `text()` column: pgjdbc sends a
 * Kotlin String as `varchar`, and Postgres refuses `varchar → jsonb` without an
 * explicit cast, so `text("content")` here would compile and then fail on the first
 * insert. `OutboundJobs.payload` sidesteps that by only ever being written through raw
 * SQL that casts; blocks are written through Exposed, so the cast has to live in the
 * column type.
 *
 * Text and not a parsed tree because the shape is per-block-kind: the service
 * validates it, the controller hands Jackson the map, and one more object model in
 * between would be a third place for the same seven shapes to disagree.
 */
class JsonbColumnType : ColumnType<String>() {
	override fun sqlType(): String = "jsonb"

	override fun valueFromDB(value: Any): String = when (value) {
		is PGobject -> value.value ?: "{}"
		is String -> value
		else -> value.toString()
	}

	override fun notNullValueToDB(value: String): Any = PGobject().apply {
		type = "jsonb"
		this.value = value
	}

	override fun nonNullValueToString(value: String): String = "'$value'"
}

private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

object DocFolders : Table("doc_folders") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val parentId = javaUUID("parent_id").nullable()
	val name = text("name")
	val position = integer("position")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object DocPages : Table("doc_pages") {
	val id = javaUUID("id")
	val teamId = javaUUID("team_id")
	val folderId = javaUUID("folder_id").nullable()
	val title = text("title")
	val authorId = javaUUID("author_id").nullable()

	/** Who touched it last, which the footer prints and `author_id` cannot answer. */
	val editedBy = javaUUID("edited_by").nullable()

	/** Null for a page written here. Set when the page mirrors one written in Notion. */
	val notionPageId = text("notion_page_id").nullable()
	val notionUrl = text("notion_url").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

object DocBlocks : Table("doc_blocks") {
	val id = javaUUID("id")
	val pageId = javaUUID("page_id")
	val position = integer("position")
	val kind = text("kind")
	val content = jsonb("content")
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

/** `ticket_docs` at block granularity — see the migration for why that matters. */
object DocBlockTickets : Table("doc_block_tickets") {
	val blockId = javaUUID("block_id")
	val ticketId = javaUUID("ticket_id")
	override val primaryKey = PrimaryKey(blockId, ticketId)
}

object DocTemplates : Table("doc_templates") {
	val id = javaUUID("id")
	val slug = text("slug")
	val name = text("name")
	val summary = text("summary")
	val blocks = jsonb("blocks")
	override val primaryKey = PrimaryKey(id)
}

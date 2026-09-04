package dev.kanso.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed's view of `V39`. Its own file for the reason `CustomFieldTables.kt` gives:
 * `Tables.kt` is past six hundred lines and is the length at which the maintainer stops
 * re-reading a file.
 *
 * [minutes] and [startedAt] are both nullable and that is the table's whole grammar, held
 * shut by `time_entries_settled_or_running_chk`: **`minutes` null means the clock is still
 * running.** Nothing in Kotlin can widen that to a third shape — the CHECK is the authority
 * and [dev.kanso.repo.TimeEntry] is the only type that reads these two columns together.
 */
object TimeEntries : Table("time_entries") {
	val id = javaUUID("id")
	val ticketId = javaUUID("ticket_id")
	val userId = javaUUID("user_id")

	/** The timesheet day, `DATE` like `cycles.starts_on`: a day nobody's timezone moves. */
	val spentOn = date("spent_on")

	/** Null while the clock runs. See `V39`. */
	val minutes = integer("minutes").nullable()

	/** When the clock started, or null for a duration somebody typed. Never rewritten. */
	val startedAt = timestampWithTimeZone("started_at").nullable()

	val note = text("note").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	override val primaryKey = PrimaryKey(id)
}

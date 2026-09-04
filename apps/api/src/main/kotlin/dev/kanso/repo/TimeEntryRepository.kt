package dev.kanso.repo

import dev.kanso.db.TimeEntries
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.stereotype.Repository

/**
 * One row of somebody's timesheet, in the two shapes `V39` allows.
 *
 * [minutes] null means **the clock is still running**, and it is the only thing that means
 * that. [elapsedMinutes] is what a caller should read instead of branching on it by hand: it
 * is the settled figure when there is one and `now() - startedAt` when there is not, which
 * keeps the one derived number in this feature derived. A running entry asked twice a minute
 * apart answers differently, on purpose.
 *
 * [startedAt] is not "when the work happened" for a typed entry — it is absent there. The
 * day the work is filed under is [spentOn], always present, and it is the field a timesheet
 * groups by. Reading a date off [startedAt] instead would have no answer for half the rows.
 */
data class TimeEntry(
	val id: UUID,
	val ticketId: UUID,
	val userId: UUID,
	val spentOn: LocalDate,
	val minutes: Int?,
	val startedAt: OffsetDateTime?,
	val note: String?,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	val running: Boolean get() = minutes == null

	/**
	 * Whole minutes, rounded rather than truncated, and never negative.
	 *
	 * Rounded because a forty-second timer is a minute of somebody's day and flooring it to
	 * nought reads as a stopwatch that failed. `CycleTimeService.elapsedHours` refuses
	 * `toHours` for the same reason in the other unit — truncation is how a whole screen of
	 * short work comes out as zero.
	 *
	 * `coerceAtLeast(0)` guards the one case the product cannot produce and a clock skew can:
	 * a `started_at` in the future would otherwise put a negative number into a `SUM`, which
	 * is worse than a gap because nothing downstream would look wrong.
	 */
	fun elapsedMinutes(now: OffsetDateTime): Int =
		minutes ?: startedAt?.let { Math.round(Duration.between(it, now).toSeconds() / 60.0).toInt().coerceAtLeast(0) } ?: 0
}

/**
 * The timesheet rows, and the totals as `SUM`s.
 *
 * **Nothing in this class writes a column on `tickets`, and that is the design.** `V39`'s
 * header has the long version: a `tickets.logged_minutes` running total would make every
 * stopped timer an `UPDATE tickets`, which `V38` measured landing in two places — the
 * "recently updated" order, and `NotionPoller.kansoWins` winning a conflict with nothing
 * behind it and reverting a real Notion edit. [totalsFor] is that column's whole job, as a
 * grouped `SUM` over `time_entries_ticket_idx`, and it cannot go stale.
 *
 * [totalsFor] takes a *list* rather than one id for the reason `ActivityRepository.
 * firstEnteredAt` does: the day a list of tickets wants its hours in a column, the caller
 * must not have to choose between N queries and inventing a second read.
 */
@Repository
class TimeEntryRepository {

	fun findById(id: UUID): TimeEntry? =
		TimeEntries.selectAll().where { TimeEntries.id eq id }.map(::toDomain).singleOrNull()

	/** Newest day first, and within a day the newest row first: a timesheet is read backwards. */
	fun ofTicket(ticketId: UUID): List<TimeEntry> =
		TimeEntries.selectAll()
			.where { TimeEntries.ticketId eq ticketId }
			.orderBy(TimeEntries.spentOn to SortOrder.DESC, TimeEntries.createdAt to SortOrder.DESC)
			.map(::toDomain)

	/**
	 * The one running entry this person has, anywhere, or null.
	 *
	 * `singleOrNull` and not `firstOrNull`, deliberately: `time_entries_one_running_per_person_idx`
	 * makes two rows impossible, so a second one means the index is gone and the right
	 * behaviour is to fail loudly rather than to pick one of two clocks and settle it.
	 */
	fun runningFor(userId: UUID): TimeEntry? =
		TimeEntries.selectAll()
			.where { (TimeEntries.userId eq userId) and TimeEntries.minutes.isNull() }
			.map(::toDomain)
			.singleOrNull()

	fun insert(entry: TimeEntry) {
		TimeEntries.insert {
			it[id] = entry.id
			it[ticketId] = entry.ticketId
			it[userId] = entry.userId
			it[spentOn] = entry.spentOn
			it[minutes] = entry.minutes
			it[startedAt] = entry.startedAt
			it[note] = entry.note
			it[createdAt] = entry.createdAt
			it[updatedAt] = entry.createdAt
		}
	}

	/**
	 * Settles or corrects one row: the duration, the note and the day, and nothing else.
	 *
	 * `updated_at` is absent from the statement because `time_entries_set_updated_at` writes
	 * it — `V2`'s trigger, not `V38`'s, for the reason `V39` states. `started_at` is absent
	 * because it is a record of when a clock actually started and a correction is about the
	 * duration; rewriting it to make the arithmetic work out would be the stored-and-then-
	 * rewritten timestamp KAN-81 exists to warn about.
	 */
	fun settle(id: UUID, minutes: Int, note: String?, spentOn: LocalDate): Boolean =
		TimeEntries.update({ TimeEntries.id eq id }) {
			it[TimeEntries.minutes] = minutes
			it[TimeEntries.note] = note
			it[TimeEntries.spentOn] = spentOn
		} > 0

	fun delete(id: UUID): Boolean = TimeEntries.deleteWhere { TimeEntries.id eq id } > 0

	/**
	 * Logged minutes per ticket — the total, computed on read, for the tickets asked about.
	 *
	 * **A running clock contributes nothing here**, and that is not an oversight to fix
	 * later. `minutes` is null on those rows so `SUM` skips them, which is the honest
	 * arithmetic: an unsettled timer is not yet a duration anybody has claimed, and folding
	 * `now() - started_at` into a billable total would make the number on the screen change
	 * every time it was refreshed. The running entry is reported *beside* the total, as
	 * itself, so a reader can see there is more to come.
	 *
	 * Tickets with no entries are absent from the map rather than present as zero, following
	 * `ActivityRepository.firstEnteredAt` — and the caller renders that absence as "nothing
	 * logged", which is a different sentence from "zero minutes logged".
	 */
	fun totalsFor(ticketIds: List<UUID>): Map<UUID, Int> {
		if (ticketIds.isEmpty()) return emptyMap()
		val total = TimeEntries.minutes.sum()
		return TimeEntries
			.select(TimeEntries.ticketId, total)
			.where { TimeEntries.ticketId inList ticketIds }
			.groupBy(TimeEntries.ticketId)
			.mapNotNull { row -> row[total]?.let { row[TimeEntries.ticketId] to it } }
			.toMap()
	}

	private fun toDomain(row: ResultRow) = TimeEntry(
		id = row[TimeEntries.id],
		ticketId = row[TimeEntries.ticketId],
		userId = row[TimeEntries.userId],
		spentOn = row[TimeEntries.spentOn],
		minutes = row[TimeEntries.minutes],
		startedAt = row[TimeEntries.startedAt],
		note = row[TimeEntries.note],
		createdAt = row[TimeEntries.createdAt],
		updatedAt = row[TimeEntries.updatedAt],
	)
}

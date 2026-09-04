package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.repo.TimeEntry
import dev.kanso.service.TicketTime
import dev.kanso.service.TimeEntryService
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One row of a timesheet on the wire.
 *
 * [minutes] absent means **the clock is still running** — the same sentence `V39` writes into
 * the column, arriving at the client as an absent key because Jackson omits nulls. That is
 * the one field on this response a client must not test with `=== null`, and the reason
 * [elapsedMinutes] is here beside it: it is the settled duration when there is one and the
 * clock's reading when there is not, so a screen that only wants a number never has to
 * branch, and a screen that wants to draw a *running* row asks `minutes === undefined`.
 *
 * [startedAt] absent means the duration was typed rather than clocked. Two different absences
 * on one row, which is why both are documented here rather than inferred from each other:
 * a settled clocked entry has both fields, a settled typed entry has only [minutes], and a
 * running entry has only [startedAt].
 */
data class TimeEntryResponse(
	val id: UUID,
	val ticketId: UUID,
	val userId: UUID,
	val spentOn: LocalDate,
	/** Absent while the clock runs. Never zero for a running row. */
	val minutes: Int?,
	/** Absent for a duration somebody typed. Never rewritten by a correction. */
	val startedAt: OffsetDateTime?,
	/** Always present: [minutes] when settled, the clock's reading when not. */
	val elapsedMinutes: Int,
	val note: String?,
	val createdAt: OffsetDateTime,
	val updatedAt: OffsetDateTime,
) {
	companion object {
		fun of(entry: TimeEntry, now: OffsetDateTime) = TimeEntryResponse(
			id = entry.id,
			ticketId = entry.ticketId,
			userId = entry.userId,
			spentOn = entry.spentOn,
			minutes = entry.minutes,
			startedAt = entry.startedAt,
			elapsedMinutes = entry.elapsedMinutes(now),
			note = entry.note,
			createdAt = entry.createdAt,
			updatedAt = entry.updatedAt,
		)
	}
}

/**
 * A ticket's hours: the total, the rows, and whether *you* are clocking it.
 *
 * [totalMinutes] excludes any running clock, and [runningId] is how the client knows the
 * total is not the whole story yet — `TicketTime` has the argument. A single flattened
 * "total including my clock" was the alternative and it is the wrong shape: it would make the
 * billable figure move on every refresh, and a screen would have no way to draw the
 * difference between two hours logged and two hours logged plus a clock that has been running
 * since Tuesday.
 *
 * [runningId] rather than the whole entry, because the entry is already in [entries] — a
 * reader can see every clock on this ticket, including a colleague's. The id says which of
 * them is the caller's, and so whether to draw *stop* or *start*.
 */
data class TicketTimeResponse(
	val totalMinutes: Int,
	val entries: List<TimeEntryResponse>,
	/** The caller's own running entry, if they have one on this ticket. Absent otherwise. */
	val runningId: UUID?,
) {
	companion object {
		fun of(time: TicketTime, now: OffsetDateTime) = TicketTimeResponse(
			totalMinutes = time.totalMinutes,
			entries = time.entries.map { TimeEntryResponse.of(it, now) },
			runningId = time.running?.id,
		)
	}
}

/**
 * [spentOn] absent means "today, as the server reckons it".
 *
 * The client is expected to send it — the browser is the only party that knows the person's
 * actual day, and `V39` refuses to read `user_preferences.timezone` for a billable record.
 * The field is optional rather than required so the API's other callers are not obliged to
 * compute a date they do not care about, and the fallback is named rather than silent.
 */
data class LogTimeRequest(val minutes: Int, val note: String? = null, val spentOn: LocalDate? = null)

/** Starting a clock has no duration to send. [note] is what you are about to do, if you know. */
data class StartTimerRequest(val note: String? = null, val spentOn: LocalDate? = null)

/**
 * A correction. Every field optional, and absent means unchanged — the `PATCH` convention
 * `TicketController` already uses.
 *
 * There is no `startedAt` here and there will not be one: it records when a clock actually
 * started, and a correction is about the duration. KAN-81 is the price of a stored timestamp
 * somebody later rewrites.
 */
data class CorrectTimeRequest(
	val minutes: Int? = null,
	val note: String? = null,
	val spentOn: LocalDate? = null,
)

/**
 * KAN-26's routes: hours on a ticket, and the clock that produces them.
 *
 * **The reads hang off the ticket and the writes-to-one-row hang off the entry**, which is
 * not inconsistency — it is where each identifier is actually known. A timesheet is read per
 * ticket, so `GET /api/tickets/{id}/time`; a clock is stopped or a duration corrected by
 * whoever is holding that row's id, and requiring the ticket in the path too would let a
 * caller send a pair that does not match and oblige this file to decide which half to
 * believe. `TicketLinkController` splits the same way for the same reason.
 *
 * Every rule is in `TimeEntryService`, and none is here. That is the shape `ProgressController`
 * argues for: a controller that reached for `TicketAccess` itself would be a second place the
 * read-only seat has to be remembered, and the MCP tools and the importer reach these
 * services without passing through any controller at all.
 *
 * No route answers "how many hours has this person logged". See `TimeEntryService` — that is
 * deliberate and it is the reason nothing in this feature needs `ProgressAccess`.
 */
@RestController
class TimeEntryController(
	private val currentUser: CurrentUser,
	private val time: TimeEntryService,
) {

	@GetMapping("/api/tickets/{ticketId}/time")
	@Transactional(readOnly = true)
	fun ofTicket(@PathVariable ticketId: UUID): TicketTimeResponse =
		TicketTimeResponse.of(time.forTicket(currentUser.require(), ticketId), OffsetDateTime.now())

	@PostMapping("/api/tickets/{ticketId}/time")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	fun log(@PathVariable ticketId: UUID, @RequestBody body: LogTimeRequest): TimeEntryResponse {
		val entry = time.log(currentUser.require(), ticketId, body.minutes, body.note, body.spentOn)
		return TimeEntryResponse.of(entry, OffsetDateTime.now())
	}

	/**
	 * 409 when a clock is already running, naming the ticket it is on.
	 *
	 * Not idempotent, and not silently a no-op either: pressing start twice on one ticket is
	 * refused the same way as pressing it on a second ticket, because both are the same
	 * mistake — the person believes they are starting something and the app already had a
	 * clock they had forgotten. `TicketLinkRepository.link` returns false on a repeat rather
	 * than throwing, and that is right *there*, where the second press changes nothing a
	 * reader would notice. Here the difference between one clock and two is the whole datum.
	 */
	@PostMapping("/api/tickets/{ticketId}/time/start")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	fun start(@PathVariable ticketId: UUID, @RequestBody(required = false) body: StartTimerRequest?): TimeEntryResponse {
		val entry = time.start(currentUser.require(), ticketId, body?.spentOn, body?.note)
		return TimeEntryResponse.of(entry, OffsetDateTime.now())
	}

	@PostMapping("/api/time/{entryId}/stop")
	@Transactional
	fun stop(@PathVariable entryId: UUID): TimeEntryResponse =
		TimeEntryResponse.of(time.stop(currentUser.require(), entryId), OffsetDateTime.now())

	@PatchMapping("/api/time/{entryId}")
	@Transactional
	fun correct(@PathVariable entryId: UUID, @RequestBody body: CorrectTimeRequest): TimeEntryResponse {
		val entry = time.correct(currentUser.require(), entryId, body.minutes, body.note, body.spentOn)
		return TimeEntryResponse.of(entry, OffsetDateTime.now())
	}

	@DeleteMapping("/api/time/{entryId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	fun remove(@PathVariable entryId: UUID) = time.remove(currentUser.require(), entryId)
}

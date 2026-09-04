package dev.kanso.service

import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.TimeEntry
import dev.kanso.repo.TimeEntryRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One ticket's hours: the billable total, the rows behind it, and the caller's own clock.
 *
 * [totalMinutes] is a `SUM` and **excludes [running]**, which is the arithmetic decision on
 * this type. An unsettled clock is not a duration anybody has claimed yet, and folding
 * `now() - started_at` into the total would give a billable figure that changed every time
 * the page was refreshed — the one property a number somebody invoices must not have. The
 * running entry travels beside the total instead, the way `unestimated` travels with every
 * points total in this package: the screen can say there is more to come rather than quietly
 * being wrong about how much.
 *
 * [running] is **the caller's own** and nobody else's. Two people clocking one ticket is
 * ordinary, and both of their clocks are in [entries] where a reader can see them; this
 * field exists so the screen knows whether to draw *stop* or *start*, which is a question
 * about the person holding the mouse.
 *
 * Zero entries gives `totalMinutes = 0` and an empty list, and the client is expected to
 * read those together as "nothing logged" rather than printing `0h`. That is the one place
 * this type is deliberately less careful than `CycleTime` — a total of no rows genuinely is
 * nought minutes, where a *median* of no rows is not nought hours.
 */
data class TicketTime(
	val totalMinutes: Int,
	val entries: List<TimeEntry>,
	val running: TimeEntry?,
)

/**
 * KAN-26 — the hours somebody says they worked, which is the one figure in this product
 * that cannot be derived from anything already on disk.
 *
 * `CycleTimeService` opens by saying it needed no migration because `activity` had already
 * timestamped every status change. This is the opposite case and the contrast is the whole
 * design: an *elapsed* hour is a fact about a ticket that the database witnessed, and a
 * *worked* hour is an assertion by a person that nothing witnessed. There is no join that
 * recovers it. So the entry is stored — and the total, which *is* derivable from the
 * entries, never is. See `V39`.
 *
 * **No working-day conversion anywhere in this file, and for the opposite reason to
 * KAN-23's.** That ticket refused working days because excluding weekends needs a per-person
 * calendar `VelocityService` will not guess at. Here the calendar is not missing — it is
 * *arriving*, one entry at a time. A logged hour is already a worked hour: nobody logs the
 * Saturday they spent in the garden, so the weekend excludes itself. That is precisely why
 * time tracking is a separate feature from cycle time rather than a unit conversion of it,
 * and it is why nothing here divides minutes by a pace: `points ÷ velocity = working days`
 * is an estimate of work not yet done, and these are hours already spent. Multiplying one
 * by the other would produce a number with no referent.
 *
 * **Three rules about who may do what, and only the third is new.**
 *
 * 1. **Reading a ticket's hours is reading the ticket.** [TicketAccess.requireReadable], the
 *    same guard a ticket's comments and feed pass, so a private draft's hours are as private
 *    as its description and nothing else is. The total is the billable fact and the ticket is
 *    the invoice line — an agency that cannot read the hours on work it can read row by row
 *    cannot bill it.
 * 2. **Creating an entry is a write on the ticket.** [TicketAccess.require], which brings the
 *    read-only seat with it for free: a `VIEWER` logs no hours, and is told why in the
 *    sentence `TicketAccess.READS_NOT_WRITES` gives every other refused write.
 * 3. **Settling or correcting an entry is a write on your own timesheet**, so it asks a
 *    different question — see [mine]. This is the branch that had to be decided rather than
 *    inherited, and it is decided the same way KAN-41 decided who reads whose figures.
 *
 * **What is deliberately not here: any cross-ticket per-person total.** No route answers
 * "how many hours did Élie log in August". That number is the ranking chart KAN-40 refused
 * and KAN-41 put behind `ProgressAccess`, wearing an hour's clothes, and it is the half of
 * "time tracking" that turns a billing tool into a surveillance one. Not building it is why
 * this file needs no reading rule beyond the ticket's own: every read here is bounded by a
 * ticket the caller already reads. When somebody does add it, it goes through
 * `ProgressAccess.requireReadable` and nowhere else — an hours-per-person page is a
 * `ProgressResponse` by another name, and answering it differently would leave the instance
 * with two rules about the same disclosure.
 */
@Service
class TimeEntryService(
	private val entries: TimeEntryRepository,
	private val tickets: TicketRepository,
	private val access: TicketAccess,
) {

	@Transactional(readOnly = true)
	fun forTicket(actor: User, ticketId: UUID): TicketTime {
		access.requireReadable(actor, ticketId)
		val rows = entries.ofTicket(ticketId)
		return TicketTime(
			// Summed here rather than read back from the database a second time: the rows
			// are already in hand, and a total from one read and a list from another are two
			// numbers free to disagree. `entries.totalsFor` exists for the caller that wants
			// totals *without* the rows — a list of tickets — and the two agree because both
			// skip a null `minutes`.
			totalMinutes = rows.sumOf { it.minutes ?: 0 },
			entries = rows,
			running = rows.firstOrNull { it.running && it.userId == actor.id },
		)
	}

	/**
	 * A duration somebody types, for work already done.
	 *
	 * [spentOn] arrives from the client because the browser is the only party that knows the
	 * person's actual day — `V39`'s header has the argument, and the server's own date is the
	 * named fallback rather than a guess dressed up as a default.
	 */
	@Transactional
	fun log(actor: User, ticketId: UUID, minutes: Int, note: String?, spentOn: LocalDate?): TimeEntry {
		access.require(actor, writable(ticketId))
		val entry = TimeEntry(
			id = UUID.randomUUID(),
			ticketId = ticketId,
			userId = actor.id,
			spentOn = spentOn ?: LocalDate.now(),
			minutes = asserted(minutes),
			// Null, and not `now()`. A typed entry was not clocked, and stamping it with the
			// instant somebody happened to fill the form in would make `started_at` mean two
			// different things in one column — which is the field the screen reads to decide
			// whether a row was measured or remembered.
			startedAt = null,
			note = trimmed(note),
			createdAt = OffsetDateTime.now(),
			updatedAt = OffsetDateTime.now(),
		)
		entries.insert(entry)
		return entry
	}

	/**
	 * Starts the caller's clock on this ticket.
	 *
	 * **Refuses rather than stopping the clock that is already running**, and names the
	 * ticket it is on. Silently settling the other one would be a write nobody asked for,
	 * fixing a duration on somebody's behalf at an instant they did not choose — and a
	 * mis-aimed click would then have quietly closed off work they were still doing, with
	 * nothing on any screen to say it happened. The cost is a round trip: the client has to
	 * offer "stop that one and start this one" as two calls, which is one more gesture and
	 * leaves the decision to settle with the person making it.
	 *
	 * The 409 is belt to `time_entries_one_running_per_person_idx`'s braces. The index is
	 * what makes two clocks impossible; this is what makes the refusal a sentence rather
	 * than a constraint violation.
	 */
	@Transactional
	fun start(actor: User, ticketId: UUID, spentOn: LocalDate?, note: String?): TimeEntry {
		access.require(actor, writable(ticketId))
		entries.runningFor(actor.id)?.let { open ->
			// Named by its title and not its `KAN-142`: `Ticket` carries a `number` and the
			// key lives on the team, so an identifier here would be a second query for a
			// sentence — and a title is what the person actually recognises as the thing they
			// left running.
			val other = tickets.findById(open.ticketId)
			throw ConflictException(
				"Your timer is already running on “${other?.title ?: "another ticket"}”. Stop it first."
			)
		}
		val entry = TimeEntry(
			id = UUID.randomUUID(),
			ticketId = ticketId,
			userId = actor.id,
			spentOn = spentOn ?: LocalDate.now(),
			// The clock is running: there is no duration yet, and null is how the schema says
			// so. See `V39`.
			minutes = null,
			startedAt = OffsetDateTime.now(),
			note = trimmed(note),
			createdAt = OffsetDateTime.now(),
			updatedAt = OffsetDateTime.now(),
		)
		entries.insert(entry)
		return entry
	}

	/**
	 * Stops a running clock and writes what it measured.
	 *
	 * **Whatever it measured**, with no ceiling — `V39` says why a cap here would be a trap
	 * rather than a guard: a timer forgotten for six weeks has to be stoppable, or it holds
	 * the person's one index slot for ever and no gesture in the product can free it. A nine
	 * hour entry nobody meant is then a correction away from being right, which is exactly
	 * what [correct] is for and why the duration is the column that gets stored.
	 *
	 * `spentOn` is deliberately not recomputed. A clock started at 23:40 and stopped at 00:20
	 * belongs to the day it started, which is the day the person will look for it under.
	 */
	@Transactional
	fun stop(actor: User, entryId: UUID, now: OffsetDateTime = OffsetDateTime.now()): TimeEntry {
		val entry = mine(actor, entryId)
		if (!entry.running) throw ConflictException("That entry is not running; its duration is already ${entry.minutes} minutes")
		val settled = entry.elapsedMinutes(now)
		entries.settle(entry.id, settled, entry.note, entry.spentOn)
		return entry.copy(minutes = settled)
	}

	/**
	 * Corrects a settled entry: the duration, the note, the day.
	 *
	 * The gesture the whole schema is shaped around. Because the duration is a stored column
	 * rather than `ended_at - started_at`, "that was forty minutes, not nine hours" is one
	 * `UPDATE` and it does not have to lie about when the clock stopped — KAN-81's warning,
	 * heeded by having nothing to rewrite. `started_at` is untouched and stays the record of
	 * when work actually began.
	 *
	 * A running entry is refused: there is nothing to correct yet, and accepting a duration
	 * for one would settle it through a route whose name says it is not settling anything.
	 */
	@Transactional
	fun correct(actor: User, entryId: UUID, minutes: Int?, note: String?, spentOn: LocalDate?): TimeEntry {
		val entry = mine(actor, entryId)
		if (entry.running) throw ConflictException("That timer is still running; stop it before correcting it")
		val next = entry.copy(
			minutes = minutes?.let { asserted(it) } ?: entry.minutes,
			note = note?.let { trimmed(it) } ?: entry.note,
			spentOn = spentOn ?: entry.spentOn,
		)
		entries.settle(next.id, next.minutes!!, next.note, next.spentOn)
		return next
	}

	@Transactional
	fun remove(actor: User, entryId: UUID) {
		entries.delete(mine(actor, entryId).id)
	}

	/**
	 * The entry, if this actor may settle or correct it. Rule three, and the only new one.
	 *
	 * **Your own entry is always yours**, first branch so nothing below can take it away —
	 * `ProgressAccess` puts the self branch first for the same reason and states the same
	 * consequence: the seat does not matter. That is not laxity here, it is a repair. A
	 * person whose ticket moved to a team they cannot write, or who was demoted to `VIEWER`
	 * this morning, would otherwise be unable to stop their own running clock, and the index
	 * would hold that slot for ever. Settling a row about your own hours is not a write on
	 * the team's work.
	 *
	 * **Somebody else's needs [TicketAccess.teamsLedBy]** — which is the identical rule
	 * `ProgressAccess` applies to reading somebody else's figures, reached through the
	 * identical primitive, and that is on purpose: KAN-41 decided that one person's numbers
	 * belong to them, their instance admins and their team's titled administrator, and an
	 * hour is one of that person's numbers. Answering it differently would leave the instance
	 * with two rules about the same disclosure.
	 *
	 * Reached through `teamsLedBy` and **not** through `ProgressAccess.requireReadable`,
	 * though the branches are the same. That method's refusal says "X's figures are
	 * *readable* by…", which is the wrong sentence to hand somebody who tried to delete a
	 * row — and `ProgressAccess` builds its own branches out of `teamsLedBy` too, so this
	 * shares the rule rather than a spelling of it.
	 *
	 * A teamless draft has no team to lead, so its author's entries are the author's alone.
	 * Strictly narrower than anything the team rule grants, which is the direction
	 * `TicketAccess.mayEditDraft` takes for the same state.
	 */
	private fun mine(actor: User, entryId: UUID): TimeEntry {
		val entry = entries.findById(entryId) ?: throw NotFoundException("No time entry $entryId")
		// A read on the ticket first, and with the same words a missing ticket gets: an entry
		// on a draft somebody else wrote must not confirm its own existence to a caller who
		// cannot see the ticket it hangs off.
		access.requireReadable(actor, entry.ticketId)
		if (entry.userId == actor.id) return entry
		val teamId = tickets.findById(entry.ticketId)?.teamId
		if (teamId != null && access.teamsLedBy(actor, setOf(teamId)).isNotEmpty()) return entry
		throw AccessDeniedException(
			"Those hours are somebody else's; they are theirs to change, and an administrator of their team's"
		)
	}

	private fun writable(ticketId: UUID): Ticket =
		tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")

	/**
	 * A duration a **person** asserted, bounded — as against one a clock measured, which
	 * [stop] accepts whatever it says.
	 *
	 * Zero is refused here and legal in the schema, and the split is the point: the clock may
	 * say nought minutes, because a stopwatch pressed twice inside half a minute honestly
	 * measured almost nothing and the row can be deleted. A person typing nought is not
	 * logging anything, and the gesture they wanted was delete.
	 */
	private fun asserted(minutes: Int): Int {
		if (minutes < 1) throw BadRequestException("An entry of no time is not an entry")
		if (minutes > MAX_ASSERTED_MINUTES) {
			throw BadRequestException("$minutes minutes is longer than a month; log it as several entries")
		}
		return minutes
	}

	/** Empty becomes absent, so `note != null` means there is something to read. */
	private fun trimmed(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }?.let {
		if (it.length > MAX_NOTE) throw BadRequestException("A note is at most $MAX_NOTE characters") else it
	}

	companion object {
		/**
		 * The ceiling on what somebody can type, and there is deliberately none on what the
		 * clock can measure.
		 *
		 * Thirty-one days because an agency bills monthly: an entry longer than the invoicing
		 * period is a typo — a `4800` where `480` was meant — not a claim anybody is making.
		 * Stated in minutes rather than as a magic number so the unit is visible at the one
		 * place it is decided.
		 */
		const val MAX_ASSERTED_MINUTES = 60 * 24 * 31

		/** Matches `time_entries_note_chk`. An invoice line, not a comment thread. */
		const val MAX_NOTE = 500
	}
}

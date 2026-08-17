package dev.kanso.service

import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What the strip at the bottom of screen 21 can do to a selection.
 *
 * `null` means "leave alone", as in [TicketPatch]. There is no `unset`: the strip has no
 * control that clears a field, and inventing one here would be a wire shape with no
 * screen behind it.
 */
data class BulkEdit(
	val ticketIds: List<UUID>,
	val status: TicketStatus? = null,
	val priority: TicketPriority? = null,
	val assigneeIds: List<UUID>? = null,
	val cycleId: UUID? = null,
)

/**
 * The selection strip: six rows changed at once, or none changed at all.
 *
 * The whole selection is checked before any of it is written. Rolling back after the
 * third row was refused would give the same end state, but only if the transaction can be
 * trusted to unwind — and it makes the guarantee invisible to a suite that never commits.
 * Checking first makes "nothing changed" a property of the code rather than of the
 * transaction manager, which is also the only version of it a test can observe.
 *
 * Each row still goes through [TicketService.patch] rather than one bulk UPDATE: the
 * mirror push, the event and the dependency cascade all hang off that method. A selection
 * is a handful of rows by construction — `x` on each one — so N patches in one request is
 * the cheaper half of the trade.
 */
@Service
class BulkEditService(
	private val tickets: TicketService,
	private val ticketRepo: TicketRepository,
	private val cycles: CycleService,
	private val access: TicketAccess,
) {

	@Transactional
	fun apply(actor: User, edit: BulkEdit): Int {
		val selection = resolve(actor, edit.ticketIds)
		if (edit.status == null && edit.priority == null && edit.assigneeIds == null && edit.cycleId == null) {
			throw BadRequestException("A bulk edit has to change something")
		}

		// The cycle move once for the whole selection rather than per ticket: it is the only
		// field `patch` does not own, and doing it per row would take the same lock N times.
		edit.cycleId?.let { cycles.addTickets(actor, it, edit.ticketIds) }

		if (edit.status != null || edit.priority != null || edit.assigneeIds != null) {
			val patch = TicketPatch(
				status = edit.status,
				priority = edit.priority,
				assigneeIds = edit.assigneeIds,
			)
			selection.forEach { tickets.patch(actor, it, patch) }
		}
		return selection.size
	}

	@Transactional
	fun delete(actor: User, ticketIds: List<UUID>): Int {
		val selection = resolve(actor, ticketIds)
		selection.forEach { tickets.delete(actor, it) }
		return selection.size
	}

	/**
	 * Every id in the selection exists and is writable by this actor, or nothing happens.
	 * Returned in the order they were selected, so a failure part-way through a patch loop
	 * is at least reproducible.
	 */
	private fun resolve(actor: User, ticketIds: List<UUID>): List<UUID> {
		if (ticketIds.isEmpty()) throw BadRequestException("Nothing was selected")
		val found = ticketRepo.findAllById(ticketIds)
		val missing = ticketIds.toSet() - found.map { it.id }.toSet()
		if (missing.isNotEmpty()) throw BadRequestException("Unknown tickets: ${missing.joinToString()}")
		found.forEach { access.require(actor, it) }
		return ticketIds.distinct()
	}
}

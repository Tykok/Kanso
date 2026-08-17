package dev.kanso.service

import dev.kanso.domain.Accent
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.Label
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.repo.LabelRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Labels, and what a ticket wears.
 *
 * Team-scoped throughout, which is one rule with two halves: a label is created inside
 * a team, and a ticket may only wear a label its own team owns. The second half is what
 * makes the first mean anything — without it a global namespace has been rebuilt by
 * hand, one attachment at a time, and a saved view filtering on `label = sync` would
 * quietly cross into another team's work.
 *
 * A team-less label has no reading here, unlike a team-less project: a project without
 * a team is transverse work, while a label without a team is a word nobody agreed on.
 */
@Service
class LabelService(
	private val labels: LabelRepository,
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
	private val access: TicketAccess,
	private val activity: ActivityService,
) {

	@Transactional(readOnly = true)
	fun list(teamId: UUID): List<Label> = labels.forTeam(teamId)

	@Transactional(readOnly = true)
	fun forTicket(ticketId: UUID): List<Label> = labels.forTicket(ticketId)

	@Transactional
	fun create(actor: User, teamId: UUID, name: String, colour: String): Label {
		// First, as every write in this package does.
		access.requireTeam(actor, teamId)
		if (teams.findById(teamId) == null) throw BadRequestException("No team $teamId")

		val trimmed = name.trim()
		if (trimmed.isEmpty()) throw BadRequestException("A label needs a name")
		// Pre-checked rather than left to `UNIQUE (team_id, name)`, so a second `sync` is
		// the 409 it is instead of a 500 from the driver. The index is still the backstop.
		if (labels.findByName(teamId, trimmed) != null) {
			throw ConflictException("${teams.findById(teamId)?.name ?: "That team"} already has a label called $trimmed")
		}
		return labels.insert(UUID.randomUUID(), teamId, trimmed, accent(colour))
	}

	@Transactional
	fun attach(actor: User, ticketId: UUID, labelId: UUID) {
		val ticket = requireEditableTicket(actor, ticketId)
		val label = requireOwnLabel(ticket, labelId)
		labels.attach(ticketId, labelId)
		record(actor, ticket, label, attached = true)
	}

	@Transactional
	fun detach(actor: User, ticketId: UUID, labelId: UUID) {
		val ticket = requireEditableTicket(actor, ticketId)
		val label = requireOwnLabel(ticket, labelId)
		if (labels.detach(ticketId, labelId)) record(actor, ticket, label, attached = false)
	}

	/**
	 * The whole set at once, which is what the pill row saves.
	 *
	 * Every id is checked before anything is written: a list holding one foreign label is
	 * refused whole, and the labels the ticket already wore are still there afterwards.
	 * The log still gets a row per label that actually moved, not one per id sent.
	 */
	@Transactional
	fun set(actor: User, ticketId: UUID, labelIds: List<UUID>): List<Label> {
		val ticket = requireEditableTicket(actor, ticketId)
		val wanted = labelIds.distinct().map { requireOwnLabel(ticket, it) }
		val before = labels.forTicket(ticketId).associateBy { it.id }

		for (label in wanted.filter { it.id !in before }) {
			labels.attach(ticketId, label.id)
			record(actor, ticket, label, attached = true)
		}
		for (label in before.values.filter { existing -> wanted.none { it.id == existing.id } }) {
			labels.detach(ticketId, label.id)
			record(actor, ticket, label, attached = false)
		}
		return labels.forTicket(ticketId)
	}

	/**
	 * One label onto a whole selection — screen 21's sixth strip button.
	 *
	 * It adds rather than replaces, which is the one place this service differs from
	 * [set]: the strip acts on rows whose labels are not on screen, and a replace would
	 * silently take off whatever each of them already wore.
	 *
	 * Every ticket is resolved and checked before the first row is written, the promise
	 * [BulkEditService] makes about the rest of the strip — a refusal half-way through
	 * would leave nobody able to say what the selection now is. Rows already wearing the
	 * label are skipped rather than re-attached, so the log gets no second row for a
	 * label that did not move; [attach] alone is idempotent in the table but not in the
	 * feed. Answers with the size of the selection, which is what the strip reports and
	 * what `apply` answers for every other button — "6 selected" is the number a reader
	 * just acted on, not the number of rows that happened to be missing the label.
	 */
	@Transactional
	fun attachAll(actor: User, ticketIds: List<UUID>, labelId: UUID): Int {
		val label = labels.findById(labelId) ?: throw NotFoundException("No label $labelId")
		val selection = ticketIds.distinct().map { requireEditableTicket(actor, it) }
		selection.forEach { requireOwnLabel(it, labelId) }

		val moved = selection.filter { ticket -> labels.forTicket(ticket.id).none { it.id == label.id } }
		for (ticket in moved) {
			labels.attach(ticket.id, label.id)
			record(actor, ticket, label, attached = true)
		}
		return selection.size
	}

	// --- helpers -------------------------------------------------------------

	private fun requireEditableTicket(actor: User, ticketId: UUID): Ticket {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)
		return ticket
	}

	/**
	 * A 409 rather than a 404: the label exists and the actor may well be allowed to see
	 * it, which is a different thing from it belonging on this ticket.
	 */
	private fun requireOwnLabel(ticket: Ticket, labelId: UUID): Label {
		val label = labels.findById(labelId) ?: throw NotFoundException("No label $labelId")
		if (label.teamId != ticket.teamId) {
			throw ConflictException("Label ${label.name} belongs to another team than ${ticket.title}")
		}
		return label
	}

	/** Caught here so an unknown colour is the 400 it always was, not a 500 from the CHECK. */
	private fun accent(raw: String): Accent = try {
		Accent.from(raw)
	} catch (e: IllegalArgumentException) {
		throw BadRequestException(e.message ?: "Invalid colour '$raw'")
	}

	private fun record(actor: User, ticket: Ticket, label: Label, attached: Boolean) = activity.record(
		ActivityEntity.TICKET,
		ticket.id,
		actor.id,
		ActivityKind.LABELLED,
		// The name travels with the row: a label can be renamed or deleted, and a feed
		// that only kept the id would then have nothing to print.
		mapOf("labelId" to label.id.toString(), "name" to label.name, "attached" to attached),
	)
}

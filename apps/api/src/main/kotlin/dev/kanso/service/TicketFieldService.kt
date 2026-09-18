package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.CustomField
import dev.kanso.domain.Ticket
import dev.kanso.domain.User
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.CustomFieldRepository
import dev.kanso.repo.TicketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * What a ticket's fields are worth — the value half of `V35`.
 *
 * **The guard is [TicketAccess.require], the same one `LabelService.attach` uses.** Valuing a
 * field is editing a ticket: it is the ticket's row that changes, the ticket's team that
 * decides who may, and the ticket's feed that records it. [CustomFieldService] holds the
 * other half, where the guard is the team instead.
 *
 * The seat falls out of that for free and is not mentioned again — `require` asks
 * `requireSeatThatWrites` before it asks about the team, so a viewer is refused with
 * `TicketAccess.READS_NOT_WRITES` whether or not they could edit the ticket otherwise. That
 * is also what makes an agent's write refuse without a line of MCP-specific code.
 */
@Service
class TicketFieldService(
	private val fields: CustomFieldRepository,
	private val tickets: TicketRepository,
	private val access: TicketAccess,
	private val activity: ActivityService,
	/**
	 * The one funnel, reused — never a second one.
	 *
	 * A field value is part of what `TicketResponse` says, so a ticket whose severity moved is
	 * a ticket that changed, and everything downstream of [EventPublisher] needs to hear it:
	 * a second browser holding the same list, and — since `KAN-17` — a webhook subscriber
	 * mirroring tickets. Staying silent would make a custom field the one part of a ticket an
	 * integration could only discover by polling, which is a strange thing to ship in the same
	 * breath as putting it on the public shape.
	 *
	 * `EventPublisher.publish` is where the webhook fan-out already lives, precisely so that
	 * "this entity changed" is said once. So this calls that, with the ticket event every
	 * other ticket write publishes; it does not reach `WebhookFanout`, and there is no second
	 * delivery path to keep in step.
	 *
	 * `LabelService` publishes nothing for an attach, and that is a real inconsistency rather
	 * than a precedent this follows. The difference that decides it: a label lives in its own
	 * cache entry and its own endpoint, so a client that wants one asks for one, while
	 * `customFields` rides on the ticket row and goes stale with it. Fixing the label side is
	 * not this ticket's business and is deliberately left alone.
	 */
	private val events: EventPublisher,
	private val json: ObjectMapper,
) {

	/**
	 * One ticket's values, by field id.
	 *
	 * Guarded by [TicketAccess.requireReadable] rather than left open, which is the lesson
	 * the four unguarded reads hanging off a ticket id already taught this codebase: a draft
	 * is private to whoever wrote it, and its custom fields are as much of its content as its
	 * comments are.
	 */
	@Transactional(readOnly = true)
	fun valuesOf(actor: User, ticketId: UUID): Map<UUID, Any> {
		access.requireReadable(actor, ticketId)
		return fields.valuesOf(ticketId)
	}

	/**
	 * Set, change or clear the fields named in [wanted] — and only those.
	 *
	 * A partial write by design, unlike `PUT /api/tickets/{id}/labels` beside it. The pill
	 * row knows the whole set a ticket should wear, so a replace is the right shape there; a
	 * field panel is a form where somebody edited one input, and a whole-set replace would
	 * make "I changed the severity" indistinguishable from "clear every other field", with
	 * the second one arriving whenever a client was built against a definition list it had
	 * fetched before somebody added a field. So a key that is absent is untouched, and a key
	 * whose value is null is cleared — the same reading `TicketPatch` gives, and the reason
	 * that class needs an `unset` set where this one does not: jsonb has a real null and
	 * clearing here means deleting a row, so the two gestures already have two spellings.
	 *
	 * **Everything is resolved and validated before the first row is written.** That is the
	 * promise `LabelService.set` and `BulkEditService` make, and it matters more here: a
	 * request setting four fields of which the third is the wrong type would otherwise leave
	 * two written, the caller told it failed, and nobody able to say what the ticket now
	 * holds. `@Transactional` would roll the statements back anyway — this is belt and
	 * braces, and it is what lets the refusal name the field rather than the row count.
	 */
	@Transactional
	fun setValues(actor: User, ticketId: UUID, wanted: Map<String, Any?>): Map<UUID, Any> {
		val ticket = tickets.findById(ticketId) ?: throw NotFoundException("No ticket $ticketId")
		access.require(actor, ticket)

		// Resolved first, whole. A definition is fetched once per key rather than per write,
		// which also means a repeated key is a repeated lookup and not a repeated statement.
		val resolved = wanted.map { (rawId, rawValue) ->
			val field = requireOwnField(ticket, rawId)
			field to FieldValueCodec.validate(field, rawValue)
		}

		val before = fields.valuesOf(ticketId)
		var moved = false
		for ((field, value) in resolved) {
			val had = before[field.id]
			// Nothing written and nothing logged for a value that did not move. Without this a
			// form that re-submits every input on every save would fill the feed with rows
			// saying a field is still what it already was — the same reason `LabelService.set`
			// records only the labels that actually moved.
			if (had == value) continue
			if (value == null) fields.clearValue(ticketId, field.id) else {
				fields.setValue(ticketId, field.id, FieldValueCodec.encode(json, value))
			}
			record(actor, ticket, field, had, value)
			moved = true
		}
		// Once for the request, not once per field: a form saving four inputs is one decision,
		// and four events would be four webhook deliveries and four refetches of the same row.
		// Guarded on something having actually changed, for the same reason the log above is —
		// a re-submitted form that moved nothing is not a change anybody should be told about.
		if (moved) {
			events.publish(
				KansoEvent.ticket(
					ChangeKind.UPDATED,
					ticket.id,
					ticket.teamId,
					ticket.projectId,
					ticket.createdBy,
				),
			)
		}
		return fields.valuesOf(ticketId)
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * The field this id names, if it is one this ticket may hold.
	 *
	 * A 409 rather than a 404 for a field belonging to another team, exactly as
	 * `LabelService.requireOwnLabel` reasons: the field exists and the caller may well be
	 * allowed to read it, which is a different thing from it belonging on this ticket. Without
	 * this check a team-scoped vocabulary would be a global one rebuilt by hand, one write at
	 * a time — and a value stored against a foreign field would be unreadable by the only
	 * screen that knows how to render it.
	 *
	 * A draft is refused with a sentence of its own. It has no team, so there is no set of
	 * definitions to check against, and "belongs to another team" would be a confusing way to
	 * say "belongs to no team".
	 */
	private fun requireOwnField(ticket: Ticket, rawId: String): CustomField {
		val id = try {
			UUID.fromString(rawId)
		} catch (notAnId: IllegalArgumentException) {
			throw BadRequestException("`$rawId` is not a custom field id")
		}
		val field = fields.findById(id) ?: throw BadRequestException("No custom field $id")
		val teamId = ticket.teamId ?: throw ConflictException(
			"${ticket.title} belongs to no team yet, and a custom field is a team's;" +
				" file it into one before setting \"${field.name}\"",
		)
		if (field.teamId != teamId) {
			throw ConflictException("Field \"${field.name}\" belongs to another team than ${ticket.title}")
		}
		return field
	}

	/**
	 * The payload carries the name as well as the id, for the reason `LabelService` gives: a
	 * definition can be renamed or deleted, and a feed holding only the id would have nothing
	 * left to print. `from` and `to` are absent rather than null when there was no value —
	 * the shared mapper omits nulls, so "cleared" reads as a missing `to`.
	 */
	private fun record(actor: User, ticket: Ticket, field: CustomField, from: Any?, to: Any?) = activity.record(
		ActivityEntity.TICKET,
		ticket.id,
		actor.id,
		ActivityKind.FIELD_SET,
		mapOf(
			"fieldId" to field.id.toString(),
			"name" to field.name,
			"type" to field.type.wire,
			"from" to from,
			"to" to to,
		),
	)
}

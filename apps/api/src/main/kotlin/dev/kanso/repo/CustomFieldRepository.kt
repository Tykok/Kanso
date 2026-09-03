package dev.kanso.repo

import dev.kanso.db.CustomFields
import dev.kanso.db.TicketFieldValues
import dev.kanso.domain.CustomField
import dev.kanso.domain.CustomFieldType
import dev.kanso.service.FieldValueCodec
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * `custom_fields` and `ticket_field_values`, in one repository because they are one feature
 * and neither is readable without the other — the shape `LabelRepository` already has for
 * `labels` and `ticket_labels`.
 */
@Repository
class CustomFieldRepository(private val json: ObjectMapper) {

	// --- definitions ---------------------------------------------------------

	fun findById(id: UUID): CustomField? =
		CustomFields.selectAll().where { CustomFields.id eq id }.singleOrNull()?.toField()

	/** By name, because the only place this is drawn is a list somebody reads. */
	fun forTeam(teamId: UUID): List<CustomField> =
		CustomFields.selectAll().where { CustomFields.teamId eq teamId }
			.orderBy(CustomFields.name to SortOrder.ASC)
			.map { it.toField() }

	/** Exactly the comparison `UNIQUE (team_id, name)` makes, so the pre-check cannot miss. */
	fun findByName(teamId: UUID, name: String): CustomField? =
		CustomFields.selectAll().where { (CustomFields.teamId eq teamId) and (CustomFields.name eq name) }
			.singleOrNull()?.toField()

	fun insert(field: CustomField): CustomField {
		CustomFields.insert {
			it[CustomFields.id] = field.id
			it[CustomFields.teamId] = field.teamId
			it[CustomFields.name] = field.name
			it[CustomFields.type] = field.type.wire
			it[CustomFields.required] = field.required
			it[CustomFields.choices] = json.writeValueAsString(field.options)
		}
		return requireNotNull(findById(field.id))
	}

	/**
	 * Name, required and options — never [CustomField.type].
	 *
	 * The type is absent from this statement on purpose and the service refuses to change it
	 * rather than silently ignoring the attempt. Retyping a field would leave every existing
	 * value disagreeing with its definition, which is the one state the write path is built
	 * to make impossible; there is no migration a user can run for it, and a "convert the
	 * values" pass would be a lossy rewrite of somebody's data behind a settings toggle.
	 * Deleting and re-creating is the honest gesture, and it is the one that says out loud
	 * that the values are going.
	 */
	fun update(id: UUID, name: String, required: Boolean, options: List<String>): CustomField? {
		CustomFields.update({ CustomFields.id eq id }) {
			it[CustomFields.name] = name
			it[CustomFields.required] = required
			it[CustomFields.choices] = json.writeValueAsString(options)
		}
		return findById(id)
	}

	/** The values go with it — `V32` carries the argument for the cascade. */
	fun delete(id: UUID): Boolean = CustomFields.deleteWhere { CustomFields.id eq id } > 0

	// --- values --------------------------------------------------------------

	/**
	 * How many tickets hold a value for each of these fields, in one grouped query.
	 *
	 * A field nobody has filled is absent rather than zero — the caller reads a missing key
	 * as none, the same way `TicketQueryRepository.groupCounts` leaves empty buckets out.
	 */
	fun valueCounts(fieldIds: Collection<UUID>): Map<UUID, Int> {
		if (fieldIds.isEmpty()) return emptyMap()
		val tally = TicketFieldValues.ticketId.count()
		return TicketFieldValues.select(TicketFieldValues.fieldId, tally)
			.where { TicketFieldValues.fieldId inList fieldIds }
			.groupBy(TicketFieldValues.fieldId)
			.associate { it[TicketFieldValues.fieldId] to it[tally].toInt() }
	}

	/**
	 * Which of a `select` field's choices are actually spoken for.
	 *
	 * Read before an option is taken off a definition, so that shrinking the list cannot
	 * strand a value outside it. Without this, "every stored value satisfies its definition"
	 * would only be true at the moment of writing — it would stop being a property of the
	 * table and become a property of the past.
	 */
	fun optionsInUse(fieldId: UUID): Set<String> =
		TicketFieldValues.select(TicketFieldValues.value)
			.where { TicketFieldValues.fieldId eq fieldId }
			.withDistinct()
			.mapNotNullTo(mutableSetOf()) { FieldValueCodec.decode(json, it[TicketFieldValues.value]) as? String }

	/**
	 * **The hot read.** Every value for a whole page of tickets, in one statement.
	 *
	 * This is the function that decides whether custom fields cost a list anything. It is
	 * shaped for `TicketDetails.of`, which already loads assignees and docs exactly this way
	 * — one query per relation for the page, never one per row — so three fields defined on
	 * two hundred tickets is *one* more query than the list cost before `V32`, not two
	 * hundred and not six hundred.
	 *
	 * No join with `custom_fields`, which is what keeps it to one. A jsonb scalar is
	 * self-describing, so decoding a value needs no definition: the type is only wanted when
	 * a value is *written* or *named*, and both of those already hold the definitions.
	 */
	fun valuesFor(ticketIds: Collection<UUID>): Map<UUID, Map<UUID, Any>> {
		if (ticketIds.isEmpty()) return emptyMap()
		return TicketFieldValues.selectAll()
			.where { TicketFieldValues.ticketId inList ticketIds }
			.groupBy({ it[TicketFieldValues.ticketId] }) {
				it[TicketFieldValues.fieldId] to FieldValueCodec.decode(json, it[TicketFieldValues.value])
			}
			.mapValues { (_, pairs) -> pairs.toMap() }
	}

	/** The one-ticket read, for the write path's answer. */
	fun valuesOf(ticketId: UUID): Map<UUID, Any> = valuesFor(listOf(ticketId))[ticketId].orEmpty()

	/**
	 * Written by field, not by ticket: setting one field must not disturb the others, which
	 * a whole-row replace would. `PUT /api/tickets/{id}/fields` sends the fields it means.
	 */
	fun setValue(ticketId: UUID, fieldId: UUID, encoded: String) {
		TicketFieldValues.upsert(TicketFieldValues.ticketId, TicketFieldValues.fieldId) {
			it[TicketFieldValues.ticketId] = ticketId
			it[TicketFieldValues.fieldId] = fieldId
			it[TicketFieldValues.value] = encoded
		}
	}

	/** Clearing is a delete, because `V32` keeps no JSON null to mean the same thing. */
	fun clearValue(ticketId: UUID, fieldId: UUID): Boolean =
		TicketFieldValues.deleteWhere {
			(TicketFieldValues.ticketId eq ticketId) and (TicketFieldValues.fieldId eq fieldId)
		} > 0

	@Suppress("UNCHECKED_CAST")
	private fun ResultRow.toField(): CustomField {
		val type = CustomFieldType.from(this[CustomFields.type])
		return CustomField(
			id = this[CustomFields.id],
			teamId = this[CustomFields.teamId],
			name = this[CustomFields.name],
			type = type,
			required = this[CustomFields.required],
			// The cast is unchecked because Jackson erases to `List<*>`, and it is safe for
			// the only values that can be here: the column is written from a `List<String>`
			// this repository serialised, and `CustomFieldService` refuses a non-string
			// option before it reaches the statement. `custom_fields_options_chk` guarantees
			// it is at least an array, so a malformed document throws inside Jackson.
			options = json.readValue(this[CustomFields.choices], List::class.java) as List<String>,
		)
	}
}

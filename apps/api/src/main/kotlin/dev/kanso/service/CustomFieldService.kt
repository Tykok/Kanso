package dev.kanso.service

import dev.kanso.domain.CustomField
import dev.kanso.domain.CustomFieldType
import dev.kanso.domain.FieldInUse
import dev.kanso.domain.User
import dev.kanso.repo.CustomFieldRepository
import dev.kanso.repo.TeamRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Defining a team's fields — the shape half. [TicketFieldService] holds the value half.
 *
 * Two services because they are two different acts guarded two different ways, and the seam
 * is where the guard changes: *deciding a team describes its work with a `Severity`* is a
 * decision about the team, and *saying this ticket's severity is high* is a decision about a
 * ticket. `LabelService` runs both halves in one file and is the reason not to — it is the
 * file where `create` reaches `requireTeam` and `attach` reaches `require`, four methods
 * apart, and telling which rule governs which method means reading all of it.
 *
 * **The guard is [TicketAccess.requireTeam], the same one `LabelService.create` uses.** A
 * field is a team-scoped object like a label, a cycle or a saved view, so it gets the
 * boundary this product already has rather than a new one.
 *
 * Requiring an instance admin instead was the considered alternative, on the argument that a
 * field changes the shape of every ticket in a team and is therefore configuration rather
 * than data. It was rejected on what it does to a self-hosted instance: a team that cannot
 * add a column to its own board without escalating to whoever owns the deployment will not
 * escalate, it will put the value in the title. And the risk it removes is small — the blast
 * radius of a bad definition is one team, it is visible on that team's settings screen, and
 * deleting it is one gesture with a count printed beside it.
 *
 * The seat is not mentioned anywhere in this file and is enforced regardless:
 * `requireTeam` asks `requireSeatThatWrites` before it asks about the team, so a viewer is
 * refused with `TicketAccess.READS_NOT_WRITES` whether or not they are in it.
 */
@Service
class CustomFieldService(
	private val fields: CustomFieldRepository,
	private val teams: TeamRepository,
	private val access: TicketAccess,
) {

	/**
	 * A team's fields, each with how many tickets already hold a value for it.
	 *
	 * Two queries for the whole list, not one per field: the definitions, then one grouped
	 * count over `ticket_field_values`. The count is derived on every read and stored
	 * nowhere — a column would be a second copy that the first value written outside this
	 * service would make wrong.
	 *
	 * Reads are open, like `LabelController.forTeam` beside it: a field's name and type are
	 * not the work, and every screen that renders a ticket needs them to render a value.
	 */
	@Transactional(readOnly = true)
	fun list(teamId: UUID): List<FieldInUse> {
		val defined = fields.forTeam(teamId)
		val counts = fields.valueCounts(defined.map { it.id })
		return defined.map { FieldInUse(it, counts[it.id] ?: 0) }
	}

	@Transactional
	fun define(
		actor: User,
		teamId: UUID,
		name: String,
		type: String,
		required: Boolean,
		options: List<String>,
	): CustomField {
		// First, as every write in this package does.
		access.requireTeam(actor, teamId)
		if (teams.findById(teamId) == null) throw BadRequestException("No team $teamId")

		val parsed = parseType(type)
		val trimmed = requireName(name)
		// Pre-checked rather than left to `UNIQUE (team_id, name)`, so a second `Severity` is
		// the 409 it is instead of a 500 from the driver. The index is still the backstop.
		if (fields.findByName(teamId, trimmed) != null) {
			throw ConflictException(
				"${teams.findById(teamId)?.name ?: "That team"} already has a field called $trimmed",
			)
		}

		return fields.insert(
			CustomField(
				id = UUID.randomUUID(),
				teamId = teamId,
				name = trimmed,
				type = parsed,
				required = required,
				options = requireOptions(parsed, trimmed, options),
			),
		)
	}

	/**
	 * A rename, a change of mind about `required`, and a longer list of choices.
	 *
	 * Not the type — [CustomFieldRepository.update] carries the argument for why retyping is
	 * a delete and a re-create rather than a silent rewrite of everybody's values. Sending a
	 * different `type` is refused here rather than ignored: a settings screen that accepted
	 * the change and did not make it is worse than one that says no.
	 */
	@Transactional
	fun redefine(
		actor: User,
		fieldId: UUID,
		name: String,
		type: String?,
		required: Boolean,
		options: List<String>,
	): CustomField {
		val existing = require(actor, fieldId)
		if (type != null && parseType(type) != existing.type) {
			throw BadRequestException(
				"Field \"${existing.name}\" is a ${existing.type.wire} field and cannot become a $type;" +
					" delete it and define a new one, which also says out loud that its values are going",
			)
		}

		val trimmed = requireName(name)
		if (!trimmed.equals(existing.name, ignoreCase = false)) {
			fields.findByName(existing.teamId, trimmed)?.let {
				throw ConflictException("That team already has a field called $trimmed")
			}
		}

		val wanted = requireOptions(existing.type, trimmed, options)
		// Shrinking the list is refused while a ticket still holds the choice being removed.
		//
		// The alternative is to allow it and let those values sit outside their own
		// definition — which would make "every stored value satisfies its definition" true
		// only of writes, not of the table, and would leave a select rendering a value its
		// dropdown cannot offer. The refusal names the option and how many tickets hold it,
		// because "you cannot do that" without the number is a dead end.
		if (existing.type.hasOptions) {
			val stranded = fields.optionsInUse(fieldId) - wanted.toSet()
			if (stranded.isNotEmpty()) {
				throw ConflictException(
					"${stranded.joinToString { "\"$it\"" }} is still the value of \"${existing.name}\"" +
						" on some tickets; clear it there before removing the choice",
				)
			}
		}

		// `requireNotNull`: the row was read under this transaction and nothing between the
		// two statements can delete it, so a null here is a bug rather than a race.
		return requireNotNull(fields.update(fieldId, trimmed, required, wanted))
	}

	/** The values go with it — `V32` carries the argument for the cascade, and [list] the count. */
	@Transactional
	fun remove(actor: User, fieldId: UUID) {
		require(actor, fieldId)
		fields.delete(fieldId)
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * The field, and the right to change it — which is the right to write in its team.
	 *
	 * A 404 for a field that is not there, and the team's own refusal otherwise, so a member
	 * of another team learns nothing about what this team has defined beyond what the open
	 * read already tells them.
	 */
	private fun require(actor: User, fieldId: UUID): CustomField {
		val field = fields.findById(fieldId) ?: throw NotFoundException("No custom field $fieldId")
		access.requireTeam(actor, field.teamId)
		return field
	}

	private fun requireName(raw: String): String {
		val trimmed = raw.trim()
		if (trimmed.isEmpty()) throw BadRequestException("A field needs a name")
		// The same bound as `custom_fields_name_chk`, said here so it is a 400 naming the
		// limit rather than a 500 from the constraint.
		if (trimmed.length > NAME_LIMIT) {
			throw BadRequestException("A field's name is at most $NAME_LIMIT characters")
		}
		return trimmed
	}

	/** Caught here so an unknown type is the 400 it always was, not a 500 from the CHECK. */
	private fun parseType(raw: String): CustomFieldType = try {
		CustomFieldType.from(raw)
	} catch (unknown: IllegalArgumentException) {
		throw BadRequestException(unknown.message ?: "Invalid field type '$raw'")
	}

	/**
	 * The choices, cleaned and checked against the type — both directions, like
	 * `custom_fields_options_chk`.
	 *
	 * Blanks are dropped and repeats collapse, which is the half `V32` leaves to Kotlin
	 * because a CHECK cannot walk an array. Two identical choices in a dropdown is not an
	 * error worth a refusal, it is a paste accident with an obvious reading.
	 */
	private fun requireOptions(type: CustomFieldType, name: String, raw: List<String>): List<String> {
		val cleaned = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		if (type.hasOptions && cleaned.isEmpty()) {
			throw BadRequestException("Field \"$name\" is a ${type.wire} field, so it needs at least one choice")
		}
		if (!type.hasOptions && cleaned.isNotEmpty()) {
			throw BadRequestException(
				"Field \"$name\" is a ${type.wire} field, which has no choices;" +
					" only a ${CustomFieldType.SELECT.wire} field does",
			)
		}
		return cleaned
	}

	private companion object {
		/** `custom_fields_name_chk`, from the other side. */
		const val NAME_LIMIT = 60
	}
}

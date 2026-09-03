package dev.kanso.service

import dev.kanso.domain.CustomField
import dev.kanso.domain.CustomFieldType
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * The half of a typed custom field that a `CHECK` cannot be.
 *
 * `V32` narrows `ticket_field_values.value` to three scalar shapes and stops there, because
 * the comparison that matters — is this value the type its *definition* names — reads
 * `custom_fields.type`, which is another table. So this is where a jsonb column stops being
 * a bag: every write passes through [validate], and nothing else may write that column.
 *
 * An `object` rather than a `@Service`: it holds no state, touches no repository and is
 * worth calling from a test directly. The [ObjectMapper] is passed in for the two functions
 * that need one, the way `decodeObject` beside `doc_blocks.content` takes it.
 */
object FieldValueCodec {

	/**
	 * The value this field may hold, or a refusal that names the field and what it expected.
	 *
	 * Returns the *normalised* scalar rather than a boolean, so a caller cannot validate one
	 * value and store another: `String`, `BigDecimal` or `Boolean`, which are exactly the
	 * three shapes [encode] knows how to write and the three the CHECK accepts.
	 *
	 * Returns null for "clear this field", which is a value in the request and the absence of
	 * a row in the table. Two spellings reach it — an explicit JSON `null` and, for
	 * [CustomFieldType.TEXT], a blank string — and collapsing them here is deliberate: a
	 * stored `""` would be a second way to say "nothing", and `V32` refuses a JSON null in
	 * the column precisely so that "no value" has one spelling. A text box somebody emptied
	 * and a text box somebody never filled are the same fact.
	 *
	 * [CustomField.required] is enforced here and only here, which is what makes it mean "a
	 * value may not be taken away" rather than "a ticket without one is invalid" — the
	 * distinction `V32` argues at length, and the reason ticking the box does not break every
	 * script that files tickets.
	 */
	fun validate(field: CustomField, raw: Any?): Any? {
		val cleared = raw == null || (field.type == CustomFieldType.TEXT && raw is String && raw.isBlank())
		if (cleared) {
			// Named, so the refusal is actionable: "something was required" sends the caller
			// looking through a whole request body for which of five fields it was.
			if (field.required) {
				throw BadRequestException("Field \"${field.name}\" is required, so its value cannot be cleared")
			}
			return null
		}

		return when (field.type) {
			// Trimmed, because trailing whitespace in a field somebody filters or groups by
			// later is an invisible difference between two values that read identically.
			CustomFieldType.TEXT -> requireString(field, raw).trim()

			CustomFieldType.NUMBER -> requireNumber(field, raw)

			// No coercion from `"true"` or from `1`, and this is the whole point of the
			// exercise. A field that accepted either would be a text field with a checkbox
			// drawn on it, and the day something read it back expecting a boolean it would
			// find a string that no longer says anything about the type it was declared as.
			CustomFieldType.BOOLEAN -> raw as? Boolean
				?: throw wrongType(field, raw, "true or false")

			CustomFieldType.SELECT -> requireString(field, raw).let { chosen ->
				chosen.takeIf { it in field.options } ?: throw BadRequestException(
					"Field \"${field.name}\" expects one of ${field.options.joinToString()};" +
						" got ${describe(raw)}",
				)
			}
		}
	}

	/** The scalar as jsonb text. Only ever a value [validate] returned. */
	fun encode(json: ObjectMapper, value: Any): String = json.writeValueAsString(value)

	/**
	 * The scalar back off the column.
	 *
	 * A number is rebuilt from the column's own text rather than from whatever Jackson's
	 * untyped binding chose for it — that binding hands back an `Int` or a `Double`, and the
	 * `Double` would put a value through binary floating point on every read of a list. The
	 * text is what Postgres stored, so [BigDecimal] over it is exact and `3.50` stays `3.50`.
	 *
	 * No branch for a malformed document and no try/catch: the column is written only by
	 * [encode], and `ticket_field_values_value_chk` refuses anything that reaches the table
	 * another way. A throw here would be a schema that has already been broken by hand, and
	 * swallowing it would draw a list with a silently missing value.
	 */
	fun decode(json: ObjectMapper, raw: String): Any {
		val decoded = json.readValue(raw, Any::class.java)
		return if (decoded is Number) BigDecimal(raw.trim()) else decoded
	}

	private fun requireString(field: CustomField, raw: Any): String =
		raw as? String ?: throw wrongType(field, raw, "text")

	/**
	 * Every JSON number, normalised to one Kotlin type — and a `String` that looks like a
	 * number refused rather than parsed.
	 *
	 * `"3"` is the tempting one to accept, and accepting it is how the type stops being real:
	 * a client that sends its numbers as strings would keep working, so nothing would ever
	 * tell it that it disagrees with the definition, and the value it wrote would come back
	 * to the *next* client as a number it never sent.
	 */
	private fun requireNumber(field: CustomField, raw: Any): BigDecimal {
		val number = raw as? Number ?: throw wrongType(field, raw, "a number")
		// `NaN` and the infinities are not JSON numbers at all — Jackson would emit bare
		// `NaN`, which the driver would then hand a jsonb column as invalid JSON. Refused
		// here so the failure is a 400 naming the field instead of a 500 from Postgres.
		if (number is Double && !number.isFinite()) throw wrongType(field, raw, "a finite number")
		return BigDecimal(number.toString())
	}

	private fun wrongType(field: CustomField, raw: Any, expected: String) =
		BadRequestException("Field \"${field.name}\" expects $expected; got ${describe(raw)}")

	/**
	 * What arrived, said in words rather than printed.
	 *
	 * The value itself is deliberately not echoed for anything but a short string: a refusal
	 * is read in a log or a toast, and "got a string" is what tells somebody their client is
	 * sending the wrong shape. Naming the *type* is the actionable half.
	 */
	private fun describe(raw: Any): String = when (raw) {
		is String -> if (raw.length <= 40) "the string \"$raw\"" else "a string"
		is Number -> "the number $raw"
		is Boolean -> "$raw"
		is Collection<*>, is Array<*> -> "a list"
		is Map<*, *> -> "an object"
		else -> "a ${raw::class.simpleName}"
	}
}

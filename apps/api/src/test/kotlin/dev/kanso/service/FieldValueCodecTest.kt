package dev.kanso.service

import dev.kanso.domain.CustomField
import dev.kanso.domain.CustomFieldType
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of a typed field a `CHECK` cannot be, tested without a database because it needs
 * none: `FieldValueCodec` compares a value against a definition and touches nothing.
 *
 * What is pinned here is the property the whole feature rests on — **a value that disagrees
 * with its definition never gets as far as the column** — and the shape of the refusal,
 * because "400" is not an answer somebody can act on and "Severity expects a number" is.
 */
class FieldValueCodecTest {

	private fun field(
		type: CustomFieldType,
		required: Boolean = false,
		options: List<String> = emptyList(),
		name: String = "Severity",
	) = CustomField(
		id = UUID.randomUUID(),
		teamId = UUID.randomUUID(),
		name = name,
		type = type,
		required = required,
		options = options,
	)

	@Test
	fun `each type accepts its own scalar and normalises it`() {
		assertEquals("high", FieldValueCodec.validate(field(CustomFieldType.TEXT), "high"))
		assertEquals(true, FieldValueCodec.validate(field(CustomFieldType.BOOLEAN), true))
		assertEquals(
			BigDecimal("3"),
			FieldValueCodec.validate(field(CustomFieldType.NUMBER), 3),
			"every JSON number has to come out as one Kotlin type, or the wire shape depends on the client",
		)
		assertEquals(
			"high",
			FieldValueCodec.validate(field(CustomFieldType.SELECT, options = listOf("low", "high")), "high"),
		)
	}

	/**
	 * The table this feature exists to make impossible. Every pair below is a value that
	 * would have been accepted by a jsonb column with no opinion — `"3"` in a number field is
	 * the one that would have gone unnoticed longest, because it renders correctly.
	 */
	@Test
	fun `a value of the wrong shape is refused, and the refusal names the field and the type`() {
		val wrong = listOf(
			Triple(field(CustomFieldType.NUMBER), "3", "a number"),
			Triple(field(CustomFieldType.NUMBER), true, "a number"),
			Triple(field(CustomFieldType.BOOLEAN), "true", "true or false"),
			Triple(field(CustomFieldType.BOOLEAN), 1, "true or false"),
			Triple(field(CustomFieldType.TEXT), 3, "text"),
			Triple(field(CustomFieldType.TEXT), false, "text"),
			Triple(field(CustomFieldType.SELECT, options = listOf("low")), 3, "text"),
		)

		for ((definition, value, expected) in wrong) {
			val refused = assertFailsWith<BadRequestException>(
				"${definition.type.wire} accepted $value, so the type is decoration",
			) { FieldValueCodec.validate(definition, value) }

			val message = refused.message!!
			assertTrue(
				message.contains("\"Severity\""),
				"the refusal must name the field; got: $message",
			)
			assertTrue(
				message.contains(expected),
				"the refusal must say what was expected ($expected); got: $message",
			)
		}
	}

	@Test
	fun `a choice outside the options is refused, and the refusal lists them`() {
		val severity = field(CustomFieldType.SELECT, options = listOf("low", "high"))

		val refused = assertFailsWith<BadRequestException> { FieldValueCodec.validate(severity, "urgent") }

		assertTrue(refused.message!!.contains("low, high"), "got: ${refused.message}")
		assertTrue(refused.message!!.contains("\"Severity\""), "got: ${refused.message}")
	}

	/**
	 * Both spellings of "nothing" collapse to the same answer, which is what keeps the table
	 * from holding two of them — the argument `V35` makes for refusing a JSON null outright.
	 */
	@Test
	fun `null clears any field, and a blank string clears a text one`() {
		assertNull(FieldValueCodec.validate(field(CustomFieldType.TEXT), null))
		assertNull(FieldValueCodec.validate(field(CustomFieldType.NUMBER), null))
		assertNull(FieldValueCodec.validate(field(CustomFieldType.TEXT), "   "))
		assertNull(FieldValueCodec.validate(field(CustomFieldType.TEXT), ""))
	}

	/**
	 * A blank is *not* a clear for a select, because a select's blank would have to be one of
	 * its options to mean anything, and it never is. It falls through to the options check.
	 */
	@Test
	fun `a blank choice is not a clear, it is an invalid choice`() {
		val severity = field(CustomFieldType.SELECT, options = listOf("low"))

		assertFailsWith<BadRequestException> { FieldValueCodec.validate(severity, "  ") }
	}

	/**
	 * `required` bites here and nowhere else. That is the whole of the decision `V35` argues:
	 * a value may not be taken away, and a *ticket* is never refused for lacking one — which
	 * is why there is no test in this suite for a create refused by a required field.
	 */
	@Test
	fun `a required field refuses to be cleared, and says which field`() {
		val severity = field(CustomFieldType.TEXT, required = true)

		val refused = assertFailsWith<BadRequestException> { FieldValueCodec.validate(severity, null) }

		assertTrue(refused.message!!.contains("\"Severity\""), "got: ${refused.message}")
		assertTrue(refused.message!!.contains("required"), "got: ${refused.message}")
		// And the same refusal through the other spelling of nothing, or the rule would have a
		// hole shaped exactly like an emptied text box.
		assertFailsWith<BadRequestException> { FieldValueCodec.validate(severity, "") }
	}

	@Test
	fun `text is trimmed, so two values that read identically are identical`() {
		assertEquals("high", FieldValueCodec.validate(field(CustomFieldType.TEXT), "  high "))
	}

	/**
	 * Not JSON numbers at all. Refused here so the failure is a 400 naming the field rather
	 * than the driver handing Postgres a bare `NaN` and a jsonb column refusing it as a 500.
	 */
	@Test
	fun `an infinite number is refused rather than written as invalid json`() {
		val size = field(CustomFieldType.NUMBER, name = "Size")

		assertFailsWith<BadRequestException> { FieldValueCodec.validate(size, Double.NaN) }
		assertFailsWith<BadRequestException> { FieldValueCodec.validate(size, Double.POSITIVE_INFINITY) }
	}
}

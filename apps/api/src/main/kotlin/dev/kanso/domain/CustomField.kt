package dev.kanso.domain

import java.util.UUID

/**
 * What a custom field holds — the vocabulary `V35`'s `custom_fields_type_chk` closes, from
 * Kotlin's side.
 *
 * Four values, and the migration carries the argument for why it is four and what `date`,
 * `multi_select` and `user` are waiting on. Both halves are needed for the same reason
 * every other vocabulary here has both: the CHECK is the standing claim that no unknown
 * type was ever written, and this is what stops one being written in the first place — with
 * a message naming the legal values, which a constraint violation does not.
 */
enum class CustomFieldType(override val wire: String) : Wire {
	TEXT("text"),
	NUMBER("number"),
	BOOLEAN("boolean"),
	SELECT("select");

	/**
	 * Whether a definition of this type carries a list of choices.
	 *
	 * Derived from the type rather than stored beside it, so there is no second copy on disk
	 * to disagree with `custom_fields_options_chk` — which asserts exactly this equality on
	 * the other side of the wire.
	 */
	val hasOptions: Boolean get() = this == SELECT

	companion object {
		fun from(raw: String): CustomFieldType = parse(entries.toTypedArray(), raw)
	}
}

/**
 * A field, defined once for a team.
 *
 * [options] is empty for every type but [CustomFieldType.SELECT], and non-empty for that
 * one — the invariant `V35` states as a CHECK and [dev.kanso.service.CustomFieldService]
 * refuses before it gets there.
 */
data class CustomField(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val type: CustomFieldType,
	val required: Boolean,
	val options: List<String>,
)

/**
 * A definition plus how much work is already described by it.
 *
 * [valueCount] is computed on read — one grouped query for a team's whole list, never a
 * column — because it is a count of another table and a stored copy would be wrong from the
 * first value written outside the one path that maintained it.
 *
 * It exists for one screen and one gesture: deleting a definition cascades to its values, so
 * the confirmation has to be able to say *how many tickets are about to lose one*. That is
 * the same courtesy `DispositionCounts` pays before a team is emptied, and the reason the
 * cascade is offerable at all.
 */
data class FieldInUse(val field: CustomField, val valueCount: Int)

package dev.kanso.domain

import dev.kanso.service.BadRequestException
import java.text.Normalizer
import java.util.UUID

/**
 * One row of a team's catalogue of statuses — `KAN-28`.
 *
 * [key] is what `tickets.status` holds, what `tickets_status_fk` checks, and what every
 * saved view and filter addresses — so it is derived once, at creation, and never
 * written again. [label] is the word the team reads, and the only field a rename
 * touches. [category] is the fact everything that reasons about work reads instead of
 * the word: `DefaultStatus.category` says why that mapping is the definition, and for a
 * team-defined status this column is the only place it lives.
 */
data class TeamStatus(
	val teamId: UUID,
	val key: String,
	val label: String,
	val category: StatusCategory,
	val position: Int,
)

/**
 * The key a label produces, and the reason a client never sends one.
 *
 * Case is dropped and accents fold, so `In Progress`, `in progress` and `IN  PROGRESS`
 * are one key and the primary key refuses the second — two spellings of one word are the
 * same row rather than a rule an interface has to enforce, on every interface. A label
 * with nothing alphanumeric in it cannot produce a key and is refused here, rather than
 * stored under a key of bare underscores that a second such label would collide with.
 *
 * `\p{Mn}` is the Unicode category of a combining mark, which is what `NFD` decomposition
 * leaves an accent as: `é` becomes `e` plus one mark, and the mark is what this drops.
 */
fun statusKeyOf(label: String): String {
	val folded = Normalizer.normalize(label, Normalizer.Form.NFD)
		.replace(Regex("\\p{Mn}+"), "")
		.lowercase()
	val key = folded.replace(Regex("[^a-z0-9]+"), "_").trim('_')
	if (key.isEmpty()) throw BadRequestException("A status needs a letter or a digit in its name")
	return key
}

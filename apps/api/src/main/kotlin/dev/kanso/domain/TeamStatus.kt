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


/**
 * Where [status] lands in [destination]'s vocabulary — `KAN-90`.
 *
 * A ticket crossing into another team has to take that team's word for where it is:
 * `tickets_status_fk` is a composite key onto `(team_id, key)`, so a move that carried
 * the source team's status would be refused by the database, and `TicketService.patch`
 * validates against the destination, so it would answer 400 for a gesture `KAN-9` made
 * ordinary. So this is not a nicety; the only question is *which* status.
 *
 * Three steps, narrowest true answer first:
 *
 * 1. **The same key**, if the destination has it. This is the overwhelmingly common case
 *    — both teams were seeded with the six — and it is also what makes a *rename*
 *    invisible here, because a rename never moves a key.
 * 2. **The destination's first status of the same category**, by `position`. The meaning
 *    is what survives a boundary: a ticket in flight is still in flight, and dropping it
 *    back to a backlog would be the destination team inventing a decision nobody made.
 * 3. **The destination's first status**, when it cannot express that meaning at all.
 *    A last resort and deliberately not a refusal: failing the move would make an
 *    ordinary gesture depend on the destination team's own configuration, which is worse
 *    than a status a reader can see is wrong and correct in one click.
 *
 * Pure, and here rather than in the service, so the table of cases is assertable without
 * a database — the same argument `PrTransition` and `SubTicketService.progressOf` make.
 * [destination] must not be empty; a team keeps at least one status, and
 * `TeamStatusService.remove` is what guarantees it.
 */
fun rebase(status: String, destination: List<TeamStatus>, meant: StatusCategory): String {
	destination.firstOrNull { it.key == status }?.let { return it.key }
	destination.firstOrNull { it.category == meant }?.let { return it.key }
	return destination.first().key
}

/**
 * The word the Notion mirror writes for [status] — `KAN-91`.
 *
 * The team's, falling back to Kanso's own. `NotionProps.select` sends a name and Notion
 * invents the option if it has never seen it, which is what lets a renamed status reach a
 * database whose schema was created with the six: the select's options are a seed, not a
 * vocabulary.
 */
fun mirroredWord(status: String, catalogue: List<TeamStatus>): String =
	catalogue.firstOrNull { it.key == status }?.label
		?: DefaultStatus.entries.firstOrNull { it.wire == status }?.label
		?: status

/**
 * The status a word read back off a Notion page means, for the team that owns the page's
 * ticket — or `null` for one nobody uses.
 *
 * **Per team, and that is the whole design.** The mirror has one tickets database for the
 * instance, so its select carries every word every team uses — and two teams may rename
 * two *different* keys to the same word: `todo` to "En cours" in one, `in_progress` to
 * "En cours" in another. Resolved against a union, that word names two statuses and
 * choosing wrong writes a status nobody set. Resolved against the ticket's own team it
 * names exactly one, which is why `NotionPoller` looks the ticket up before it reads the
 * property.
 *
 * The team's word is tried first and Kanso's second, so a page edited before a rename
 * still reads — and a team that renamed nothing is unaffected either way.
 */
fun statusFromWord(word: String, catalogue: List<TeamStatus>): String? {
	val said = word.trim()
	catalogue.firstOrNull { it.label.equals(said, ignoreCase = true) }?.let { return it.key }
	// Kanso's own word, and only if the team still has the status it names — `KAN-90`.
	// Without that second half, a page still reading "Done" for a team that removed
	// `done` would resolve to a key `tickets_status_fk` refuses, and the poller would
	// fail every cycle on one stale page.
	val seeded = DefaultStatus.fromLabel(said)?.wire ?: return null
	return seeded.takeIf { key -> catalogue.any { it.key == key } }
}

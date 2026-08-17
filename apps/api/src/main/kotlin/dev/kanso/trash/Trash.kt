package dev.kanso.trash

import dev.kanso.domain.User
import dev.kanso.domain.Wire
import dev.kanso.service.BadRequestException
import java.time.OffsetDateTime
import java.util.UUID

/** Thirty days, then it is gone. The number the countdown counts down from. */
const val TRASH_RETENTION_DAYS = 30L

/**
 * What can be thrown away.
 *
 * Closed here and closed again by `V11`'s `CHECK`, so a fifth kind is a migration rather
 * than a string a service lets through. Only [TICKET] has a table behind it on this
 * branch: [DOC], [VIEW] and [FOLDER] name what slices B and C are building, and they are
 * declared now so that landing those tables adds a [TrashSource] bean rather than a
 * branch to [TrashService].
 */
enum class TrashKind(override val wire: String) : Wire {
	TICKET("ticket"),
	DOC("doc"),
	VIEW("view"),
	FOLDER("folder");

	companion object {
		fun from(raw: String): TrashKind = entries.firstOrNull { it.wire == raw }
			?: throw BadRequestException(
				"Unknown trash kind '$raw' (expected one of ${entries.joinToString { it.wire }})",
			)
	}
}

/**
 * Where a restore puts something back — **named**, not implied.
 *
 * The drawing's button reads "Restore into Product", and that is the whole point: a
 * restore that says only "restore" asks somebody to remember where a thing was before
 * they threw it away, which is exactly what they have stopped doing.
 */
data class TrashParent(val kind: String, val id: UUID?, val name: String)

/**
 * Something the deleted thing holds, and whether the delete reaches it.
 *
 * [BLOCKS] and [MENTIONED_TICKETS] are slice B's to fill from `doc_pages`; the drawing's
 * sentence about them is the load-bearing one — "les tickets n'ont pas été supprimés,
 * seul le renvoi disparaît" — and [TrashHolding.cascades] is what makes it a fact the
 * screen reads rather than a sentence somebody typed.
 */
enum class TrashHoldingKind(override val wire: String) : Wire {
	BLOCKS("blocks"),
	MENTIONED_TICKETS("mentionedTickets"),
	LINKED_DOCS("linkedDocs");
}

/** [cascades] is false when the thing goes and this stays. */
data class TrashHolding(val kind: TrashHoldingKind, val count: Int, val cascades: Boolean)

/**
 * One row of screen 26, in either tab.
 *
 * [deletedAt], [deletedBy] and [daysLeft] are null for an archive: archived is a
 * decision and has no clock on it, which is the distinction the whole slice turns on and
 * the reason these are nullable rather than a second type.
 */
data class TrashItem(
	val kind: TrashKind,
	val id: UUID,
	val label: String,
	val parent: TrashParent?,
	val holds: List<TrashHolding> = emptyList(),
	val deletedAt: OffsetDateTime? = null,
	val deletedBy: User? = null,
	val daysLeft: Int? = null,
)

/** Both tabs, with both counts, in one read: the drawing shows them side by side. */
data class TrashView(val trash: List<TrashItem>, val archives: List<TrashItem>)

/**
 * One kind of thing the trash can hold.
 *
 * The point of the interface is that [TrashService] never branches on a kind: it looks
 * the source up and delegates. Four kinds are one closed vocabulary of *rows*, not three
 * more code paths beside the ticket's.
 *
 * The division of labour is strict, and it is what keeps a source small: the **entry** is
 * [TrashService]'s (it writes and removes it, and it alone knows about the countdown),
 * the **entity** is the source's. A source that finds itself reading `trash_entries` is a
 * source doing the service's job.
 */
interface TrashSource {
	val kind: TrashKind

	/**
	 * Rows for the ids the trash names, without the deletion's own facts — the service
	 * fills those in from the entry. An id with no row behind it is **omitted**: `V11`
	 * carries no foreign key, so an entity another path destroyed outright leaves an
	 * entry pointing at nothing, and a blank row offering three exits is worse than no
	 * row at all.
	 */
	fun describe(ids: Collection<UUID>): List<TrashItem>

	/** The Archives tab. Never anything in the trash: the two tabs are disjoint. */
	fun archived(limit: Int): List<TrashItem>

	fun restore(actor: User, id: UUID)

	fun archiveInstead(actor: User, id: UUID)

	/** [actor] is null for the retention sweep, which answers to the clock, not a person. */
	fun purge(actor: User?, id: UUID)
}

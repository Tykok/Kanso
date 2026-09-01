package dev.kanso.favourites

import dev.kanso.domain.Wire
import dev.kanso.service.BadRequestException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.util.UUID

/**
 * What can be pinned to the top of a sidebar.
 *
 * Closed here and closed again by `V22`, which does it by having one nullable foreign key
 * per kind and a `CHECK` that exactly one is filled — so a fifth kind is a column and a
 * migration, not a string a service lets through. `V22`'s header argues why tickets and
 * doc folders are not on this list; the short of it is that a favourite is a place you go
 * back to, and neither of those is one.
 */
enum class FavouriteKind(override val wire: String) : Wire {
	TEAM("team"),
	PROJECT("project"),
	VIEW("view"),
	DOC("doc");

	companion object {
		fun from(raw: String): FavouriteKind = entries.firstOrNull { it.wire == raw }
			?: throw BadRequestException(
				"Unknown favourite kind '$raw' (expected one of ${entries.joinToString { it.wire }})",
			)
	}
}

/**
 * Exposed's view of `favourites`, in the slice's own package rather than at the end of
 * `db/Tables.kt` — the reason `docs/DocTables.kt` gives, and it is the same reason here:
 * several branches are cut from one commit and appending to that file is one conflict per
 * branch for a layout choice worth nothing to either reader.
 *
 * There is no `entity_type` column and no `entity_id`. The kind is [target]: which of the
 * four is set. Reading it costs this one function and buys a real cascade, which is the
 * whole trade `V22` is about.
 */
object Favourites : Table("favourites") {
	val id = javaUUID("id")
	val userId = javaUUID("user_id")
	val teamId = javaUUID("team_id").nullable()
	val projectId = javaUUID("project_id").nullable()
	val viewId = javaUUID("view_id").nullable()
	val docId = javaUUID("doc_id").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)

	/** The one column a favourite of [kind] fills, and the only place that mapping lives. */
	fun target(kind: FavouriteKind): Column<UUID?> = when (kind) {
		FavouriteKind.TEAM -> teamId
		FavouriteKind.PROJECT -> projectId
		FavouriteKind.VIEW -> viewId
		FavouriteKind.DOC -> docId
	}
}

/**
 * One row of the Favourites section, already rendered.
 *
 * No href. Which URL a kind is reached at is the client's business — nothing else in this
 * API sends one, and a route spelled in Kotlin is a route that goes stale the next time
 * `app/` moves.
 *
 * [archived] is a fact, not a decision. Whether an archived pin is drawn at all is the
 * sidebar's "Show archived" toggle's business — `useUi` state that never reaches the
 * server — and this is what lets the column answer it without a second request.
 */
data class FavouriteItem(
	val kind: FavouriteKind,
	val id: UUID,
	val label: String,
	val archived: Boolean = false,
)

/**
 * One kind of thing that can be pinned.
 *
 * The half of the trash's shape worth copying: [FavouriteService] never branches on a
 * kind, it looks the source up and delegates, so a fifth kind is a bean and a column
 * rather than a fifth arm of a `when` in three places.
 *
 * Thinner than [dev.kanso.trash.TrashSource] on purpose — a favourite has no restore, no
 * purge and no archive, only a label — which is why the four of them share one file.
 */
interface FavouriteSource {
	val kind: FavouriteKind

	/**
	 * Rows for the ids given, in no particular order — the service puts them back into the
	 * one the reader made them in.
	 *
	 * An id with nothing to draw is **omitted**, and "nothing to draw" means exactly one
	 * thing: *trashed*. A saved view or a document on the thirty-day countdown is dropped
	 * from the read while its row survives, so restoring it brings the pin back with it and
	 * the sweep that purges it takes the pin through `V22`'s cascade. Un-pinning on the way
	 * into the trash would spend somebody's decision on a mis-click they have thirty days
	 * to undo.
	 *
	 * *Archived* is not omission. It is a decision with no clock on it, and the sidebar's
	 * "Show archived" toggle already answers it — that toggle is `useUi` state and never
	 * reaches the server, so filtering here would be this endpoint guessing at a switch it
	 * cannot see. The row is sent with [FavouriteItem.archived] set and the column decides,
	 * which also makes flipping the toggle instant rather than a refetch.
	 *
	 * Deletion needs no source at all. That is the foreign key's job, and the reason `V22`
	 * has four of them.
	 */
	fun describe(ids: Collection<UUID>): List<FavouriteItem>
}

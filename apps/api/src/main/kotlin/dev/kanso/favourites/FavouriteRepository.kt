package dev.kanso.favourites

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A pin, as the table holds it: whose, what, and when it was made. */
data class FavouriteRow(
	val kind: FavouriteKind,
	val entityId: UUID,
	val createdAt: OffsetDateTime,
)

@Repository
class FavouriteRepository {

	/**
	 * One person's pins, oldest first — the order `V22` chose and the only one this table
	 * is ever read in.
	 *
	 * Tie-broken on `id`, because `created_at` defaults to `now()` and two pins made in one
	 * transaction share it to the microsecond. Without the tie-break the sidebar would
	 * reshuffle those two rows between reads for no reason a reader could see.
	 */
	fun findByUser(userId: UUID): List<FavouriteRow> =
		Favourites.selectAll()
			.where { Favourites.userId eq userId }
			.orderBy(Favourites.createdAt to SortOrder.ASC, Favourites.id to SortOrder.ASC)
			.mapNotNull { row ->
				// Which column is filled *is* the kind, so the row is read by asking each of
				// them in turn. `V22`'s CHECK guarantees exactly one answers; `mapNotNull`
				// covers the impossible case rather than throwing, since a sidebar that will
				// not draw is a worse failure than a sidebar missing a row.
				FavouriteKind.entries.firstNotNullOfOrNull { kind ->
					row[Favourites.target(kind)]?.let {
						FavouriteRow(kind, it, row[Favourites.createdAt])
					}
				}
			}

	/**
	 * Idempotent, and that is the point rather than a convenience: the gesture is a toggle
	 * and a second press arrives whenever two tabs are open on the same sidebar.
	 * `insertIgnore` is `ON CONFLICT DO NOTHING` against `favourites_<kind>_uniq`, so the
	 * duplicate costs a statement and changes nothing — the same trick `VoteService` uses
	 * for a second click on the same vote.
	 */
	fun add(userId: UUID, kind: FavouriteKind, entityId: UUID) {
		Favourites.insertIgnore {
			it[id] = UUID.randomUUID()
			it[Favourites.userId] = userId
			it[target(kind)] = entityId
			it[createdAt] = OffsetDateTime.now()
		}
	}

	/** True when a row went. False means it was not pinned, which is not an error either. */
	fun remove(userId: UUID, kind: FavouriteKind, entityId: UUID): Boolean =
		Favourites.deleteWhere {
			(Favourites.userId eq userId) and (Favourites.target(kind) eq entityId)
		} > 0
}

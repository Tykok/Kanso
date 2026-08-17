package dev.kanso.trash

import dev.kanso.db.TrashEntries
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A row here is a deletion. `entity_id` carries no foreign key — it points at four
 * different tables — so this repository never assumes the thing it names still exists;
 * that is [TrashSource.describe]'s problem, and it answers it by omission.
 */
data class TrashEntry(
	val kind: TrashKind,
	val entityId: UUID,
	val deletedAt: OffsetDateTime,
	val deletedById: UUID?,
) {
	/**
	 * Whole days remaining of [TRASH_RETENTION_DAYS], never below zero.
	 *
	 * Computed here and sent as a number rather than left to the client: this is the one
	 * clock, and a browser recomputing it from a timestamp would disagree with the sweep
	 * that actually empties the trash — by a whole day, for anyone far enough from UTC.
	 */
	fun daysLeft(now: OffsetDateTime): Int {
		val elapsed = java.time.Duration.between(deletedAt, now).toDays()
		return (TRASH_RETENTION_DAYS - elapsed).coerceAtLeast(0).toInt()
	}
}

@Repository
class TrashRepository {

	fun add(kind: TrashKind, entityId: UUID, deletedById: UUID?) {
		TrashEntries.insert {
			it[entityType] = kind.wire
			it[TrashEntries.entityId] = entityId
			it[deletedAt] = OffsetDateTime.now()
			it[deletedBy] = deletedById
		}
	}

	/**
	 * Bypasses [TrashKind] on purpose, and exists for exactly one test: the closed
	 * vocabulary has to be refused by the database, not only by a Kotlin enum that a raw
	 * insert from some future caller would walk straight past.
	 */
	fun addRaw(rawKind: String, entityId: UUID, deletedById: UUID?) {
		TrashEntries.insert {
			it[entityType] = rawKind
			it[TrashEntries.entityId] = entityId
			it[deletedAt] = OffsetDateTime.now()
			it[deletedBy] = deletedById
		}
	}

	fun find(kind: TrashKind, entityId: UUID): TrashEntry? =
		TrashEntries.selectAll()
			.where { (TrashEntries.entityType eq kind.wire) and (TrashEntries.entityId eq entityId) }
			.singleOrNull()
			?.let {
				TrashEntry(
					kind = kind,
					entityId = it[TrashEntries.entityId],
					deletedAt = it[TrashEntries.deletedAt],
					deletedById = it[TrashEntries.deletedBy],
				)
			}

	/** Newest first: whoever opens the trash is usually looking for what they just lost. */
	fun all(): List<TrashEntry> =
		TrashEntries.selectAll()
			.orderBy(TrashEntries.deletedAt to SortOrder.DESC)
			.map {
				TrashEntry(
					kind = TrashKind.from(it[TrashEntries.entityType]),
					entityId = it[TrashEntries.entityId],
					deletedAt = it[TrashEntries.deletedAt],
					deletedById = it[TrashEntries.deletedBy],
				)
			}

	/** The ids of one kind, for the `NOT IN` that keeps the trash out of a live read. */
	fun idsOf(kind: TrashKind): List<UUID> =
		TrashEntries.select(TrashEntries.entityId)
			.where { TrashEntries.entityType eq kind.wire }
			.map { it[TrashEntries.entityId] }

	fun expired(now: OffsetDateTime): List<TrashEntry> =
		all().filter { it.daysLeft(now) == 0 }

	fun remove(kind: TrashKind, entityId: UUID): Boolean =
		TrashEntries.deleteWhere {
			(entityType eq kind.wire) and (TrashEntries.entityId eq entityId)
		} > 0

	/**
	 * Forgets deletions whose thing another path destroyed outright.
	 *
	 * The foreign key `entity_id` cannot have — it points at four tables — paid by hand, and
	 * the only caller is a disposition: those paths destroy under their own consent model, a
	 * retyped team name rather than a countdown, so a row here that survived them would be a
	 * clock still running on something already gone. [remove] is the *entry's* exit and this
	 * is the *entity's*; they are one statement apart and worth two names, because a source
	 * calling this one would be losing a countdown it has no business ending.
	 *
	 * Returns how many it forgot, so a caller can log a number rather than a reassurance.
	 */
	fun forget(kind: TrashKind, entityIds: Collection<UUID>): Int =
		if (entityIds.isEmpty()) 0
		else TrashEntries.deleteWhere {
			(entityType eq kind.wire) and (TrashEntries.entityId inList entityIds)
		}

	/**
	 * Moves an entry's clock back, so a test can be two days old without sleeping for
	 * two days. Nothing in the application calls it, and it lives here rather than as raw
	 * SQL in a test so that the countdown is exercised through the same column the sweep
	 * reads.
	 */
	fun backdate(kind: TrashKind, entityId: UUID, at: OffsetDateTime) {
		TrashEntries.update({
			(TrashEntries.entityType eq kind.wire) and (TrashEntries.entityId eq entityId)
		}) { it[deletedAt] = at }
	}
}

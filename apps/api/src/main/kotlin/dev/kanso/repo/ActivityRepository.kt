package dev.kanso.repo

import dev.kanso.db.Activity
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A row as it is stored: the actor is an id here, and a person one layer up. */
data class ActivityRecord(
	val id: UUID,
	val entity: ActivityEntity,
	val entityId: UUID,
	val actorId: UUID?,
	val kind: ActivityKind,
	/** The `jsonb` document, still text. [dev.kanso.service.ActivityService] decodes it. */
	val payload: String,
	val createdAt: OffsetDateTime,
)

@Repository
class ActivityRepository(private val jdbc: JdbcClient) {

	/**
	 * Raw, and only because of the column type: `payload` is `jsonb`, and the driver sends
	 * a Kotlin string as `varchar`, which Postgres refuses for a jsonb column.
	 * `outbound_jobs` casts the same way, for the same reason. It runs on the connection
	 * Spring already holds, inside the caller's transaction.
	 *
	 * [createdAt] is passed in rather than left to the column default: `now()` is the
	 * *transaction* timestamp, so every row one transaction writes would carry the same
	 * instant, and a newest-first feed would then order a create and the edit that
	 * followed it arbitrarily.
	 */
	fun insert(
		id: UUID,
		entity: ActivityEntity,
		entityId: UUID,
		actorId: UUID?,
		kind: ActivityKind,
		payload: String,
		createdAt: OffsetDateTime,
		/**
		 * Which application typed it, or null when a person did — the public `client_id`,
		 * which is what `V19__activity_via_client_id_target.sql` moved the foreign key onto
		 * so that the value a service layer actually holds could be written down.
		 */
		viaClientId: String? = null,
	) {
		jdbc.sql(
			"""
			INSERT INTO activity (id, entity_type, entity_id, actor_id, kind, payload, created_at, via_client_id)
			VALUES (:id, :entityType, :entityId, :actorId, :kind, CAST(:payload AS jsonb), :createdAt, :viaClientId)
			""".trimIndent()
		)
			.param("id", id)
			.param("entityType", entity.wire)
			.param("entityId", entityId)
			.param("actorId", actorId)
			.param("kind", kind.wire)
			.param("payload", payload)
			.param("createdAt", createdAt)
			.param("viaClientId", viaClientId)
			.update()
	}

	/**
	 * Newest first, which is the order `activity_entity_idx` is stored in and the only
	 * order a feed is ever drawn in.
	 */
	fun forEntity(entity: ActivityEntity, entityId: UUID, limit: Int): List<ActivityRecord> =
		Activity.selectAll()
			.where { (Activity.entityType eq entity.wire) and (Activity.entityId eq entityId) }
			.orderBy(Activity.createdAt to SortOrder.DESC)
			.limit(limit)
			.map { it.toRecord() }

	/**
	 * When each of these tickets **first** reached any of [toStatuses], if it ever did.
	 *
	 * The one datum KAN-23 needs, and the reason that ticket said "a query, not a new
	 * datum": `activity` has timestamped every `status_changed` since V8, so when work
	 * started on a ticket is already on disk and nothing has to be collected or stored.
	 *
	 * `MIN` rather than the newest, and the difference is the whole rule: a ticket parked
	 * back in `todo` and picked up again keeps the instant somebody *first* started it. The
	 * clock starts once and never restarts, so a ticket bounced four times reports the whole
	 * saga rather than the twenty minutes of its final touch. Cycle time and the age of work
	 * in flight both read this, so neither can flatter the other.
	 *
	 * [toStatuses] is the caller's, spelled in wire values, because which statuses mean
	 * "started" is a property of the vocabulary and not of this query — `StatusCategory`
	 * owns it, so a seventh started status is read here without this file being edited.
	 * Filtered in Postgres rather than by decoding every payload in Kotlin: the answer is
	 * one row per ticket, and shipping the whole history to throw most of it away is how a
	 * page over six closed cycles becomes a page that reads a table.
	 *
	 * A second raw statement, and for a different reason than [insert]'s: `payload` is
	 * `jsonb` and `->>` is how you look inside one. Exposed has no operator for it that
	 * would read better than the SQL.
	 */
	fun firstEnteredAt(
		ticketIds: Collection<UUID>,
		toStatuses: Collection<String>,
	): Map<UUID, OffsetDateTime> {
		// An empty `IN ()` is a syntax error in Postgres, and both lists are empty in
		// ordinary cases: a person with nothing in flight, a team whose closed cycles
		// delivered nothing.
		if (ticketIds.isEmpty() || toStatuses.isEmpty()) return emptyMap()

		return jdbc.sql(
			"""
			SELECT entity_id, MIN(created_at) AS started_at
			FROM activity
			WHERE entity_type = :entityType
			  AND kind = :kind
			  AND entity_id IN (:ticketIds)
			  AND payload ->> 'to' IN (:toStatuses)
			GROUP BY entity_id
			""".trimIndent()
		)
			.param("entityType", ActivityEntity.TICKET.wire)
			.param("kind", ActivityKind.STATUS_CHANGED.wire)
			.param("ticketIds", ticketIds)
			.param("toStatuses", toStatuses)
			.query { rs, _ ->
				rs.getObject("entity_id", UUID::class.java) to
					rs.getObject("started_at", OffsetDateTime::class.java)
			}
			.list()
			.toMap()
	}

	private fun ResultRow.toRecord() = ActivityRecord(
		id = this[Activity.id],
		entity = ActivityEntity.from(this[Activity.entityType]),
		entityId = this[Activity.entityId],
		actorId = this[Activity.actorId],
		kind = ActivityKind.from(this[Activity.kind]),
		payload = this[Activity.payload],
		createdAt = this[Activity.createdAt],
	)
}

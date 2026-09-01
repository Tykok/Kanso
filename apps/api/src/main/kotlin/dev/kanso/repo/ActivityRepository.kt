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
	 * The one raw statement in this file, and only because of the column type: `payload`
	 * is `jsonb`, and the driver sends a Kotlin string as `varchar`, which Postgres
	 * refuses for a jsonb column. `sync_jobs` casts the same way, for the same reason.
	 * It runs on the connection Spring already holds, inside the caller's transaction.
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

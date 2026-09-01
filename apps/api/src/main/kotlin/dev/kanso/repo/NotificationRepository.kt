package dev.kanso.repo

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/** The name a row prints in place of an actor, and nothing more of the user. */
data class NotificationActor(val id: UUID, val displayName: String)

/**
 * One inbox row, resolved.
 *
 * The sentence is *not* here: the server answers with the kind, the actor, the thing
 * and whatever numbers the kind needs, and the client composes the English. Copy
 * belongs beside the rest of the interface's copy, not in a repository.
 */
data class NotificationRow(
	/**
	 * Null for a row nobody stored — the failures tab is read straight off the
	 * outbox, so those rows have no `notifications.id` and cannot be marked read.
	 */
	val id: UUID?,
	val kind: String,
	val entityType: String,
	val entityId: UUID,
	/** `KAN-142`. Null for anything with no per-team number: a project, a document. */
	val reference: String?,
	/** The thing's own name. Null once the thing itself has been deleted. */
	val subject: String?,
	val actor: NotificationActor?,
	/** Whatever the kind needs and no join can recover: a slip in days, two titles. */
	val payload: Map<String, Any?>,
	val readAt: OffsetDateTime?,
	val createdAt: OffsetDateTime,
)

/**
 * Raw SQL throughout, following `OutboundJobRepository`.
 *
 * Two things put this outside the Exposed DSL. `payload` is jsonb, which has to be
 * cast explicitly on the way in and read as `::text` on the way out; and every read
 * resolves the row's entity through a `CASE` over three left joins, because
 * `entity_id` is polymorphic and Exposed has no way to say "join whichever table
 * this column names".
 */
@Repository
class NotificationRepository(
	private val jdbc: JdbcClient,
	private val objectMapper: ObjectMapper,
) {

	private val mapper = RowMapper { rs, _ ->
		NotificationRow(
			id = rs.uuid("id"),
			kind = rs.text("kind"),
			entityType = rs.text("entity_type"),
			entityId = rs.uuid("entity_id"),
			reference = rs.getString("reference"),
			subject = rs.getString("subject"),
			actor = rs.uuidOrNull("actor_id")?.let { NotificationActor(it, rs.text("actor_name")) },
			payload = readPayload(rs.getString("payload")),
			readAt = rs.timestampOrNull("read_at"),
			createdAt = rs.timestamp("created_at"),
		)
	}

	/**
	 * A payload that cannot be parsed yields an empty map rather than failing the
	 * read: the inbox is where someone goes when something has already gone wrong,
	 * and one malformed row must not be what stops them seeing the rest.
	 */
	private fun readPayload(raw: String?): Map<String, Any?> {
		if (raw.isNullOrBlank()) return emptyMap()
		@Suppress("UNCHECKED_CAST")
		return runCatching { objectMapper.readValue(raw, Map::class.java) as Map<String, Any?> }
			.getOrDefault(emptyMap())
	}

	/**
	 * One row per recipient, inside the caller's transaction — the same rule the
	 * outbox and the activity log follow, and for the same reason: a notification
	 * about a change that rolled back is a lie the reader cannot check.
	 */
	fun insert(
		userId: UUID,
		kind: String,
		entityType: String,
		entityId: UUID,
		actorId: UUID?,
		payload: Map<String, Any?>,
	) {
		jdbc.sql(
			"""
			INSERT INTO notifications (user_id, kind, entity_type, entity_id, actor_id, payload)
			VALUES (:userId, :kind, :entityType, :entityId, :actorId, CAST(:payload AS jsonb))
			""".trimIndent()
		)
			.param("userId", userId)
			.param("kind", kind)
			.param("entityType", entityType)
			.param("entityId", entityId)
			.param("actorId", actorId)
			.param("payload", objectMapper.writeValueAsString(payload))
			.update()
	}

	/**
	 * [kinds] empty means every kind. Passed as one comma-separated parameter rather
	 * than as an interpolated `IN (…)`: the list is short, and an array built in SQL
	 * keeps the statement a single prepared shape whatever tab is open.
	 */
	fun forUser(userId: UUID, kinds: Collection<String>, limit: Int): List<NotificationRow> = jdbc.sql(
		"""
		SELECT n.id, n.kind, n.entity_type, n.entity_id, n.actor_id,
		       n.payload::text AS payload, n.read_at, n.created_at,
		       a.display_name AS actor_name,
		       $REFERENCE_SQL AS reference,
		       $SUBJECT_SQL AS subject
		  FROM notifications n
		  LEFT JOIN users       a  ON a.id = n.actor_id
		  $ENTITY_JOINS
		 WHERE n.user_id = :userId
		   AND (:kinds = '' OR n.kind = ANY(string_to_array(:kinds, ',')))
		 ORDER BY n.created_at DESC, n.id DESC
		 LIMIT :limit
		""".trimIndent()
	)
		.param("userId", userId)
		.param("kinds", kinds.joinToString(","))
		.param("limit", limit)
		.query(mapper)
		.list()

	fun countsByKind(userId: UUID): Map<String, Long> = jdbc.sql(
		"SELECT kind, count(*) AS total FROM notifications WHERE user_id = :userId GROUP BY kind"
	).param("userId", userId).query { rs, _ -> rs.text("kind") to rs.getLong("total") }.list().toMap()

	fun unreadCount(userId: UUID): Long = jdbc.sql(
		"SELECT count(*) AS total FROM notifications WHERE user_id = :userId AND read_at IS NULL"
	).param("userId", userId).query { rs, _ -> rs.getLong("total") }.single()

	fun markAllRead(userId: UUID): Int = jdbc.sql(
		"UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL"
	).param("userId", userId).update()

	/**
	 * Scoped to the owner in the statement, not checked first and then updated: a
	 * notification is the one kind of row whose very existence is private, so
	 * "someone else's id" and "no such id" have to be the same answer.
	 */
	fun markRead(userId: UUID, id: UUID): Int = jdbc.sql(
		"UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :userId AND read_at IS NULL"
	).param("id", id).param("userId", userId).update()

	/**
	 * Whether this exact disagreement is already in somebody's inbox.
	 *
	 * Not scoped to a user: a conflict is recorded for a set of recipients in one go, so
	 * "already told" is a fact about the entity, the field and the value that was discarded
	 * — which is also the key that makes it change. A different value in Notion is a new
	 * conflict; the same one seen again by the poller is not.
	 *
	 * `payload->>` rather than a column, and unindexed: this runs once per poll per page
	 * that lost, against a table already narrowed by `entity_id`.
	 */
	fun conflictExists(entityId: UUID, field: String, theirs: String): Boolean = jdbc.sql(
		"""
		SELECT 1 FROM notifications
		 WHERE kind = 'conflict'
		   AND entity_id = :entityId
		   AND payload->>'field' = :field
		   AND payload->>'theirs' = :theirs
		 LIMIT 1
		""".trimIndent()
	)
		.param("entityId", entityId)
		.param("field", field)
		.param("theirs", theirs)
		.query { _, _ -> true }
		.optional()
		.orElse(false)

	fun exists(userId: UUID, id: UUID): Boolean = jdbc.sql(
		"SELECT 1 FROM notifications WHERE id = :id AND user_id = :userId"
	).param("id", id).param("userId", userId).query { _, _ -> true }.optional().orElse(false)

	// --- the failures tab, read off the outbox --------------------------------

	/**
	 * Refused pushes, as inbox rows.
	 *
	 * Not stored beside the other kinds on purpose. `outbound_jobs` is already where "the
	 * far side refused this write" is true, and a notification copying it would go on
	 * claiming a push had failed after the queue was retried and the push succeeded.
	 * Nobody has to remember to delete anything: the row exists exactly while the job
	 * is failed.
	 *
	 * Every destination, unlike the admin screen, which is one destination's own. The
	 * inbox's question is "what has not left the building", and a change stuck on its
	 * way to somewhere else is still stuck. `destination` rides along so the row can
	 * name who refused instead of assuming Notion.
	 *
	 * `entity_type` here is the queue's vocabulary, which includes `team` — one value
	 * wider than `notifications`' own `CHECK`. That is legal because these rows are
	 * never written to that table. It is also the axis that survives generalising the
	 * queue: whoever refused the write, the row is still named after the ticket it was
	 * about, which is why the discriminator is two columns and this stays a join.
	 */
	fun failedPushes(limit: Int): List<FailedPush> = jdbc.sql(
		"""
		SELECT j.id, j.destination, j.entity_type, j.entity_id, j.attempts, j.last_error, j.updated_at,
		       CASE WHEN j.entity_type = 'ticket' THEN tm.key || '-' || t.number END AS reference,
		       COALESCE(t.title, p.name, d.title, jt.name) AS subject
		  FROM outbound_jobs j
		  LEFT JOIN tickets     t  ON j.entity_type = 'ticket'  AND t.id = j.entity_id
		  LEFT JOIN teams       tm ON tm.id = t.team_id
		  LEFT JOIN projects    p  ON j.entity_type = 'project' AND p.id = j.entity_id
		  LEFT JOIN notion_docs d  ON j.entity_type = 'doc'     AND d.id = j.entity_id
		  LEFT JOIN teams       jt ON j.entity_type = 'team'    AND jt.id = j.entity_id
		 WHERE j.status = 'failed'
		 ORDER BY j.updated_at DESC, j.id DESC
		 LIMIT :limit
		""".trimIndent()
	).param("limit", limit).query { rs, _ ->
		FailedPush(
			jobId = rs.getLong("id"),
			destination = rs.text("destination"),
			entityType = rs.text("entity_type"),
			entityId = rs.uuid("entity_id"),
			attempts = rs.getInt("attempts"),
			error = rs.getString("last_error"),
			reference = rs.getString("reference"),
			subject = rs.getString("subject"),
			failedAt = rs.timestamp("updated_at"),
		)
	}.list()

	fun failedPushCount(): Long = jdbc.sql(
		"SELECT count(*) AS total FROM outbound_jobs WHERE status = 'failed'"
	).query { rs, _ -> rs.getLong("total") }.single()

	private companion object {
		const val REFERENCE_SQL =
			"CASE WHEN n.entity_type = 'ticket' THEN tm.key || '-' || t.number END"

		const val SUBJECT_SQL = "COALESCE(t.title, p.name, d.title)"

		val ENTITY_JOINS = """
			LEFT JOIN tickets     t  ON n.entity_type = 'ticket'  AND t.id = n.entity_id
			LEFT JOIN teams       tm ON tm.id = t.team_id
			LEFT JOIN projects    p  ON n.entity_type = 'project' AND p.id = n.entity_id
			LEFT JOIN notion_docs d  ON n.entity_type = 'doc'     AND d.id = n.entity_id
		""".trimIndent()
	}
}

/** A job the queue gave up on, with enough of its entity to name it on screen. */
data class FailedPush(
	val jobId: Long,
	val destination: String,
	val entityType: String,
	val entityId: UUID,
	val attempts: Int,
	val error: String?,
	val reference: String?,
	val subject: String?,
	val failedAt: OffsetDateTime,
)

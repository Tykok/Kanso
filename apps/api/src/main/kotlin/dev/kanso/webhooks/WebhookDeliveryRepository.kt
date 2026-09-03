package dev.kanso.webhooks

import dev.kanso.outbox.OutboundEntityType
import dev.kanso.repo.text
import dev.kanso.repo.timestamp
import dev.kanso.repo.timestampOrNull
import dev.kanso.repo.uuid
import dev.kanso.repo.uuidOrNull
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * The journal, and the ledger the retry reads. `V31` argues why those are one table.
 *
 * Raw SQL rather than Exposed, like `OutboundJobRepository` next door and for the same
 * kind of reason: every write here is either an increment (`attempts = attempts + 1`) or a
 * read of a partial unique index, and this table's whole job is to be the honest record of
 * what the queue beside it did. Keeping the two in the same idiom means a person debugging
 * a wedged delivery reads one dialect, not two.
 */
@Repository
class WebhookDeliveryRepository(private val jdbc: JdbcClient) {

	private val mapper = RowMapper { rs, _ ->
		WebhookDelivery(
			id = rs.uuid("id"),
			subscriptionId = rs.uuid("subscription_id"),
			jobId = rs.getLong("job_id").takeIf { !rs.wasNull() },
			entityType = OutboundEntityType.from(rs.text("entity_type")),
			entityId = rs.uuid("entity_id"),
			payload = rs.text("payload"),
			status = DeliveryStatus.from(rs.text("status")),
			attempts = rs.getInt("attempts"),
			responseStatus = rs.getInt("response_status").takeIf { !rs.wasNull() },
			error = rs.getString("error"),
			replayOf = rs.uuidOrNull("replay_of"),
			createdAt = rs.timestamp("created_at"),
			deliveredAt = rs.timestampOrNull("delivered_at"),
		)
	}

	private val columns =
		"id, subscription_id, job_id, entity_type, entity_id, payload, status, attempts, " +
			"response_status, error, replay_of, created_at, delivered_at"

	/**
	 * Everything already written down for this job — the ledger read, once per drain pass.
	 *
	 * Both kinds of row, deliberately. The automatic ones say which subscriptions this job
	 * has already satisfied, so a retry can skip them; the replay ones say which
	 * subscriptions somebody asked to be sent again, which the fan-out would not have
	 * produced. `WebhookOutboundHandler` unions the two, and `V31` says why that union is
	 * additive rather than a choice between them.
	 */
	fun forJob(jobId: Long): List<WebhookDelivery> = jdbc
		.sql("SELECT $columns FROM webhook_deliveries WHERE job_id = :jobId")
		.param("jobId", jobId)
		.query(mapper)
		.list()

	/**
	 * A row before the POST, never after it.
	 *
	 * The order matters and is the point of a separate method: a delivery written after the
	 * request would be a journal that loses exactly the requests worth investigating — the
	 * ones during which the process died. Written first, a crashed pass leaves a 'pending'
	 * row with zero attempts, which reads as "we were about to call you and cannot prove we
	 * did not".
	 */
	fun open(
		subscriptionId: UUID,
		jobId: Long?,
		entityType: OutboundEntityType,
		entityId: UUID,
		payload: String,
		replayOf: UUID? = null,
		requestedBy: UUID? = null,
	): UUID {
		val id = UUID.randomUUID()
		jdbc.sql(
			"""
			INSERT INTO webhook_deliveries
			  (id, subscription_id, job_id, entity_type, entity_id, payload, status, replay_of, requested_by)
			VALUES (:id, :subscription, :jobId, :type, :entityId, :payload, 'pending', :replayOf, :requestedBy)
			""".trimIndent()
		)
			.param("id", id)
			.param("subscription", subscriptionId)
			.param("jobId", jobId)
			.param("type", entityType.wire)
			.param("entityId", entityId)
			.param("payload", payload)
			.param("replayOf", replayOf)
			.param("requestedBy", requestedBy)
			.update()
		return id
	}

	/**
	 * A 2xx. `delivered_at` is stamped here and only here, so "delivered, and when" is one
	 * column that cannot disagree with the status beside it.
	 *
	 * `error` is cleared, because a row that succeeded on its third attempt should not still
	 * be showing the second attempt's sentence — the attempt count is what records that it
	 * took three.
	 */
	fun markDelivered(id: UUID, responseStatus: Int) {
		jdbc.sql(
			"""
			UPDATE webhook_deliveries
			   SET status = 'delivered', attempts = attempts + 1, response_status = :status,
			       error = NULL, delivered_at = now()
			 WHERE id = :id
			""".trimIndent()
		).param("id", id).param("status", responseStatus).update()
	}

	/**
	 * A refusal, a timeout or a connection that never opened.
	 *
	 * [responseStatus] is null for the last two and that null is load-bearing — `V31`: "two
	 * different facts and the null tells them apart". A 500 is an endpoint that is there and
	 * unhappy; nothing at all is an endpoint that is not there, and a configurator chasing
	 * the second should not be reading HTTP semantics into it.
	 */
	fun markFailed(id: UUID, responseStatus: Int?, error: String) {
		jdbc.sql(
			"""
			UPDATE webhook_deliveries
			   SET status = 'failed', attempts = attempts + 1, response_status = :status,
			       error = :error, delivered_at = NULL
			 WHERE id = :id
			""".trimIndent()
		)
			.param("id", id)
			.param("status", responseStatus)
			.param("error", error.take(2000))
			.update()
	}

	/** The journal screen's read, in `webhook_deliveries_recent_idx`'s own order. */
	fun recent(subscriptionId: UUID, limit: Int = 50): List<WebhookDelivery> = jdbc
		.sql(
			"""
			SELECT $columns FROM webhook_deliveries
			 WHERE subscription_id = :subscription
			 ORDER BY created_at DESC, id DESC LIMIT :limit
			""".trimIndent()
		)
		.param("subscription", subscriptionId)
		.param("limit", limit)
		.query(mapper)
		.list()

	fun findById(id: UUID): WebhookDelivery? = jdbc
		.sql("SELECT $columns FROM webhook_deliveries WHERE id = :id")
		.param("id", id)
		.query(mapper)
		.optional()
		.orElse(null)
}

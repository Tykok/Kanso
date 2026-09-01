package dev.kanso.repo

import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundJob
import dev.kanso.outbox.OutboundOperation
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * The outbox. Every statement here is raw SQL on purpose: coalescing onto a
 * partial unique index and claiming with `FOR UPDATE SKIP LOCKED` are both
 * outside what the Exposed DSL can express, and both are load-bearing.
 *
 * The destination appears in every statement that reasons about "another job for the
 * same thing". Left out of any one of them, a push queued for one destination would
 * be treated as superseding a push queued for another, and the change would silently
 * never leave — see `V26`.
 */
@Repository
class OutboundJobRepository(private val jdbc: JdbcClient) {

	private val mapper = RowMapper { rs, _ ->
		OutboundJob(
			id = rs.getLong("id"),
			destination = Destination.from(rs.text("destination")),
			entityType = OutboundEntityType.from(rs.text("entity_type")),
			entityId = rs.uuid("entity_id"),
			operation = OutboundOperation.from(rs.text("operation")),
			attempts = rs.getInt("attempts"),
			priority = rs.getInt("priority"),
			payload = rs.getString("payload"),
			lastError = rs.getString("last_error"),
		)
	}

	/**
	 * Called inside the business transaction, so the job cannot be lost if the
	 * process dies right after the commit.
	 *
	 * A push always writes the entity's full current state, which makes two
	 * queued pushes for the same row redundant: the insert coalesces onto the
	 * existing pending job instead of stacking. Attempts reset, because a fresh
	 * change deserves a fresh try rather than inheriting an old backoff.
	 *
	 * [destination] is first and has no default. A default would be the Notion-shaped
	 * queue all over again — the second consumer's author would never have to notice
	 * the axis existed — and the call site is exactly where it will be obvious that
	 * one change now has to reach two places.
	 */
	fun enqueue(
		destination: Destination,
		entityType: OutboundEntityType,
		entityId: UUID,
		operation: OutboundOperation,
		payload: String? = null,
	) {
		jdbc.sql(
			"""
			INSERT INTO outbound_jobs (destination, entity_type, entity_id, operation, status, priority, next_attempt_at, payload)
			VALUES (:destination, :type, :id, :op, 'pending', :priority, now(), CAST(:payload AS jsonb))
			ON CONFLICT (destination, entity_type, entity_id) WHERE status = 'pending'
			DO UPDATE SET operation       = EXCLUDED.operation,
			              priority        = LEAST(outbound_jobs.priority, EXCLUDED.priority),
			              next_attempt_at = LEAST(outbound_jobs.next_attempt_at, EXCLUDED.next_attempt_at),
			              payload         = EXCLUDED.payload,
			              attempts        = 0,
			              last_error      = NULL
			""".trimIndent()
		)
			.param("destination", destination.wire)
			.param("type", entityType.wire)
			.param("id", entityId)
			.param("op", operation.wire)
			.param("priority", entityType.priority)
			.param("payload", payload)
			.update()
	}

	/**
	 * `SKIP LOCKED` is what makes several workers (or several API instances) safe
	 * to run at once: each claims a disjoint set instead of blocking on the same
	 * head row. Flipping to 'running' also frees the pending slot, so an edit made
	 * while a push is in flight still gets queued.
	 *
	 * One destination at a time, because a handler drains its own: a batch mixing
	 * destinations would spend one remote's budget on another's backlog.
	 */
	fun claimBatch(destination: Destination, limit: Int, workerId: String): List<OutboundJob> = jdbc.sql(
		"""
		WITH ready AS (
		    SELECT id FROM outbound_jobs
		     WHERE destination = :destination AND status = 'pending' AND next_attempt_at <= now()
		     ORDER BY priority, next_attempt_at, id
		     LIMIT :limit
		     FOR UPDATE SKIP LOCKED
		)
		UPDATE outbound_jobs j
		   SET status = 'running', locked_at = now(), locked_by = :worker, attempts = j.attempts + 1
		  FROM ready
		 WHERE j.id = ready.id
		RETURNING j.id, j.destination, j.entity_type, j.entity_id, j.operation, j.attempts, j.priority,
		          j.payload::text AS payload, j.last_error
		""".trimIndent()
	)
		.param("destination", destination.wire)
		.param("limit", limit)
		.param("worker", workerId)
		.query(mapper)
		.list()

	fun markDone(id: Long) {
		jdbc.sql(
			"UPDATE outbound_jobs SET status = 'done', last_error = NULL, locked_at = NULL, locked_by = NULL WHERE id = :id"
		).param("id", id).update()
	}

	/**
	 * Returns the job to the queue after [delay].
	 *
	 * If a newer pending job for the same entity *and the same destination* appeared
	 * meanwhile, that one already carries the latest state and the partial unique
	 * index would reject a second pending row — so this one is dropped rather than
	 * retried. A pending job for a different destination proves nothing about this
	 * one and must not count.
	 */
	fun scheduleRetry(job: OutboundJob, error: String, delay: Duration) {
		val requeued = jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = 'pending',
			       next_attempt_at = now() + make_interval(secs => :delaySeconds),
			       last_error = :error,
			       locked_at = NULL,
			       locked_by = NULL
			 WHERE id = :id
			   AND NOT EXISTS (
			       SELECT 1 FROM outbound_jobs other
			        WHERE other.destination = :destination
			          AND other.entity_type = :type AND other.entity_id = :entityId
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		)
			.param("id", job.id)
			.param("delaySeconds", delay.toMillis() / 1000.0)
			.param("error", error.take(2000))
			.param("destination", job.destination.wire)
			.param("type", job.entityType.wire)
			.param("entityId", job.entityId)
			.update()

		if (requeued == 0) {
			jdbc.sql("DELETE FROM outbound_jobs WHERE id = :id").param("id", job.id).update()
		}
	}

	/**
	 * Puts the job back without spending an attempt.
	 *
	 * Used when nothing is wrong with the job itself — a dependency hasn't reached
	 * the far side yet, or the rate limiter asked us to wait. Counting those against
	 * `max-attempts` would fail perfectly good work just because the queue was
	 * busy.
	 */
	fun defer(job: OutboundJob, reason: String, delay: Duration) {
		val deferred = jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = 'pending',
			       attempts = GREATEST(attempts - 1, 0),
			       next_attempt_at = now() + make_interval(secs => :delaySeconds),
			       last_error = :reason,
			       locked_at = NULL,
			       locked_by = NULL
			 WHERE id = :id
			   AND NOT EXISTS (
			       SELECT 1 FROM outbound_jobs other
			        WHERE other.destination = :destination
			          AND other.entity_type = :type AND other.entity_id = :entityId
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		)
			.param("id", job.id)
			.param("delaySeconds", delay.toMillis() / 1000.0)
			.param("reason", reason.take(2000))
			.param("destination", job.destination.wire)
			.param("type", job.entityType.wire)
			.param("entityId", job.entityId)
			.update()

		if (deferred == 0) {
			jdbc.sql("DELETE FROM outbound_jobs WHERE id = :id").param("id", job.id).update()
		}
	}

	fun markFailed(id: Long, error: String) {
		jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = 'failed', last_error = :error, locked_at = NULL, locked_by = NULL
			 WHERE id = :id
			""".trimIndent()
		).param("id", id).param("error", error.take(2000)).update()
	}

	/**
	 * A worker that died mid-push leaves its job 'running' forever. Anything held
	 * longer than [timeout] goes back to the queue — unless a newer pending job for
	 * the same destination already supersedes it, in which case it is dropped.
	 *
	 * Not scoped to a destination: a dead worker took everything it was holding with
	 * it, whoever the work was for.
	 *
	 * `clock_timestamp()`, not `now()`: `now()` is the transaction's start time, so a
	 * job locked inside the same transaction would never appear old enough. How long
	 * a lock has actually been held is a wall-clock question.
	 */
	fun reclaimStuck(timeout: Duration): Int {
		// A zero timeout is a legitimate instruction — "reclaim anything locked before
		// right now" — which is what the tests and a manual sweep want.
		val seconds = timeout.seconds.coerceAtLeast(0)
		jdbc.sql(
			"""
			DELETE FROM outbound_jobs stale
			 WHERE stale.status = 'running'
			   AND stale.locked_at < clock_timestamp() - make_interval(secs => :seconds)
			   AND EXISTS (
			       SELECT 1 FROM outbound_jobs other
			        WHERE other.destination = stale.destination
			          AND other.entity_type = stale.entity_type
			          AND other.entity_id = stale.entity_id
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		).param("seconds", seconds.toDouble()).update()

		return jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = 'pending', locked_at = NULL, locked_by = NULL
			 WHERE status = 'running' AND locked_at < clock_timestamp() - make_interval(secs => :seconds)
			""".trimIndent()
		).param("seconds", seconds.toDouble()).update()
	}

	/**
	 * Per destination, because the screens that print these numbers are per
	 * destination: "the mirror is 40 jobs behind" is a sentence about Notion, and a
	 * total that quietly folded in another consumer's backlog would be a wrong answer
	 * to the question actually being asked.
	 */
	fun countsByStatus(destination: Destination): Map<String, Long> = jdbc.sql(
		"SELECT status, count(*) AS total FROM outbound_jobs WHERE destination = :destination GROUP BY status"
	)
		.param("destination", destination.wire)
		.query { rs, _ -> rs.text("status") to rs.getLong("total") }
		.list()
		.toMap()

	fun findFailed(destination: Destination, limit: Int = 50): List<OutboundJob> = jdbc.sql(
		"""
		SELECT id, destination, entity_type, entity_id, operation, attempts, priority,
		       payload::text AS payload, last_error
		  FROM outbound_jobs WHERE status = 'failed' AND destination = :destination
		 ORDER BY updated_at DESC LIMIT :limit
		""".trimIndent()
	).param("destination", destination.wire).param("limit", limit).query(mapper).list()

	/**
	 * Puts every failed job for one destination back in the queue. Used by the admin
	 * retry endpoint, which is a button on one destination's own screen — retrying
	 * somebody else's failures from it would be a surprise.
	 */
	fun retryAllFailed(destination: Destination): Int = jdbc.sql(
		"""
		UPDATE outbound_jobs SET status = 'pending', attempts = 0, next_attempt_at = now()
		 WHERE status = 'failed'
		   AND destination = :destination
		   AND NOT EXISTS (
		       SELECT 1 FROM outbound_jobs other
		        WHERE other.destination = outbound_jobs.destination
		          AND other.entity_type = outbound_jobs.entity_type
		          AND other.entity_id = outbound_jobs.entity_id
		          AND other.status = 'pending'
		   )
		""".trimIndent()
	).param("destination", destination.wire).update()
}

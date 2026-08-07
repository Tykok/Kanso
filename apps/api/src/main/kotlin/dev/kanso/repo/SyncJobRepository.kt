package dev.kanso.repo

import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncJob
import dev.kanso.sync.SyncOperation
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * The outbox. Every statement here is raw SQL on purpose: coalescing onto a
 * partial unique index and claiming with `FOR UPDATE SKIP LOCKED` are both
 * outside what the Exposed DSL can express, and both are load-bearing.
 */
@Repository
class SyncJobRepository(private val jdbc: JdbcClient) {

	private val mapper = RowMapper { rs, _ ->
		SyncJob(
			id = rs.getLong("id"),
			entityType = SyncEntityType.from(rs.text("entity_type")),
			entityId = rs.uuid("entity_id"),
			operation = SyncOperation.from(rs.text("operation")),
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
	 */
	fun enqueue(
		entityType: SyncEntityType,
		entityId: UUID,
		operation: SyncOperation,
		payload: String? = null,
	) {
		jdbc.sql(
			"""
			INSERT INTO sync_jobs (entity_type, entity_id, operation, status, priority, next_attempt_at, payload)
			VALUES (:type, :id, :op, 'pending', :priority, now(), CAST(:payload AS jsonb))
			ON CONFLICT (entity_type, entity_id) WHERE status = 'pending'
			DO UPDATE SET operation       = EXCLUDED.operation,
			              priority        = LEAST(sync_jobs.priority, EXCLUDED.priority),
			              next_attempt_at = LEAST(sync_jobs.next_attempt_at, EXCLUDED.next_attempt_at),
			              payload         = EXCLUDED.payload,
			              attempts        = 0,
			              last_error      = NULL
			""".trimIndent()
		)
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
	 */
	fun claimBatch(limit: Int, workerId: String): List<SyncJob> = jdbc.sql(
		"""
		WITH ready AS (
		    SELECT id FROM sync_jobs
		     WHERE status = 'pending' AND next_attempt_at <= now()
		     ORDER BY priority, next_attempt_at, id
		     LIMIT :limit
		     FOR UPDATE SKIP LOCKED
		)
		UPDATE sync_jobs j
		   SET status = 'running', locked_at = now(), locked_by = :worker, attempts = j.attempts + 1
		  FROM ready
		 WHERE j.id = ready.id
		RETURNING j.id, j.entity_type, j.entity_id, j.operation, j.attempts, j.priority,
		          j.payload::text AS payload, j.last_error
		""".trimIndent()
	).param("limit", limit).param("worker", workerId).query(mapper).list()

	fun markDone(id: Long) {
		jdbc.sql(
			"UPDATE sync_jobs SET status = 'done', last_error = NULL, locked_at = NULL, locked_by = NULL WHERE id = :id"
		).param("id", id).update()
	}

	/**
	 * Returns the job to the queue after [delay].
	 *
	 * If a newer pending job for the same entity appeared meanwhile, that one
	 * already carries the latest state and the partial unique index would reject
	 * a second pending row — so this one is dropped rather than retried.
	 */
	fun scheduleRetry(job: SyncJob, error: String, delay: Duration) {
		val requeued = jdbc.sql(
			"""
			UPDATE sync_jobs
			   SET status = 'pending',
			       next_attempt_at = now() + make_interval(secs => :delaySeconds),
			       last_error = :error,
			       locked_at = NULL,
			       locked_by = NULL
			 WHERE id = :id
			   AND NOT EXISTS (
			       SELECT 1 FROM sync_jobs other
			        WHERE other.entity_type = :type AND other.entity_id = :entityId
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		)
			.param("id", job.id)
			.param("delaySeconds", delay.toMillis() / 1000.0)
			.param("error", error.take(2000))
			.param("type", job.entityType.wire)
			.param("entityId", job.entityId)
			.update()

		if (requeued == 0) {
			jdbc.sql("DELETE FROM sync_jobs WHERE id = :id").param("id", job.id).update()
		}
	}

	/**
	 * Puts the job back without spending an attempt.
	 *
	 * Used when nothing is wrong with the job itself — a dependency hasn't reached
	 * Notion yet, or the rate limiter asked us to wait. Counting those against
	 * `max-attempts` would fail perfectly good work just because the queue was
	 * busy.
	 */
	fun defer(job: SyncJob, reason: String, delay: Duration) {
		val deferred = jdbc.sql(
			"""
			UPDATE sync_jobs
			   SET status = 'pending',
			       attempts = GREATEST(attempts - 1, 0),
			       next_attempt_at = now() + make_interval(secs => :delaySeconds),
			       last_error = :reason,
			       locked_at = NULL,
			       locked_by = NULL
			 WHERE id = :id
			   AND NOT EXISTS (
			       SELECT 1 FROM sync_jobs other
			        WHERE other.entity_type = :type AND other.entity_id = :entityId
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		)
			.param("id", job.id)
			.param("delaySeconds", delay.toMillis() / 1000.0)
			.param("reason", reason.take(2000))
			.param("type", job.entityType.wire)
			.param("entityId", job.entityId)
			.update()

		if (deferred == 0) {
			jdbc.sql("DELETE FROM sync_jobs WHERE id = :id").param("id", job.id).update()
		}
	}

	fun markFailed(id: Long, error: String) {
		jdbc.sql(
			"""
			UPDATE sync_jobs
			   SET status = 'failed', last_error = :error, locked_at = NULL, locked_by = NULL
			 WHERE id = :id
			""".trimIndent()
		).param("id", id).param("error", error.take(2000)).update()
	}

	/**
	 * A worker that died mid-push leaves its job 'running' forever. Anything held
	 * longer than [timeout] goes back to the queue — unless a newer pending job
	 * already supersedes it, in which case it is dropped.
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
			DELETE FROM sync_jobs stale
			 WHERE stale.status = 'running'
			   AND stale.locked_at < clock_timestamp() - make_interval(secs => :seconds)
			   AND EXISTS (
			       SELECT 1 FROM sync_jobs other
			        WHERE other.entity_type = stale.entity_type
			          AND other.entity_id = stale.entity_id
			          AND other.status = 'pending'
			   )
			""".trimIndent()
		).param("seconds", seconds.toDouble()).update()

		return jdbc.sql(
			"""
			UPDATE sync_jobs
			   SET status = 'pending', locked_at = NULL, locked_by = NULL
			 WHERE status = 'running' AND locked_at < clock_timestamp() - make_interval(secs => :seconds)
			""".trimIndent()
		).param("seconds", seconds.toDouble()).update()
	}

	fun countsByStatus(): Map<String, Long> = jdbc.sql(
		"SELECT status, count(*) AS total FROM sync_jobs GROUP BY status"
	).query { rs, _ -> rs.text("status") to rs.getLong("total") }.list().toMap()

	fun findFailed(limit: Int = 50): List<SyncJob> = jdbc.sql(
		"""
		SELECT id, entity_type, entity_id, operation, attempts, priority,
		       payload::text AS payload, last_error
		  FROM sync_jobs WHERE status = 'failed'
		 ORDER BY updated_at DESC LIMIT :limit
		""".trimIndent()
	).param("limit", limit).query(mapper).list()

	/** Puts every failed job back in the queue. Used by the admin retry endpoint. */
	fun retryAllFailed(): Int = jdbc.sql(
		"""
		UPDATE sync_jobs SET status = 'pending', attempts = 0, next_attempt_at = now()
		 WHERE status = 'failed'
		   AND NOT EXISTS (
		       SELECT 1 FROM sync_jobs other
		        WHERE other.entity_type = sync_jobs.entity_type
		          AND other.entity_id = sync_jobs.entity_id
		          AND other.status = 'pending'
		   )
		""".trimIndent()
	).update()
}

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
	 * "Make sure there is a pending job for this entity, and tell me which one" — without
	 * changing what an already-queued job was going to say.
	 *
	 * Added for the webhook replay, which is the first caller that needs a job's *id*
	 * rather than only its existence: it writes a `webhook_deliveries` row pointing at the
	 * job that will carry it, so the handler can find the delivery it was asked to repeat.
	 *
	 * The difference from [enqueue] is the whole reason this is a second method rather than
	 * a flag on that one. [enqueue] means "this entity changed, here is its current state",
	 * so coalescing *overwrites* — `payload`, `operation` and `attempts` all take the newer
	 * value, because the newer state is the one worth pushing. A replay means "send this old
	 * event again", which is not a statement about the entity's current state at all, so
	 * overwriting a queued job's payload with it would make a genuine pending change go out
	 * describing something that had already been superseded. Here the conflict branch only
	 * pulls `next_attempt_at` forward — it wakes the queue and touches nothing else.
	 *
	 * `RETURNING id` fires on the update branch too, so the id comes back whether the row
	 * was inserted or found. Without the no-op `DO UPDATE` there would be no returned row
	 * on a conflict at all, which is the trap `DO NOTHING` sets.
	 */
	fun enqueueQuiet(
		destination: Destination,
		entityType: OutboundEntityType,
		entityId: UUID,
		operation: OutboundOperation,
		payload: String? = null,
	): Long = jdbc.sql(
		"""
		INSERT INTO outbound_jobs (destination, entity_type, entity_id, operation, status, priority, next_attempt_at, payload)
		VALUES (:destination, :type, :id, :op, 'pending', :priority, now(), CAST(:payload AS jsonb))
		ON CONFLICT (destination, entity_type, entity_id) WHERE status = 'pending'
		DO UPDATE SET next_attempt_at = LEAST(outbound_jobs.next_attempt_at, EXCLUDED.next_attempt_at)
		RETURNING id
		""".trimIndent()
	)
		.param("destination", destination.wire)
		.param("type", entityType.wire)
		.param("id", entityId)
		.param("op", operation.wire)
		.param("priority", entityType.priority)
		.param("payload", payload)
		.query(Long::class.java)
		.single()

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
		   SET status = 'running', locked_at = now(), locked_by = :worker, attempts = j.attempts + 1,
		       heartbeat_at = clock_timestamp()
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

	/**
	 * "The process holding these is still alive." Called on a clock while pushes are in
	 * flight — see `OutboundWorker.beat`.
	 *
	 * This is the whole of the fix for a slow push being reclaimed under itself: it moves
	 * the question the stuck-job sweep asks from "how long has this been running", which
	 * a slow push and a dead worker answer identically, to "when was the holder last
	 * seen", which only one of them can answer. `locked_at` is deliberately left alone,
	 * so how long a push has actually been running stays readable — see `V29`.
	 *
	 * Keyed on the job id and never on `locked_by`, which is what keeps this compatible
	 * with a worker id shared across concurrent drains: the ids come from the holder's
	 * own in-memory register of what it is pushing right now, so no identity has to be
	 * matched in SQL and the sweep's predicate stays `locked_by`-free.
	 *
	 * `status = 'running'` rather than the id alone. If the sweep has already reclaimed a
	 * row — the worker really was gone, or a beat really was missed — the next beat must
	 * not stamp liveness onto a job that is now pending and may already be claimed by
	 * somebody else.
	 */
	fun heartbeat(jobIds: Collection<Long>): Int {
		if (jobIds.isEmpty()) return 0
		return jdbc.sql(
			"""
			UPDATE outbound_jobs SET heartbeat_at = clock_timestamp()
			 WHERE status = 'running' AND id IN (:ids)
			""".trimIndent()
		).param("ids", jobIds).update()
	}

	fun markDone(id: Long) {
		jdbc.sql(
			"""
			UPDATE outbound_jobs
			   SET status = 'done', last_error = NULL, locked_at = NULL, locked_by = NULL, heartbeat_at = NULL
			 WHERE id = :id
			""".trimIndent()
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
			       locked_by = NULL,
			       heartbeat_at = NULL
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
			       locked_by = NULL,
			       heartbeat_at = NULL
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
			   SET status = 'failed', last_error = :error,
			       locked_at = NULL, locked_by = NULL, heartbeat_at = NULL
			 WHERE id = :id
			""".trimIndent()
		).param("id", id).param("error", error.take(2000)).update()
	}

	/**
	 * A worker that died mid-push leaves its job 'running' forever. Anything whose holder
	 * has not been heard from for [timeout] goes back to the queue — unless a newer
	 * pending job for the same destination already supersedes it, in which case it is
	 * dropped.
	 *
	 * **The age of the lock is not what is measured, and that is the point.** Reading
	 * `locked_at` answers "how long has this push been running", and a push that is
	 * merely slow answers it exactly like a process that died holding the row. The sweep
	 * would then reclaim a job still in flight, a drain would claim it again, and the same
	 * operation would go out twice — invisibly, while Notion is the only destination,
	 * because a repeated `upsert` writes the same state. `COALESCE(heartbeat_at,
	 * locked_at)` asks the question the sweep actually has: when was the holder last known
	 * to be alive. See [heartbeat] and `V29`.
	 *
	 * [timeout] therefore stops meaning "longer than a push should ever take" and starts
	 * meaning "more consecutive heartbeats missed than a live process would ever miss",
	 * which is a fact about this deployment rather than a guess about somebody's remote.
	 *
	 * Still keyed on time and never on `locked_by`, which is what keeps a worker id shared
	 * across concurrent drains harmless: nothing here compares identities, so two drains
	 * presenting the same id cannot reclaim from each other. [heartbeat] keeps it that way
	 * by addressing rows by id.
	 *
	 * `COALESCE` rather than the bare column, because a 'running' row with no heartbeat
	 * never got as far as beating and has to stay reclaimable; NULL compares false against
	 * everything, so the alternative is a job invisible to the sweep forever.
	 *
	 * Not scoped to a destination: a dead worker took everything it was holding with
	 * it, whoever the work was for.
	 *
	 * `clock_timestamp()`, not `now()`: `now()` is the transaction's start time, so a
	 * job locked inside the same transaction would never appear old enough. How long
	 * ago something was last seen is a wall-clock question.
	 */
	fun reclaimStuck(timeout: Duration): Int {
		// A zero timeout is a legitimate instruction — "reclaim anything locked before
		// right now" — which is what the tests and a manual sweep want.
		val seconds = timeout.seconds.coerceAtLeast(0)
		jdbc.sql(
			"""
			DELETE FROM outbound_jobs stale
			 WHERE stale.status = 'running'
			   AND COALESCE(stale.heartbeat_at, stale.locked_at)
			         < clock_timestamp() - make_interval(secs => :seconds)
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
			   SET status = 'pending', locked_at = NULL, locked_by = NULL, heartbeat_at = NULL
			 WHERE status = 'running'
			   AND COALESCE(heartbeat_at, locked_at) < clock_timestamp() - make_interval(secs => :seconds)
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

package dev.kanso.docs

import dev.kanso.repo.text
import dev.kanso.repo.timestamp
import dev.kanso.repo.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * `doc_block_locks`, and every statement here is raw SQL for one reason: **`now()` has to
 * be the database's**.
 *
 * A lock this repository decided with `OffsetDateTime.now()` in Kotlin would be a lock
 * whose expiry two API instances, and the browser that drew the countdown, all disagree
 * about — by whatever their clocks are apart. The row is written from `now() + interval`
 * and read back with `expires_at > now()`, so the only clock in the feature is the one
 * that is also the arbiter.
 *
 * The second reason is [take], which is the whole atomicity of `KAN-25`: an
 * `INSERT … ON CONFLICT (block_id) DO UPDATE … WHERE`, which the Exposed DSL does not
 * express. `OutboundJobRepository` made the same call for the same kind of statement.
 */
@Repository
class DocBlockLockRepository(private val jdbc: JdbcClient) {

	/**
	 * Takes the block for [userId], or refuses.
	 *
	 * One statement, and the `WHERE` on the conflict branch is the refusal: the update
	 * lands only if the existing row has lapsed or already belongs to the caller.
	 * Otherwise Postgres updates nothing, the `RETURNING` is empty, and this answers null
	 * — which the service turns into a 409 naming the holder it then reads.
	 *
	 * Renewal is the same call, and that is not an economy. A separate `renew` guarded on
	 * `user_id = :userId` would be a second grant path, and the day the two guards drifted
	 * the renewal would be the one that let somebody keep a block they had lost. Here
	 * holding it for another thirty seconds and taking it for the first time are literally
	 * the same statement, so they cannot disagree.
	 *
	 * `taken_at` is not touched on the conflict branch: a renewal extends a claim, it does
	 * not restate when it began, and the screen reads "held for four minutes" off it.
	 */
	fun take(blockId: UUID, userId: UUID, ttl: Duration): DocBlockLock? = jdbc.sql(
		"""
		INSERT INTO doc_block_locks (block_id, user_id, expires_at, taken_at)
		VALUES (:blockId, :userId, now() + make_interval(secs => :ttlSeconds), now())
		ON CONFLICT (block_id) DO UPDATE
		   SET user_id    = :userId,
		       expires_at = now() + make_interval(secs => :ttlSeconds)
		 WHERE doc_block_locks.expires_at <= now()
		    OR doc_block_locks.user_id = :userId
		RETURNING block_id, user_id, expires_at, taken_at
		""".trimIndent()
	)
		.param("blockId", blockId)
		.param("userId", userId)
		.param("ttlSeconds", ttl.toMillis() / 1000.0)
		.query(mapper)
		.optional()
		.orElse(null)

	/**
	 * The live lock on one block, or null.
	 *
	 * "Live" is the only reading of this table anybody gets: an expired row is not a lock,
	 * so it is filtered here rather than returned with a flag for every caller to remember
	 * to check. Nothing in this class hands out a lapsed claim.
	 */
	fun liveFor(blockId: UUID): DocBlockLock? = jdbc.sql(
		"""
		SELECT block_id, user_id, expires_at, taken_at
		  FROM doc_block_locks
		 WHERE block_id = :blockId AND expires_at > now()
		""".trimIndent()
	)
		.param("blockId", blockId)
		.query(mapper)
		.optional()
		.orElse(null)

	/**
	 * Every live lock on one page, with the holder's name, in one query.
	 *
	 * The join is what `V40` chose instead of a `page_id` column, and this is the read it
	 * was weighed against: `doc_blocks_page_idx` drives it, and `users` supplies the name
	 * the refusal has to print. Reading the name here rather than in the service is what
	 * keeps `GET /api/docs/pages/{id}` at one query for the whole page's locks instead of
	 * one per held block.
	 */
	fun liveForPage(pageId: UUID): List<DocBlockLockHolder> = jdbc.sql(
		"""
		SELECT l.block_id, l.user_id, l.expires_at, l.taken_at, u.display_name
		  FROM doc_block_locks l
		  JOIN doc_blocks b ON b.id = l.block_id
		  JOIN users       u ON u.id = l.user_id
		 WHERE b.page_id = :pageId AND l.expires_at > now()
		""".trimIndent()
	)
		.param("pageId", pageId)
		.query(holderMapper)
		.list()

	/** The holder's display name, for the one refusal that has to name somebody. */
	fun holderName(userId: UUID): String? = jdbc
		.sql("SELECT display_name FROM users WHERE id = :userId")
		.param("userId", userId)
		.query(String::class.java)
		.optional()
		.orElse(null)

	/**
	 * Releases the block, if the caller is the one holding it.
	 *
	 * Guarded on `user_id` rather than deleting whatever is there: a release arriving late
	 * — a `beforeunload` from a tab whose lock had already lapsed and been taken by
	 * somebody else — must not free the new holder's block. That is the one race a blur
	 * can lose, and it costs one clause.
	 *
	 * Not guarded on `expires_at`: releasing a lock the caller held and has just let lapse
	 * is exactly what should happen, and refusing it would leave an inert row behind for no
	 * reason.
	 */
	fun release(blockId: UUID, userId: UUID): Boolean = jdbc
		.sql("DELETE FROM doc_block_locks WHERE block_id = :blockId AND user_id = :userId")
		.param("blockId", blockId)
		.param("userId", userId)
		.update() > 0

	private companion object {
		val mapper = RowMapper { rs, _ ->
			DocBlockLock(
				blockId = rs.uuid("block_id"),
				userId = rs.uuid("user_id"),
				expiresAt = rs.timestamp("expires_at"),
				takenAt = rs.timestamp("taken_at"),
			)
		}

		val holderMapper = RowMapper { rs, _ ->
			DocBlockLockHolder(
				blockId = rs.uuid("block_id"),
				userId = rs.uuid("user_id"),
				displayName = rs.text("display_name"),
				expiresAt = rs.timestamp("expires_at"),
				takenAt = rs.timestamp("taken_at"),
			)
		}
	}
}

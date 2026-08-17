package dev.kanso.service

import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One entry of the log, with the person resolved. [actor] is null for a row whose
 * author has since been deleted — `actor_id` is `ON DELETE SET NULL`, because losing
 * the account must not lose the history.
 */
data class ActivityRow(
	val id: UUID,
	val entity: ActivityEntity,
	val entityId: UUID,
	val actor: User?,
	val kind: ActivityKind,
	val payload: Map<String, Any?>,
	val createdAt: OffsetDateTime,
)

/**
 * What happened, written where it happened.
 *
 * [record] is called by the services that already publish events, at the same point in
 * the same transaction as the change — never by a listener on `pg_notify`.
 * `EventPublisher` fires on `afterCommit` and its payload is capped at 8000 bytes: a
 * receiver that was not listening never learns, which would make the log lossy in
 * exactly the case it exists to explain. The consequence for tests is the useful one:
 * the row is there to be asserted while the transaction is still open.
 *
 * Reads are open, as `architecture.md` states. There is no [TicketAccess] call here and
 * no actor parameter on [forEntity] — but an activity row can name a private ticket, so
 * the public surfaces (slice F) read none of it.
 */
@Service
class ActivityService(
	private val activity: ActivityRepository,
	private val users: UserRepository,
	private val json: ObjectMapper,
) {

	/**
	 * [payload] carries the before and after of one scalar and nothing else. The shared
	 * mapper omits nulls, so a bound that was unset comes back as an absent key rather
	 * than an explicit null — the same fact either way for anything drawing a feed.
	 */
	@Transactional
	fun record(
		entity: ActivityEntity,
		entityId: UUID,
		actorId: UUID?,
		kind: ActivityKind,
		payload: Map<String, Any?> = emptyMap(),
	) {
		activity.insert(
			id = UUID.randomUUID(),
			entity = entity,
			entityId = entityId,
			actorId = actorId,
			kind = kind,
			payload = json.writeValueAsString(payload),
			createdAt = OffsetDateTime.now(),
		)
	}

	/** Newest first: every reader of this list draws a feed, and a feed starts at the top. */
	@Transactional(readOnly = true)
	fun forEntity(entity: ActivityEntity, entityId: UUID, limit: Int = 50): List<ActivityRow> {
		val records = activity.forEntity(entity, entityId, limit)
		if (records.isEmpty()) return emptyList()
		// One query for the whole page's actors instead of one per row — the same shape
		// `TicketService.decorate` uses, and a feed repeats the same few people.
		val actors = users.findAllById(records.mapNotNull { it.actorId }.toSet()).associateBy { it.id }
		return records.map { record ->
			ActivityRow(
				id = record.id,
				entity = record.entity,
				entityId = record.entityId,
				actor = record.actorId?.let { actors[it] },
				kind = record.kind,
				payload = decode(record.payload),
				createdAt = record.createdAt,
			)
		}
	}

	/** Whatever [record] was handed, back out again. jsonb objects have string keys. */
	@Suppress("UNCHECKED_CAST")
	private fun decode(payload: String): Map<String, Any?> =
		json.readValue(payload, Map::class.java) as Map<String, Any?>
}

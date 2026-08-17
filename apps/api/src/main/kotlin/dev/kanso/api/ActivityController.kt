package dev.kanso.api

import dev.kanso.domain.ActivityEntity
import dev.kanso.service.ActivityRow
import dev.kanso.service.ActivityService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One entry of a feed. [payload] travels as it was written — the shape depends on
 * [kind], and pinning it into a class per kind would put a second definition of the
 * vocabulary on the wire for a client that only ever formats a sentence from it.
 */
data class ActivityResponse(
	val id: UUID,
	val entityType: String,
	val entityId: UUID,
	/** Null once the account is gone. The history is not. */
	val actor: UserResponse?,
	val kind: String,
	val payload: Map<String, Any?>,
	val createdAt: OffsetDateTime,
) {
	companion object {
		fun of(row: ActivityRow) = ActivityResponse(
			id = row.id,
			entityType = row.entity.wire,
			entityId = row.entityId,
			actor = row.actor?.let(UserResponse::of),
			kind = row.kind.wire,
			payload = row.payload,
			createdAt = row.createdAt,
		)
	}
}

/**
 * Reads are open, as every other GET here is. `entityType` is parsed at this edge, so
 * an unknown one is a 400 from [ActivityEntity.from] rather than a query that quietly
 * matches nothing.
 */
@RestController
@RequestMapping("/api/activity")
class ActivityController(private val activity: ActivityService) {

	@GetMapping
	fun list(
		@RequestParam entityType: String,
		@RequestParam entityId: UUID,
		@RequestParam(defaultValue = "50") limit: Int,
	): List<ActivityResponse> = activity
		.forEntity(ActivityEntity.from(entityType), entityId, limit.coerceIn(1, 200))
		.map(ActivityResponse::of)
}

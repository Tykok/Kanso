package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.ActivityEntity
import dev.kanso.service.TicketAccess
import dev.kanso.service.ActivityRow
import dev.kanso.service.ActivityService
import org.springframework.security.access.AccessDeniedException
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
 *
 * "Open" is a claim about *ids that are public*, though, and not a policy that survives any
 * addition to [ActivityEntity]. [list] carries one clause per entity type that needs a rule
 * and the reason beside it; adding a value to that enum means deciding which of them the
 * new one belongs to, and `V30` records what it cost to learn that.
 */
@RestController
@RequestMapping("/api/activity")
class ActivityController(
	private val activity: ActivityService,
	private val currentUser: CurrentUser,
	private val access: TicketAccess,
) {

	@GetMapping
	fun list(
		@RequestParam entityType: String,
		@RequestParam entityId: UUID,
		@RequestParam(defaultValue = "50") limit: Int,
	): List<ActivityResponse> {
		val entity = ActivityEntity.from(entityType)
		// Only a ticket's feed can be a draft's. A project, a team or a doc is named by
		// something the reader could already list, and asking the ticket rule about one
		// would be asking the wrong question of the wrong id.
		if (entity == ActivityEntity.TICKET) access.requireReadable(currentUser.require(), entityId)
		// An account's feed asks a different question, because its id names a *person*.
		//
		// The clause above is an argument about public ids: a project, a team or a doc can be
		// listed by any member, so a feed keyed on one gives away nothing its reader could
		// not already read, and leaving those open costs nothing. `USER` is the first value
		// where that stops holding. `entityId` is a free query parameter, and a
		// `token_revoked` payload carries the *name* somebody gave a credential — "prod
		// deploy", "laptop CLI" — so without this line `?entityType=user&entityId=<anyone>`
		// hands one member a map of another's integrations, through no new endpoint and with
		// nothing about the request looking wrong.
		//
		// Yourself or a configurator, which is the rule the account itself already uses
		// rather than a third answer invented here. `ApiTokenService` takes no user id on
		// any method, so a member cannot so much as enumerate somebody else's tokens; a read
		// side looser than that would make the history disclose what the write side refuses
		// to admit exists.
		if (entity == ActivityEntity.USER) {
			val reader = currentUser.require()
			if (reader.id != entityId && !reader.instanceRole.canConfigureInstance) {
				throw AccessDeniedException("An account's history is its own owner's, and the instance admins'")
			}
		}
		return activity.forEntity(entity, entityId, limit.coerceIn(1, 200)).map(ActivityResponse::of)
	}
}

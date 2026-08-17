package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.repo.NotificationRow
import dev.kanso.service.Inbox
import dev.kanso.service.InboxTab
import dev.kanso.service.NotificationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class InboxActorResponse(val id: UUID, val displayName: String)

data class NotificationResponse(
	/** Null for a failed mirror push: nothing stored it, so nothing can mark it read. */
	val id: UUID?,
	val kind: String,
	val entityType: String,
	val entityId: UUID,
	val reference: String?,
	val subject: String?,
	val actor: InboxActorResponse?,
	val payload: Map<String, Any?>,
	val readAt: OffsetDateTime?,
	val createdAt: OffsetDateTime,
) {
	companion object {
		fun of(row: NotificationRow) = NotificationResponse(
			id = row.id,
			kind = row.kind,
			entityType = row.entityType,
			entityId = row.entityId,
			reference = row.reference,
			subject = row.subject,
			actor = row.actor?.let { InboxActorResponse(it.id, it.displayName) },
			payload = row.payload,
			readAt = row.readAt,
			createdAt = row.createdAt,
		)
	}
}

data class InboxCountsResponse(
	val all: Int,
	val assigned: Int,
	val mentions: Int,
	val failures: Int,
	val unread: Int,
)

data class InboxResponse(
	val rows: List<NotificationResponse>,
	val counts: InboxCountsResponse,
) {
	companion object {
		fun of(inbox: Inbox) = InboxResponse(
			rows = inbox.rows.map(NotificationResponse::of),
			counts = with(inbox.counts) {
				InboxCountsResponse(all, assigned, mentions, failures, unread)
			},
		)
	}
}

/**
 * Screen 14.
 *
 * The only reads in Kanso that are not open: "reads are open, writes are scoped"
 * is about *team* scoping, and a notification has no team — it belongs to one
 * person by construction, and answering somebody else's would be handing over a
 * list of what they have been told. So every method here reads the session's own
 * identity and never takes a user id.
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(
	private val currentUser: CurrentUser,
	private val notifications: NotificationService,
) {

	@GetMapping
	fun inbox(
		@RequestParam(required = false) tab: String?,
		@RequestParam(defaultValue = "50") limit: Int,
	): InboxResponse = InboxResponse.of(
		notifications.inbox(
			userId = currentUser.requireId(),
			tab = InboxTab.from(tab),
			limit = limit.coerceIn(1, 200),
		)
	)

	/** `⇧e`. Returns how many rows it changed, so the badge does not need a refetch. */
	@PostMapping("/read-all")
	fun readAll(): Map<String, Int> =
		mapOf("read" to notifications.markAllRead(currentUser.requireId()))

	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun read(@PathVariable id: UUID) = notifications.markRead(currentUser.requireId(), id)
}

package dev.kanso.service

import dev.kanso.repo.FailedPush
import dev.kanso.repo.NotificationRepository
import dev.kanso.repo.NotificationRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The six things the inbox is drawn to say, closed in Kotlin and again by a `CHECK`
 * — the pattern `TicketStatus` and `user_preferences` already share. `CONFLICT` is
 * the seventh and is not one of the six: it is not a row in the list but the thing
 * the chooser opens on, and it lives here because it is still "somebody has to be
 * told", written at the same moment and read from the same table.
 */
enum class NotificationKind(val wire: String) {
	ASSIGNED("assigned"),
	MENTIONED("mentioned"),
	SYNC_FAILED("sync_failed"),
	STATUS_MOVED("status_moved"),
	COMMENT_REPLIED("comment_replied"),
	PROJECT_SLIPPED("project_slipped"),
	CONFLICT("conflict");

	companion object {
		fun from(raw: String): NotificationKind = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown notification kind '$raw'")
	}
}

/**
 * The four tabs of screen 14. They do not partition: `All` is everything, and a
 * status change or a slipped project belongs to no narrower tab — the drawing shows
 * both under `All` and offers no fifth strip for them.
 *
 * A reply to your comment counts as a mention. It is the same event from the reader's
 * side — somebody wrote a sentence at you — and one drawing is not enough to design a
 * separate thread inbox around.
 */
enum class InboxTab(val wire: String, val kinds: Set<NotificationKind>) {
	ALL("all", emptySet()),
	ASSIGNED("assigned", setOf(NotificationKind.ASSIGNED)),
	MENTIONS("mentions", setOf(NotificationKind.MENTIONED, NotificationKind.COMMENT_REPLIED)),

	/** Read off the outbox, not off `notifications` — see [NotificationRepository]. */
	FAILURES("failures", setOf(NotificationKind.SYNC_FAILED));

	companion object {
		fun from(raw: String?): InboxTab = raw?.let { wanted ->
			entries.firstOrNull { it.wire == wanted }
				?: throw BadRequestException("Unknown inbox tab '$wanted'")
		} ?: ALL
	}
}

/** What the tab strip prints. `all` is the whole inbox, not the sum of the others. */
data class InboxCounts(
	val all: Int,
	val assigned: Int,
	val mentions: Int,
	val failures: Int,
	val unread: Int,
)

data class Inbox(val rows: List<NotificationRow>, val counts: InboxCounts)

/**
 * Who needs to know, and what they have already seen.
 *
 * [record] is the write half and is called by the services that make the change —
 * the same place, in the same transaction, as the activity row. It is not called
 * from a listener on `pg_notify`: that would make the inbox lossy in exactly the
 * case someone opens it to find out what they missed.
 */
@Service
class NotificationService(private val notifications: NotificationRepository) {

	/**
	 * Writes one row per recipient and returns how many were written.
	 *
	 * The actor is dropped from [recipients] rather than asked not to be there.
	 * Everything that notifies notifies a set — a ticket's assignees, a comment's
	 * mentions — and that set routinely contains the person who caused the change;
	 * making every call site subtract them is how one of them eventually forgets and
	 * tells somebody what they just did themselves.
	 */
	@Transactional
	fun record(
		recipients: Collection<UUID>,
		kind: NotificationKind,
		entityType: String,
		entityId: UUID,
		actorId: UUID?,
		payload: Map<String, Any?> = emptyMap(),
	): Int {
		val targets = recipients.toSet() - setOfNotNull(actorId)
		for (userId in targets) {
			notifications.insert(userId, kind.wire, entityType, entityId, actorId, payload)
		}
		return targets.size
	}

	/**
	 * Whether this exact disagreement has already been recorded, for anybody.
	 *
	 * The inbound poller's guard, and the reason it is a question rather than an `ON
	 * CONFLICT`: the Kanso-wins rule answers on timestamps alone and so fires on every poll
	 * of a row that moved since the last push. Without this the same two versions of the
	 * same title would be recorded every thirty seconds until the corrective push landed —
	 * and for as long as it kept failing, which is exactly when somebody is reading the
	 * inbox.
	 */
	@Transactional(readOnly = true)
	fun conflictRecorded(entityId: UUID, field: String, theirs: String): Boolean =
		notifications.conflictExists(entityId, field, theirs)

	@Transactional(readOnly = true)
	fun inbox(userId: UUID, tab: InboxTab = InboxTab.ALL, limit: Int = DEFAULT_LIMIT): Inbox {
		val stored = if (tab == InboxTab.FAILURES) {
			emptyList()
		} else {
			notifications.forUser(userId, tab.kinds.map { it.wire }, limit)
		}
		val failures = if (tab == InboxTab.ASSIGNED || tab == InboxTab.MENTIONS) {
			emptyList()
		} else {
			notifications.failedPushes(limit).map(::asRow)
		}

		// Merged by time rather than concatenated: the two halves are one list on
		// screen, and a failure four minutes old belongs above an assignment from
		// yesterday whichever table it came out of.
		val rows = (failures + stored).sortedByDescending { it.createdAt }.take(limit)

		val byKind = notifications.countsByKind(userId)
		val stillFailing = notifications.failedPushCount().toInt()
		val countOf = { kinds: Set<NotificationKind> -> kinds.sumOf { byKind[it.wire] ?: 0L }.toInt() }

		return Inbox(
			rows = rows,
			counts = InboxCounts(
				all = byKind.values.sum().toInt() + stillFailing,
				assigned = countOf(InboxTab.ASSIGNED.kinds),
				mentions = countOf(InboxTab.MENTIONS.kinds),
				failures = stillFailing,
				// A failed push is unread while it is failed. There is no dismissing
				// one: the drawing offers Retry and the queue, not an acknowledgement,
				// and a mirror somebody ticked off is still a mirror that is behind.
				unread = notifications.unreadCount(userId).toInt() + stillFailing,
			),
		)
	}

	@Transactional
	fun markAllRead(userId: UUID): Int = notifications.markAllRead(userId)

	/**
	 * `⇧e` on one row. A row already read is not an error — the shortcut and a click
	 * can both land on the same row — but somebody else's row is a [NotFoundException]
	 * rather than a 403: a notification is the one kind of row whose existence is
	 * itself private, so "not yours" and "no such thing" have to read alike.
	 */
	@Transactional
	fun markRead(userId: UUID, id: UUID) {
		if (notifications.markRead(userId, id) == 0 && !notifications.exists(userId, id)) {
			throw NotFoundException("No notification $id")
		}
	}

	/**
	 * A failed push, shaped as an inbox row.
	 *
	 * `id` stays null: there is no `notifications` row behind this and the client must
	 * not be able to send it to [markRead]. `createdAt` is when the queue gave up,
	 * which is what "4 minutes ago" on the drawn row means.
	 *
	 * `destination` is in the payload because the outbox is no longer Notion's alone
	 * and the sentence has to stop saying so. The kind stays [NotificationKind.SYNC_FAILED]:
	 * it is a wire value in `V13`'s `CHECK` and in the web client's closed list, and
	 * "a push failed" is still exactly what it means — renaming it would cost a
	 * migration and a client release to say the same thing.
	 */
	private fun asRow(push: FailedPush) = NotificationRow(
		id = null,
		kind = NotificationKind.SYNC_FAILED.wire,
		entityType = push.entityType,
		entityId = push.entityId,
		reference = push.reference,
		subject = push.subject,
		actor = null,
		payload = mapOf(
			"jobId" to push.jobId,
			"destination" to push.destination,
			"attempts" to push.attempts,
			"error" to push.error,
		),
		readAt = null,
		createdAt = push.failedAt,
	)

	private companion object {
		const val DEFAULT_LIMIT = 50
	}
}

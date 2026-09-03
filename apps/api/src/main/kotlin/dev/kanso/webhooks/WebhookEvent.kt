package dev.kanso.webhooks

import dev.kanso.outbox.OutboundEntityType
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.KansoEvent
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The body Kanso POSTs, and the whole of its public contract.
 *
 * ## Its own type, not `KansoEvent` serialised
 *
 * It is built from [KansoEvent] and is very nearly the same fields, and it is still a
 * separate class. `KansoEvent` is an internal DTO on a bus between Kanso and its own
 * browser tabs: it can be renamed, widened or narrowed in the same commit as the client
 * that reads it, because both ends ship together. This is a published wire format read by
 * software nobody here controls, where the same edit is a silent breaking change to every
 * subscriber. Serialising the internal type would make every future refactor of it a
 * compatibility event that no reviewer would recognise as one.
 *
 * ## Thin
 *
 * Identifiers, a verb and a time. No title, no description, no assignees. `V31` carries the
 * argument in full — authorisation stays in one place, a delete can be delivered at all,
 * and the log stays cheap — and it is the same decision `Events.kt` records for the
 * realtime bus. A subscriber that wants the ticket fetches the ticket, with an `api_tokens`
 * credential whose scopes and seat decide what it may see.
 */
data class WebhookEvent(
	/**
	 * `ticket.updated` — the routing key, and the same string the `X-Kanso-Event` header
	 * carries so a receiver can switch on either without them ever disagreeing.
	 *
	 * Denormalised from [entity] and [kind] rather than left to the subscriber to
	 * concatenate, because every subscriber would concatenate it and half of them would
	 * pick a different separator when they logged it.
	 */
	val event: String,
	val entity: String,
	val kind: String,
	val id: UUID,
	val teamId: UUID?,
	val projectId: UUID?,
	/** "kanso" for a person's action, "notion" when the inbound poller applied it. */
	val origin: String,
	/** When the change happened, not when this delivery was attempted — the signature carries that. */
	val at: OffsetDateTime,
) {
	companion object {
		fun of(source: KansoEvent, entityType: OutboundEntityType): WebhookEvent {
			val kind = source.kind.name.lowercase()
			return WebhookEvent(
				event = "${entityType.wire}.$kind",
				entity = entityType.wire,
				kind = kind,
				id = source.id,
				teamId = source.teamId,
				projectId = source.projectId,
				origin = source.origin,
				at = source.at,
			)
		}

		/**
		 * `KansoEvent.entity` is a plural topic name ("tickets"); the queue's axis is a
		 * singular [OutboundEntityType] ("ticket"). Mapped rather than derived by trimming
		 * an `s`, because that would be a rule quietly waiting for the first entity whose
		 * plural is not its singular plus a letter.
		 *
		 * An unmapped topic returns null and enqueues nothing, which is the right default:
		 * a new realtime topic should not start being broadcast to third parties because
		 * somebody added a `pg_notify` for it. Widening the webhook contract stays a
		 * deliberate act, and it is one line here.
		 */
		fun entityTypeOf(topic: String): OutboundEntityType? = when (topic) {
			"tickets" -> OutboundEntityType.TICKET
			"projects" -> OutboundEntityType.PROJECT
			"teams" -> OutboundEntityType.TEAM
			else -> null
		}

		/**
		 * A delete is the only kind the queue has to treat differently, because it is the
		 * only one whose entity is gone by the time the job drains. The others are a
		 * change to something that still exists.
		 *
		 * The verb the *subscriber* sees is [kind] and stays precise; this is only which
		 * `outbound_jobs.operation` the row carries, and that vocabulary was written for a
		 * mirror rather than for an event log.
		 */
		fun isDelete(kind: ChangeKind): Boolean = kind == ChangeKind.DELETED

		/**
		 * The payload a replay puts on a job it had to create, and the bit that keeps a
		 * replay from becoming a broadcast.
		 *
		 * A replay needs a queue row to ride — that is how it inherits the retry, the
		 * backoff and the rate limit instead of forking them — and
		 * `OutboundJobRepository.enqueueQuiet` gets it one either by finding the entity's
		 * pending job or by making a new one. Those two cases owe the fan-out opposite
		 * things, and the payload is what tells them apart:
		 *
		 *   * **Coalesced onto a real pending change.** `enqueueQuiet` leaves the payload
		 *     alone, so the job still carries a genuine event and the automatic fan-out
		 *     runs as it should. The replay row is delivered *as well*.
		 *   * **A new job.** The payload is this marker, and `WebhookOutboundHandler.plan`
		 *     reads it as "deliver the replay rows attached to me and nothing else".
		 *
		 * Without the distinction, replaying one delivery to one subscriber would enqueue
		 * the old event as though it had just happened and send it to *every* subscriber —
		 * a button labelled "send this again" that quietly notifies four other systems
		 * about an hour-old change.
		 *
		 * A sentinel string rather than a column on `outbound_jobs`, because the
		 * alternative is widening the table two destinations share to carry a fact only one
		 * of them has. The job's own payload is already this handler's private business:
		 * the bytes a subscriber receives live on the delivery row, never here.
		 */
		const val REPLAY_ONLY_JOB = """{"replayOnly":true}"""
	}
}

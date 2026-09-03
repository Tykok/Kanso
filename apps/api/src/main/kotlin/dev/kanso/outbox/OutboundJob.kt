package dev.kanso.outbox

import java.util.UUID

/**
 * Where a job is going — one value per system Kanso pushes to, one
 * [OutboundJobHandler] per value.
 *
 * This is half of the queue's discriminator and the half the worker dispatches on;
 * [OutboundEntityType] is the other half and says what the job is *about*. Two axes
 * rather than one compound `notion.ticket`, because the failures tab names a job by
 * its entity whatever destination refused it, and that has to stay a join rather
 * than a string prefix. See `V26`.
 *
 * Closed here and again by a `CHECK`, so a typo cannot invent a destination that
 * nothing drains and everything waits for.
 */
enum class Destination(val wire: String) {
	NOTION("notion"),

	/**
	 * Outbound webhooks — one job per entity change, fanned out to every subscription
	 * whose filter matches it. See `V31` and `WebhookOutboundHandler`.
	 *
	 * The fan-out is the one thing this axis cannot express: the queue's discriminator is
	 * `(destination, entity_type, entity_id)` with no room for a subscription, so N
	 * subscribers share one job's attempts and `webhook_deliveries` is what keeps a retry
	 * from re-POSTing to the ones that already answered. `V31` carries the trade.
	 */
	WEBHOOK("webhook");

	companion object {
		fun from(raw: String): Destination = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown outbound destination '$raw'")
	}
}

/**
 * What the job is about.
 *
 * Dependency order is encoded in [priority]: whatever a relation points at has to
 * exist at the far end before the thing pointing at it — a project page cannot be
 * created in Notion before its team page, and a ticket needs both. The worker drains
 * lower numbers first, so a fresh install pushes the graph in a valid order without
 * a topological sort at runtime. A destination whose graph is flat pays nothing for
 * the ordering.
 */
enum class OutboundEntityType(val wire: String, val priority: Int) {
	TEAM("team", 10),
	PROJECT("project", 20),
	TICKET("ticket", 30),
	DOC("doc", 40);

	companion object {
		fun from(raw: String): OutboundEntityType = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown outbound entity type '$raw'")
	}
}

enum class OutboundOperation(val wire: String) {
	/** Create or overwrite the far side from the current Postgres row. */
	UPSERT("upsert"),

	/** Notion archives rather than deletes; a Kanso delete lands here too. */
	ARCHIVE("archive"),
	DELETE("delete");

	companion object {
		fun from(raw: String): OutboundOperation = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown outbound operation '$raw'")
	}
}

data class OutboundJob(
	val id: Long,
	val destination: Destination,
	val entityType: OutboundEntityType,
	val entityId: UUID,
	val operation: OutboundOperation,
	val attempts: Int,
	val priority: Int,
	val payload: String?,
	val lastError: String?,
)

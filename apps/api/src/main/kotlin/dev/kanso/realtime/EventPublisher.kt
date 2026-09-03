package dev.kanso.realtime

import dev.kanso.config.KansoProperties
import dev.kanso.webhooks.WebhookFanout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * Publishes a change on a Postgres channel rather than pushing to the local broker
 * directly.
 *
 * Two reasons. First, ordering: emitting inside the transaction would let a client
 * refetch before the commit is visible and see stale data. Second, reach: every API
 * instance is LISTENing, so one notification fans out to all connected clients
 * without Redis or sticky sessions — and the instance that made the change receives
 * it through exactly the same path as its peers, so there is only one broadcast code
 * path to reason about.
 */
@Component
class EventPublisher(
	private val dataSource: DataSource,
	private val props: KansoProperties,
	private val objectMapper: ObjectMapper,
	/**
	 * The webhook half of "this entity changed", queued from here for the reason
	 * [WebhookFanout] argues: this method is already the one funnel every mutation passes
	 * through exactly once, and the alternative was a second call beside forty
	 * `outbox.enqueue` sites woven into archive cascades.
	 *
	 * `ObjectProvider` so this class keeps working in a context that has no webhook slice
	 * wired — several tests build a trimmed context, and a hard dependency would make the
	 * realtime bus unstartable without the queue.
	 */
	private val fanout: ObjectProvider<WebhookFanout>,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun publish(event: KansoEvent) {
		// **Before the `afterCommit` registration, not inside it.** The two halves of
		// announcing a change want opposite treatment: the notification below is deferred
		// past the commit so no client can refetch ahead of it, while the webhook job must
		// be written *in* the caller's transaction — that is the outbox's founding
		// guarantee, that a queued push cannot be lost if the process dies right after the
		// commit. Deferring this too would be the natural-looking mistake and would trade
		// a durable delivery for a cosmetic ordering property it does not need.
		fanout.ifAvailable { it.record(event) }

		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() = notify(event)
			})
		} else {
			notify(event)
		}
	}

	private fun notify(event: KansoEvent) {
		try {
			val payload = objectMapper.writeValueAsString(event)

			// A connection straight from the pool, not the thread-bound one.
			//
			// During afterCommit the transaction's connection is still bound to the
			// thread but already committed, so anything run through the usual Spring
			// plumbing would open a *new* transaction on it — one nobody ever commits.
			// The NOTIFY would be rolled back when the connection went back to the
			// pool, silently: the statement succeeds, and no other session ever sees it.
			dataSource.connection.use { connection ->
				// pg_notify caps the payload at 8000 bytes; these events are tiny by design.
				connection.prepareStatement("SELECT pg_notify(?, ?)").use { statement ->
					statement.setString(1, props.realtime.channel)
					statement.setString(2, payload)
					statement.execute()
				}
				if (!connection.autoCommit) connection.commit()
			}
			log.debug("notified {} for {} {}", props.realtime.channel, event.entity, event.id)
		} catch (e: Exception) {
			// A realtime miss is cosmetic — the next read is still correct. Never let it
			// fail or roll back a write that has already committed.
			log.warn("Failed to publish {} event for {}: {}", event.entity, event.id, e.message)
		}
	}
}

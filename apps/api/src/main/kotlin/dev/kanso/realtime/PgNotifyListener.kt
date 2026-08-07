package dev.kanso.realtime

import dev.kanso.config.KansoProperties
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors

/**
 * Holds one long-lived `LISTEN` connection and relays what arrives to this
 * instance's STOMP subscribers.
 *
 * The connection is opened directly rather than borrowed from Hikari: a pooled
 * connection parked here forever would shrink the pool and be recycled out from
 * under us by `maxLifetime`. Losing it is still expected (restarts, failover), so
 * the loop reconnects and re-LISTENs rather than assuming it stays up.
 */
@Component
class PgNotifyListener(
	private val props: KansoProperties,
	private val messaging: SimpMessagingTemplate,
	private val objectMapper: ObjectMapper,
	@Value("\${spring.datasource.url}") private val jdbcUrl: String,
	@Value("\${spring.datasource.username}") private val username: String,
	@Value("\${spring.datasource.password}") private val password: String,
) : SmartLifecycle {

	private val log = LoggerFactory.getLogger(javaClass)
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "pg-notify-listener").apply { isDaemon = true }
	}

	@Volatile
	private var active = false

	override fun start() {
		active = true
		executor.submit(::listenLoop)
		log.info("Listening on Postgres channel '{}'", props.realtime.channel)
	}

	override fun stop() {
		active = false
		executor.shutdownNow()
	}

	override fun isRunning(): Boolean = active

	private fun listenLoop() {
		while (active) {
			try {
				openConnection().use { connection ->
					connection.createStatement().use { it.execute("LISTEN ${props.realtime.channel}") }
					val pgConnection = connection.unwrap(PGConnection::class.java)
					while (active && !connection.isClosed) {
						// Blocks until something arrives or the timeout elapses. The
						// timeout is what lets `active` be noticed on shutdown.
						pgConnection.getNotifications(POLL_TIMEOUT_MS)
							?.forEach { relay(it.parameter) }
					}
				}
			} catch (_: InterruptedException) {
				return
			} catch (e: Exception) {
				if (!active) return
				log.warn("LISTEN connection lost ({}), reconnecting in {}ms", e.message, RECONNECT_DELAY_MS)
				try {
					Thread.sleep(RECONNECT_DELAY_MS)
				} catch (_: InterruptedException) {
					return
				}
			}
		}
	}

	private fun openConnection(): Connection =
		DriverManager.getConnection(jdbcUrl, username, password)

	private fun relay(payload: String?) {
		if (payload.isNullOrBlank()) return
		try {
			val event = objectMapper.readValue(payload, KansoEvent::class.java)
			val destinations = event.destinations()
			log.debug("relaying {} {} to {}", event.entity, event.id, destinations)
			destinations.forEach { messaging.convertAndSend(it, event) }
		} catch (e: Exception) {
			log.warn("Dropping unreadable realtime payload: {}", e.message)
		}
	}

	private companion object {
		const val POLL_TIMEOUT_MS = 5_000
		const val RECONNECT_DELAY_MS = 2_000L
	}
}

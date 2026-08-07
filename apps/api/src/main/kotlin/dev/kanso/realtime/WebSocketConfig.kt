package dev.kanso.realtime

import dev.kanso.config.KansoProperties
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP over a native WebSocket, with the in-memory simple broker.
 *
 * No SockJS fallback: every browser Kanso targets speaks WebSocket, and the
 * fallback transports would add polling endpoints for nothing.
 *
 * Clients only ever subscribe. There is no `/app` inbound destination because
 * mutations go through REST — one write path, one place where validation,
 * transactions and the Notion outbox live.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(private val props: KansoProperties) : WebSocketMessageBrokerConfigurer {

	override fun configureMessageBroker(registry: MessageBrokerRegistry) {
		registry.enableSimpleBroker("/topic")
	}

	override fun registerStompEndpoints(registry: StompEndpointRegistry) {
		// The handshake carries the session cookie, so the endpoint is already
		// authenticated by the security filter chain.
		registry.addEndpoint("/ws").setAllowedOrigins(props.webOrigin)
	}
}

package dev.kanso.realtime

import dev.kanso.config.KansoProperties
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.support.ChannelInterceptor
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

	/**
	 * The class doc's "clients only ever subscribe", enforced instead of asserted.
	 *
	 * The absence of an `/app` prefix is what made that sentence false rather than what made
	 * it true. `SimpleBrokerMessageHandler` is subscribed to the inbound channel and handles
	 * any frame whose destination carries a prefix it owns — with no application prefix
	 * declared, `/topic` is the only routing rule there is, so a browser `SEND` to
	 * `/topic/tickets` was fanned out to every subscriber verbatim.
	 *
	 * That is not a second write path, it is a write path with none of the first one's
	 * checks: `ReadOnlySeat` is an HTTP `HandlerInterceptor` and never sees a STOMP frame, so
	 * a VIEWER refused every write over REST could still announce that a ticket was deleted
	 * and have every open tab believe it. Nothing reached Postgres and nothing was logged,
	 * which is the part that makes it worth a guard rather than a comment.
	 */
	override fun configureClientInboundChannel(registration: ChannelRegistration) {
		registration.interceptors(SubscribeOnly())
	}
}

/**
 * Drops client `SEND` frames and passes everything else.
 *
 * Returning `null` from `preSend` is how this channel refuses: it stops the frame without an
 * error frame back, which is right for a client that has no business sending one anyway.
 * `MESSAGE` and not a destination allowlist, because the rule is about the *direction* of the
 * bus rather than about which topics exist — a new topic should not have to be remembered
 * here. SUBSCRIBE is deliberately untouched: `DocPresence` is a feature built on a client
 * subscribing, and `KansoEvent.viewersTopic` is where that destination is decided.
 *
 * Its own class rather than an object expression so [WebSocketInboundTest] can hand it a
 * frame directly. A test that needed a live socket to assert this would not get written.
 */
internal class SubscribeOnly : ChannelInterceptor {

	override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? =
		if (SimpMessageHeaderAccessor.getMessageType(message.headers) == SimpMessageType.MESSAGE) {
			null
		} else {
			message
		}
}

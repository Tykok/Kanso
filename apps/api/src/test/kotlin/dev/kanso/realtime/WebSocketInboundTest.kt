package dev.kanso.realtime

import dev.kanso.MockMvcTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.support.AbstractMessageChannel
import org.springframework.messaging.support.MessageBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WebSocketConfig]'s bus is one-way, and this is the half of that claim a comment cannot
 * make.
 *
 * The invariant was written down and not enforced, which is a shape worth naming because it
 * looked enforced: "there is no `/app` inbound destination" is true, and the conclusion drawn
 * from it — that a client therefore cannot send — is the opposite of what the absence means.
 * With no application prefix declared, the broker's own `/topic` is the only routing rule on
 * the inbound channel, so `SimpleBrokerMessageHandler` fanned a browser `SEND` straight back
 * out to every subscriber. A VIEWER seat that `ReadOnlySeat` refuses every REST write to
 * could announce a ticket's deletion to every open tab, with nothing written to Postgres and
 * nothing in the logs.
 *
 * Two assertions, because the rule and its wiring rot separately and both rot silently. The
 * first hands [SubscribeOnly] a frame and asks what it does with it; the second asks the
 * running context whether that answer is anywhere in the path a real frame takes. Deleting
 * the `configureClientInboundChannel` override leaves the first green.
 */
class WebSocketInboundTest : MockMvcTest() {

	/**
	 * Qualified by name because the broker configuration contributes several channels and
	 * the inbound one is the only one under test — the outbound channel is the server
	 * talking, which is the direction that is supposed to work.
	 */
	@Autowired
	@Qualifier("clientInboundChannel")
	lateinit var inbound: AbstractMessageChannel

	@Test
	fun `a client SEND is dropped and a SUBSCRIBE is not`() {
		val send = frame(SimpMessageType.MESSAGE)
		val subscribe = frame(SimpMessageType.SUBSCRIBE)

		assertNull(
			SubscribeOnly().preSend(send, inbound),
			"a client SEND to /topic/tickets is broadcast verbatim to every subscriber, and " +
				"passes none of the checks the REST write path exists to apply",
		)
		assertNotNull(
			SubscribeOnly().preSend(subscribe, inbound),
			"DocPresence is built on a client subscribing — refusing SUBSCRIBE would take " +
				"presence away rather than close anything",
		)
	}

	@Test
	fun `the interceptor is on the channel a real frame arrives through`() {
		assertTrue(
			inbound.interceptors.any { it is SubscribeOnly },
			"`SubscribeOnly` is not registered on `clientInboundChannel`, so it refuses " +
				"nothing that a browser actually sends. It is wired by " +
				"`WebSocketConfig.configureClientInboundChannel`; removing that override " +
				"leaves every other test in this suite green.",
		)
	}

	/**
	 * `/topic/tickets` and not a made-up destination: the point of the frame is that it names
	 * a prefix the simple broker owns, which is what made it routable in the first place.
	 */
	private fun frame(type: SimpMessageType) = MessageBuilder.createMessage(
		ByteArray(0),
		SimpMessageHeaderAccessor.create(type)
			.apply { destination = "/topic/tickets" }
			.messageHeaders,
	)
}

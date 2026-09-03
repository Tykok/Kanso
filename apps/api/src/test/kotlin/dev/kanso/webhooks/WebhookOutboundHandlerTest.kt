package dev.kanso.webhooks

import dev.kanso.PostgresTest
import dev.kanso.config.KansoProperties
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundJobHandler
import dev.kanso.outbox.OutboundOperation
import dev.kanso.outbox.OutboundWorker
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.TeamRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.TaskScheduler
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The webhook handler, driven through the real [OutboundWorker].
 *
 * Through the worker and not around it, because the claim this ticket makes is that the
 * queue, the retry and the backoff were *reused* — and the only way to show that is to let
 * the worker classify what the handler throws and watch `outbound_jobs` move. Asserting on
 * the handler's return value alone would prove the handler works and say nothing about
 * whether it is wired into the machinery it was supposed to inherit.
 *
 * Built by hand for `OutboundWorkerTest`'s reason: the real `WebhookOutboundHandler` already
 * claims [Destination.WEBHOOK] in the application context, and two handlers for one
 * destination is exactly the wiring the worker refuses. The only fake is the socket, which
 * is the seam `WebhookSender` exists to be — `GoogleCredentialProbeTest` argues why the HTTP
 * call is an interface here.
 *
 * Every assertion is scoped to entity ids this test made, because `outbound_jobs` is shared
 * across test classes and `ImportCommitTest` commits into it.
 */
@Transactional
class WebhookOutboundHandlerTest : PostgresTest() {

	@Autowired lateinit var subscriptions: WebhookSubscriptionRepository
	@Autowired lateinit var deliveries: WebhookDeliveryRepository
	@Autowired lateinit var secrets: WebhookSecret
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var objectMapper: ObjectMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var scheduler: TaskScheduler
	@Autowired lateinit var teams: TeamRepository

	/** Records what it was asked to send, and answers whatever the test scripted. */
	private class FakeSender : WebhookSender {
		data class Call(val url: String, val body: String, val headers: Map<String, String>)

		val calls = mutableListOf<Call>()
		var respond: (String) -> Int = { 200 }
		var unreachable = false

		override suspend fun post(url: String, body: String, headers: Map<String, String>): WebhookHttpResponse {
			calls += Call(url, body, headers)
			if (unreachable) throw WebhookUnreachable("no route to host")
			return WebhookHttpResponse(respond(url), "")
		}
	}

	// --- the signature, over the wire ----------------------------------------

	@Test
	fun `a delivery carries a signature the subscription's own secret verifies`() {
		val (_, secret) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		drain(sender)

		val call = sender.calls.single()
		val header = assertNotNull(
			call.headers[WebhookSignature.HEADER],
			"every delivery must be signed; headers were ${call.headers.keys}",
		)
		assertTrue(
			WebhookSignature.verify(call.body, secret, header, Instant.now()),
			"a subscriber following the recipe with their own secret must be able to verify this",
		)
		assertTrue(
			call.headers.containsKey(WebhookSignature.DELIVERY_HEADER),
			"the idempotency key is what lets a receiver dedupe a retry; headers were ${call.headers.keys}",
		)
		assertEquals("ticket.updated", call.headers[WebhookSignature.EVENT_HEADER], "the routing header")
	}

	@Test
	fun `the body carries identifiers and no ticket contents`() {
		subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		drain(sender)

		val body = sender.calls.single().body
		assertTrue(body.contains(entityId.toString()), "the id is the message; body was $body")
		for (leak in listOf("title", "description", "assignee")) {
			assertTrue(
				!body.contains(leak),
				"V31's thin payload is what keeps authorisation in one place — '$leak' must not be in $body",
			)
		}
	}

	// --- the ledger, which is what makes a shared retry safe -----------------

	@Test
	fun `a retry does not call an endpoint that already answered 200`() {
		val (healthy, _) = subscribe()
		val (broken, _) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		// First pass: one subscriber answers, the other does not.
		val first = FakeSender()
		first.respond = { url -> if (url == healthy.url) 200 else 500 }
		drain(first)

		assertEquals(2, first.calls.size, "both subscriptions are in the fan-out")
		assertEquals("pending", jobStatus(entityId), "one endpoint failed, so the job goes back for another go")

		// Second pass: the job is retried, and the ledger is read.
		makeReady(entityId)
		val second = FakeSender()
		second.respond = { 200 }
		drain(second)

		assertEquals(
			listOf(broken.url),
			second.calls.map { it.url },
			"the subscriber that already answered 200 must not be POSTed again — this is the whole " +
				"reason webhook_deliveries exists, and without it a shared job-level retry duplicates deliveries",
		)
		assertEquals("done", jobStatus(entityId), "with everybody delivered the job is finished")
		assertEquals(1, attemptsFor(healthy.id, entityId), "the healthy endpoint was called exactly once")
		assertEquals(2, attemptsFor(broken.id, entityId), "the failing one records both tries")
	}

	@Test
	fun `the same delivery id is presented on a retry, so a receiver can dedupe`() {
		subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val first = FakeSender()
		first.respond = { 500 }
		drain(first)

		makeReady(entityId)
		val second = FakeSender()
		second.respond = { 200 }
		drain(second)

		assertEquals(
			first.calls.single().headers[WebhookSignature.DELIVERY_HEADER],
			second.calls.single().headers[WebhookSignature.DELIVERY_HEADER],
			"X-Kanso-Delivery is the idempotency key and must be stable across retries of one delivery",
		)
		// And the retry's signature is stamped at *send* time, not carried over from the first
		// attempt — which is what makes the recipe's freshness check survivable on a retry
		// that happens an hour later. Asserted as "the `t` is current" rather than "the two
		// headers differ", because `t` is whole seconds and two passes in one test land in the
		// same second; `WebhookSignatureTest` is where re-stamping is proved to change the
		// digest at all.
		val stamped = second.calls.single().headers.getValue(WebhookSignature.HEADER)
			.substringAfter("t=").substringBefore(",").toLong()
		assertTrue(
			Instant.now().epochSecond - stamped in 0..60,
			"the retry must be signed now, not at enqueue time; t was $stamped",
		)
	}

	// --- the queue's own policy, inherited rather than rewritten --------------

	@Test
	fun `a 500 spends an attempt and comes back, a 2xx finishes`() {
		subscribe()
		val failing = UUID.randomUUID()
		enqueueChange(failing)

		val sender = FakeSender()
		sender.respond = { 500 }
		drain(sender)
		assertEquals("pending", jobStatus(failing), "a 5xx is worth another go")
		assertEquals(1, attemptCountOnJob(failing), "and it counts, because the queue's max-attempts must be reachable")

		// A second entity, enqueued now: the first drain would otherwise have claimed it too
		// and backed it off into the future, where the next pass cannot reach it.
		val fine = UUID.randomUUID()
		enqueueChange(fine)
		val good = FakeSender()
		drain(good)
		assertEquals("done", jobStatus(fine), "a 2xx is the end of it")
	}

	@Test
	fun `an unreachable endpoint records a null status, which is not a 500`() {
		val (subscription, _) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		sender.unreachable = true
		drain(sender)

		val delivery = deliveryFor(subscription.id, entityId)
		assertNull(
			delivery.responseStatus,
			"nothing answered, so there is no HTTP status — a configurator chasing 'the endpoint is " +
				"not there' must not be reading HTTP semantics into a fabricated 500",
		)
		assertEquals(DeliveryStatus.FAILED, delivery.status)
		assertEquals("pending", jobStatus(entityId), "a missing route is transient until max-attempts says otherwise")
	}

	@Test
	fun `a throttled subscription defers, and the deferral refunds the attempt`() {
		subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		// A limiter with nothing to give: the delivery must be postponed, not failed.
		drain(sender, limit = WebhookRateLimit(perMinute = 0))

		assertEquals(0, sender.calls.size, "a throttled delivery must not reach the endpoint at all")
		assertEquals("pending", jobStatus(entityId), "throttled is late, never dropped")
		assertEquals(
			0,
			attemptCountOnJob(entityId),
			"the claim spent an attempt and the deferral must refund it — being popular is not being broken, " +
				"and counting these would fail perfectly good work for being paced",
		)
	}

	@Test
	fun `a receiver's own 429 is treated as backpressure and costs the delivery nothing`() {
		val (subscription, _) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		sender.respond = { 429 }
		drain(sender)

		assertEquals("pending", jobStatus(entityId), "they asked us to slow down, which is not a failure")
		assertEquals(0, attemptCountOnJob(entityId), "their backpressure must not consume our attempts")
		assertEquals(
			DeliveryStatus.PENDING,
			deliveryFor(subscription.id, entityId).status,
			"a 429 leaves the row alone rather than writing a failure nobody should act on",
		)
	}

	// --- endpoints that will never work --------------------------------------

	@Test
	fun `a 4xx stops the subscription instead of spending eight attempts proving it`() {
		val (subscription, _) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		sender.respond = { 400 }
		drain(sender)

		assertEquals(
			"done",
			jobStatus(entityId),
			"a receiver that refuses this body will refuse it identically eight more times",
		)
		val reason = subscriptions.findById(subscription.id)?.disabledReason
		assertNotNull(reason, "the subscription must be switched off, or a dead endpoint costs every entity eight tries")
		assertTrue(reason.contains("400"), "and the reason must name what came back; it said '$reason'")

		// Found by looking at the running app rather than by this test: the row was recording a
		// null status for a 400, and `V31` reserves null for "nothing answered". A refusal is
		// an endpoint that is very much there, and reporting it as null sends a configurator
		// hunting a network problem that does not exist.
		assertEquals(
			400,
			deliveryFor(subscription.id, entityId).responseStatus,
			"a 4xx answered, so the journal must say what it answered",
		)
	}

	@Test
	fun `giving up on a job disables whatever is still undelivered`() {
		val (subscription, _) = subscribe()
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		sender.respond = { 500 }
		// maxAttempts = 1, so this pass is the last one.
		drain(sender, maxAttempts = 1)

		assertEquals("failed", jobStatus(entityId), "the queue has stopped trying")
		assertNotNull(
			subscriptions.findById(subscription.id)?.disabledReason,
			"otherwise one dead endpoint is not eight failed attempts but eight per queued entity",
		)
	}

	// --- who gets what -------------------------------------------------------

	@Test
	fun `a team-scoped subscription is not sent another team's changes`() {
		// Real rows: `webhook_subscriptions.team_id` is a foreign key, so a random UUID is
		// refused by the database rather than quietly scoping to nothing.
		val mine = newTeam()
		val theirs = newTeam()
		val (scoped, _) = subscribe(teamId = mine)

		val ours = UUID.randomUUID()
		val notOurs = UUID.randomUUID()
		enqueueChange(ours, teamId = mine)
		enqueueChange(notOurs, teamId = theirs)

		val sender = FakeSender()
		drain(sender)

		assertEquals(1, sender.calls.size, "exactly one of the two changes is in this subscription's scope")
		assertTrue(sender.calls.single().body.contains(ours.toString()), "and it is the one from its own team")
		assertEquals(scoped.url, sender.calls.single().url)
	}

	@Test
	fun `a teamless change reaches only the instance-wide subscriptions`() {
		subscribe(teamId = newTeam())
		val entityId = UUID.randomUUID()
		enqueueChange(entityId, teamId = null)

		val sender = FakeSender()
		drain(sender)

		assertEquals(
			0,
			sender.calls.size,
			"a subscription scoped to a team asked for that team's changes; a teamless ticket (V20) is " +
				"not one of them, and a null-friendly comparison here would leak in exactly the untested case",
		)
	}

	@Test
	fun `the entity filter is honoured`() {
		subscribe(entities = setOf(OutboundEntityType.TEAM))
		val ticketId = UUID.randomUUID()
		enqueueChange(ticketId)

		val sender = FakeSender()
		drain(sender)

		assertEquals(
			0,
			sender.calls.size,
			"a subscriber who asked for team changes and is sent every ticket edit is a subscriber who " +
				"will be sent a bulk edit of five hundred rows",
		)
	}

	@Test
	fun `a disabled subscription receives nothing`() {
		val (subscription, _) = subscribe()
		subscriptions.disable(subscription.id, "switched off by hand")
		val entityId = UUID.randomUUID()
		enqueueChange(entityId)

		val sender = FakeSender()
		drain(sender)

		assertEquals(0, sender.calls.size, "disabled is the off switch, not a label")
	}

	// --- helpers -------------------------------------------------------------

	private fun handler(sender: WebhookSender, limit: WebhookRateLimit) = WebhookOutboundHandler(
		subscriptions = subscriptions,
		deliveries = deliveries,
		secrets = secrets,
		limit = limit,
		sender = sender,
		tx = tx,
		objectMapper = objectMapper,
	)

	private fun drain(
		sender: WebhookSender,
		limit: WebhookRateLimit = WebhookRateLimit(perMinute = 1_000),
		maxAttempts: Int = 8,
	) {
		val handler: OutboundJobHandler = handler(sender, limit)
		OutboundWorker(
			props = KansoProperties(
				sync = KansoProperties.Sync(outbound = KansoProperties.Outbound(maxAttempts = maxAttempts)),
			),
			jobs = jobs,
			handlers = listOf(handler),
			tx = tx,
			scheduler = scheduler,
		).drain(handler)
	}

	private fun newTeam(): UUID =
		teams.insert("Webhook team ${UUID.randomUUID().toString().take(4)}", key(), null).id

	private fun key() = "W${UUID.randomUUID().toString().take(5).uppercase()}"

	private fun subscribe(
		teamId: UUID? = null,
		entities: Set<OutboundEntityType> = setOf(OutboundEntityType.TICKET),
	): Pair<WebhookSubscription, String> {
		val secret = secrets.generate()
		val subscription = subscriptions.insert(
			url = "https://receiver.test/hook/${UUID.randomUUID()}",
			description = "test receiver",
			secretCipher = secrets.encrypt(secret),
			secretPrefix = WebhookSecret.prefixOf(secret),
			entities = entities,
			teamId = teamId,
			createdBy = null,
		)
		return subscription to secret
	}

	private fun enqueueChange(
		entityId: UUID,
		teamId: UUID? = null,
		entity: OutboundEntityType = OutboundEntityType.TICKET,
	) {
		val event = WebhookEvent(
			event = "${entity.wire}.updated",
			entity = entity.wire,
			kind = "updated",
			id = entityId,
			teamId = teamId,
			projectId = null,
			origin = "kanso",
			at = OffsetDateTime.now(),
		)
		jobs.enqueue(
			Destination.WEBHOOK,
			entity,
			entityId,
			OutboundOperation.UPSERT,
			objectMapper.writeValueAsString(event),
		)
	}

	/** The backoff puts a retried job in the future; a test must not wait for it. */
	private fun makeReady(entityId: UUID) {
		jdbc.sql(
			"UPDATE outbound_jobs SET next_attempt_at = now() " +
				"WHERE entity_id = :id AND destination = 'webhook'"
		).param("id", entityId).update()
	}

	private fun jobStatus(entityId: UUID): String? = jdbc
		.sql("SELECT status FROM outbound_jobs WHERE entity_id = :id AND destination = 'webhook'")
		.param("id", entityId)
		.query { rs, _ -> rs.getString("status") }
		.optional()
		.orElse(null)

	private fun attemptCountOnJob(entityId: UUID): Int = jdbc
		.sql("SELECT attempts FROM outbound_jobs WHERE entity_id = :id AND destination = 'webhook'")
		.param("id", entityId)
		.query { rs, _ -> rs.getInt("attempts") }
		.single()

	private fun deliveryFor(subscriptionId: UUID, entityId: UUID): WebhookDelivery =
		deliveries.recent(subscriptionId, 50).single { it.entityId == entityId }

	private fun attemptsFor(subscriptionId: UUID, entityId: UUID): Int =
		deliveryFor(subscriptionId, entityId).attempts
}

package dev.kanso.webhooks

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundJobHandler
import dev.kanso.outbox.OutboundWorker
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.TaskScheduler
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Who may point Kanso at somebody else's server, and what a replay actually does.
 *
 * The authorisation half is the part worth being strict about. A subscription is an
 * instance-wide egress rule — a null `team_id` matches every team, including ones its
 * creator is not in — so **every** method here is a configurator's, reads included, and each
 * one is proved to refuse rather than the guard being asserted once and assumed to be
 * everywhere. A guard that is on four of five methods is the same bug as no guard.
 */
@Transactional
class WebhookServiceTest : PostgresTest() {

	@Autowired lateinit var webhooks: WebhookService
	@Autowired lateinit var subscriptions: WebhookSubscriptionRepository
	@Autowired lateinit var deliveryRows: WebhookDeliveryRepository
	@Autowired lateinit var secrets: WebhookSecret
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var jobs: OutboundJobRepository
	@Autowired lateinit var tx: TransactionTemplate
	@Autowired lateinit var objectMapper: ObjectMapper
	@Autowired lateinit var jdbc: JdbcClient
	@Autowired lateinit var scheduler: TaskScheduler

	private class FakeSender : WebhookSender {
		data class Call(val url: String, val body: String)

		val calls = mutableListOf<Call>()
		override suspend fun post(url: String, body: String, headers: Map<String, String>) =
			WebhookHttpResponse(200, "").also { calls += Call(url, body) }
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- the guard, both ways ------------------------------------------------

	@Test
	fun `a member cannot create, read, delete, enable or replay a webhook`() {
		val subscription = actAs(admin) { newSubscription().subscription }
		val delivery = seedDelivery(subscription.id)

		for (seat in listOf(InstanceRole.MEMBER, InstanceRole.VIEWER)) {
			val outsider = user(seat)
			actAs(outsider) {
				assertFailsWith<AccessDeniedException>("$seat must not list webhooks") { webhooks.list() }
				assertFailsWith<AccessDeniedException>("$seat must not create one") {
					webhooks.create("https://elsewhere.test/hook", "mine", listOf("ticket"), null)
				}
				assertFailsWith<AccessDeniedException>("$seat must not delete one") {
					webhooks.delete(subscription.id)
				}
				assertFailsWith<AccessDeniedException>("$seat must not re-arm one") {
					webhooks.enable(subscription.id)
				}
				assertFailsWith<AccessDeniedException>(
					"$seat must not read the journal — it is a list of every change and where it was sent, " +
						"which is the same disclosure as receiving them",
				) { webhooks.deliveries(subscription.id, 50) }
				assertFailsWith<AccessDeniedException>("$seat must not replay a delivery") {
					webhooks.replay(delivery)
				}
			}
		}
	}

	@Test
	fun `an owner and an admin may, which is what makes the refusals above a rule and not a wall`() {
		for (seat in listOf(InstanceRole.OWNER, InstanceRole.ADMIN)) {
			val actor = user(seat)
			actAs(actor) {
				val created = newSubscription()
				assertTrue(
					webhooks.list().any { it.id == created.subscription.id },
					"$seat configures the instance and must be able to do this",
				)
			}
		}
	}

	// --- the secret, shown once ---------------------------------------------

	@Test
	fun `the secret is returned once and stored encrypted`() {
		val created = actAs(admin) { newSubscription() }

		assertTrue(created.secret.startsWith(WebhookSecret.MARKER), "secret was ${created.secret}")
		assertEquals(
			WebhookSecret.prefixOf(created.secret),
			created.subscription.secretPrefix,
			"the stored label must be the start of the secret that was handed out",
		)

		val stored = jdbc.sql("SELECT secret_cipher FROM webhook_subscriptions WHERE id = :id")
			.param("id", created.subscription.id)
			.query { rs, _ -> rs.getString("secret_cipher") }
			.single()
		assertNotEquals(created.secret, stored, "the column must not hold the plaintext")
		assertEquals(
			created.secret,
			secrets.decrypt(stored),
			"and it must be recoverable, because signing needs the bytes back — the one place V27's " +
				"argument for a one-way digest does not reach",
		)

		// The listing has no field to leak it through, which is a fact about the type rather
		// than a habit of this constructor call — see `WebhookSubscription`.
		assertFalse(
			WebhookResponse.of(created.subscription).toString().contains(created.secret),
			"a listing must not carry the secret",
		)
	}

	@Test
	fun `a plaintext url is refused, and so is an empty entity list`() {
		actAs(admin) {
			assertFailsWith<BadRequestException>("http to a non-loopback host must be refused") {
				webhooks.create("http://receiver.test/hook", "insecure", listOf("ticket"), null)
			}
			assertFailsWith<BadRequestException>("a subscription matching nothing is broken, not narrow") {
				webhooks.create("https://receiver.test/hook", "empty", emptyList(), null)
			}
			assertFailsWith<BadRequestException>("an unknown noun must be named rather than dropped") {
				webhooks.create("https://receiver.test/hook", "typo", listOf("tickets"), null)
			}
			assertFailsWith<BadRequestException>("the description bound is V31's CHECK, with a sentence") {
				webhooks.create("https://receiver.test/hook", "  ", listOf("ticket"), null)
			}
		}
	}

	// --- replay --------------------------------------------------------------

	@Test
	fun `a replay is a new row, and the delivery it repeats is left as evidence`() {
		val subscription = actAs(admin) { newSubscription().subscription }
		val original = seedDelivery(subscription.id, status = "failed", responseStatus = 500)

		val replayId = actAs(admin) { webhooks.replay(original) }

		assertNotEquals(original, replayId, "a replay is a different request, made later, and gets its own row")

		val before = assertNotNull(deliveryRows.findById(original))
		assertEquals(
			DeliveryStatus.FAILED,
			before.status,
			"folding a replay into the original would overwrite the failure that prompted it — the " +
				"evidence would disappear at the moment somebody started acting on it",
		)
		assertEquals(500, before.responseStatus, "and so would the status")

		val replay = assertNotNull(deliveryRows.findById(replayId))
		assertEquals(original, replay.replayOf, "the new row points at what it repeats")
		assertEquals(DeliveryStatus.PENDING, replay.status, "queued, not sent from the request thread")
		assertEquals(before.payload, replay.payload, "a replay sends the bytes that were signed the first time")
		assertNotNull(replay.jobId, "it must ride a queue job, which is how it inherits the retry and the limit")
	}

	@Test
	fun `replaying one delivery does not broadcast the old event to everybody else`() {
		val (target, other) = actAs(admin) { newSubscription().subscription to newSubscription().subscription }
		val original = seedDelivery(target.id, status = "failed", responseStatus = 500)

		actAs(admin) { webhooks.replay(original) }

		val sender = FakeSender()
		drain(sender)

		assertEquals(
			listOf(target.url),
			sender.calls.map { it.url },
			"a button labelled 'send this again' must not quietly notify the other subscribers about an " +
				"hour-old change — that is what WebhookEvent.REPLAY_ONLY_JOB prevents",
		)
		assertTrue(
			deliveryRows.recent(other.id, 50).isEmpty(),
			"and it must not even open a delivery row for them",
		)
	}

	@Test
	fun `a replay onto a disabled subscription is refused rather than queued forever`() {
		val subscription = actAs(admin) { newSubscription().subscription }
		val delivery = seedDelivery(subscription.id, status = "failed")
		subscriptions.disable(subscription.id, "the endpoint went away")

		actAs(admin) {
			val refusal = assertFailsWith<BadRequestException>(
				"nothing drains a disabled subscription, so a replay would sit pending and look lost",
			) { webhooks.replay(delivery) }
			assertTrue(
				refusal.message?.contains("enable") == true,
				"and the refusal must say what to do about it; it said '${refusal.message}'",
			)
		}
	}

	@Test
	fun `replaying something that does not exist is a 404, not a queued nothing`() {
		actAs(admin) {
			assertFailsWith<NotFoundException> { webhooks.replay(UUID.randomUUID()) }
		}
	}

	// --- helpers -------------------------------------------------------------

	private fun newSubscription() = webhooks.create(
		url = "https://receiver.test/hook/${UUID.randomUUID()}",
		description = "test receiver",
		entities = listOf("ticket"),
		teamId = null,
	)

	/** A delivery row written directly, standing in for a pass that already happened. */
	private fun seedDelivery(
		subscriptionId: UUID,
		status: String = "delivered",
		responseStatus: Int? = 200,
	): UUID {
		val entityId = UUID.randomUUID()
		val payload = objectMapper.writeValueAsString(
			WebhookEvent(
				event = "ticket.updated",
				entity = "ticket",
				kind = "updated",
				id = entityId,
				teamId = null,
				projectId = null,
				origin = "kanso",
				at = OffsetDateTime.now(),
			),
		)
		val id = deliveryRows.open(
			subscriptionId = subscriptionId,
			jobId = null,
			entityType = OutboundEntityType.TICKET,
			entityId = entityId,
			payload = payload,
		)
		if (status == "delivered") {
			deliveryRows.markDelivered(id, responseStatus ?: 200)
		} else if (status == "failed") {
			deliveryRows.markFailed(id, responseStatus, "HTTP ${responseStatus ?: "none"}")
		}
		return id
	}

	private fun drain(sender: WebhookSender) {
		val handler: OutboundJobHandler = WebhookOutboundHandler(
			subscriptions = subscriptions,
			deliveries = deliveryRows,
			secrets = secrets,
			limit = WebhookRateLimit(perMinute = 1_000),
			sender = sender,
			tx = tx,
			objectMapper = objectMapper,
		)
		OutboundWorker(
			props = KansoProperties(),
			jobs = jobs,
			handlers = listOf(handler),
			tx = tx,
			scheduler = scheduler,
		).drain(handler)
	}

	private fun user(role: InstanceRole): User = users.createLocalUser(
		email = "webhook-perm-${UUID.randomUUID()}@kanso.test",
		displayName = "Test ${role.wire}",
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	/** Stands in for the auth filter, which has no servlet request here to run inside. */
	private fun <T> actAs(actor: User, block: () -> T): T {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.setContext(
			SecurityContextHolder.createEmptyContext().apply {
				authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
			},
		)
		return try {
			block()
		} finally {
			SecurityContextHolder.clearContext()
		}
	}
}

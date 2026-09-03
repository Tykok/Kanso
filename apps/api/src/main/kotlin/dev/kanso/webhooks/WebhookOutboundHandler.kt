package dev.kanso.webhooks

import dev.kanso.outbox.Completion
import dev.kanso.outbox.Destination
import dev.kanso.outbox.Failure
import dev.kanso.outbox.OutboundJob
import dev.kanso.outbox.OutboundJobHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Somebody's minute is full, or their server said 429. Nothing is wrong with the job. */
private class WebhookThrottled(val subscriptions: Int, val retryAfter: Duration) :
	RuntimeException("$subscriptions subscription(s) are at their rate limit")

/** At least one endpoint failed in a way another attempt could fix. */
private class WebhookDeliveriesFailed(val reasons: List<String>) :
	RuntimeException("${reasons.size} delivery(ies) failed: ${reasons.distinct().take(3).joinToString("; ")}")

/**
 * The webhook half of the outbox: what a job *means* when its destination is a subscriber.
 *
 * Draining, ordering, retrying, backing off and giving up are not here — they are the same
 * problem for every destination and live in `OutboundWorker`. This file is only the answer
 * to "what does delivering a change to a subscriber involve", which is what
 * `NotionOutboundHandler` is the other of.
 *
 * ## The heartbeat is not this file's job, and here is the proof
 *
 * A slow endpoint must not look like a dead worker, or `KAN-61`'s sweep would reclaim the
 * job under a POST that is still in the air and deliver it twice. Nothing in this file
 * beats, and nothing needs to: `OutboundWorker.drain` adds the job id to its `inFlight` set
 * *before* calling [handle] and removes it in a `finally`, and `OutboundWorker.beat` runs on
 * its own scheduler clock stamping every id in that set. So the beat covers the whole
 * duration of [handle] by construction, for any handler, including one that spends a hundred
 * seconds in `HttpClient.send`.
 *
 * That is worth stating because the obvious reading of "your handler must refresh the
 * heartbeat the same way Notion's does" is that a handler refreshes it, and Notion's does
 * not either — the beat was deliberately built central rather than per-handler, and
 * `OutboundWorker.inFlight` says why: "the question is about *this* process and the table
 * cannot answer it". A per-handler beat would also be a second thing to forget.
 *
 * ## Failure policy, in one place
 *
 * | what came back        | delivery row | the job                          |
 * |-----------------------|--------------|----------------------------------|
 * | 2xx                   | delivered    | fine                             |
 * | 429                   | untouched    | deferred, attempt refunded       |
 * | other 4xx             | failed       | fine — and the subscription stops|
 * | 5xx, timeout, no route| failed       | retried, attempt spent           |
 * | secret won't decrypt  | failed       | fine — and the subscription stops|
 *
 * The two "fine — and the subscription stops" rows are the ones worth arguing. A receiver
 * that answers 400 to a body will answer 400 to the same body eight more times, and a
 * secret this instance's key cannot open will never open; spending the job's attempts on
 * either is a queue kept busy proving something already known. Disabling is recoverable —
 * `WebhookSubscriptionRepository.enable` exists precisely so this is not a one-way door.
 */
@Component
class WebhookOutboundHandler(
	private val subscriptions: WebhookSubscriptionRepository,
	private val deliveries: WebhookDeliveryRepository,
	private val secrets: WebhookSecret,
	private val limit: WebhookRateLimit,
	private val sender: WebhookSender,
	private val tx: TransactionTemplate,
	private val objectMapper: ObjectMapper,
) : OutboundJobHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	override val destination = Destination.WEBHOOK

	/** One POST to make: who, which row to write it down in, and the exact bytes to sign. */
	private data class Target(
		val subscription: SigningSubscription,
		val deliveryId: UUID,
		val body: String,
		val event: String,
	)

	override suspend fun handle(job: OutboundJob): Completion {
		val targets = tx.execute { plan(job) }.orEmpty()
		if (targets.isEmpty()) return Completion.NOTHING

		var throttled = 0
		val failures = mutableListOf<String>()

		// Sequential, for `OutboundWorker.drain`'s reason one level up: the limiter is the
		// bottleneck, so running these together would only queue them inside it.
		for (target in targets) {
			// Asked before the row is touched, so a throttled delivery leaves its 'pending'
			// row exactly as it was — no attempt spent, nothing to explain in the log. Being
			// popular is not being broken.
			if (!limit.allow(target.subscription.id)) {
				throttled++
				continue
			}
			deliver(target)?.let { failures += it }
		}

		// Genuine failures outrank throttling, and the order is load-bearing. A deferral
		// *refunds* the attempt, so if a job carrying one broken endpoint and one busy one
		// deferred, it would never exhaust `max-attempts` and the broken endpoint would be
		// retried forever. Deferring only when every unfinished delivery is merely waiting
		// keeps the refund honest.
		if (failures.isNotEmpty()) throw WebhookDeliveriesFailed(failures)
		if (throttled > 0) throw WebhookThrottled(throttled, limit.retryAfter)

		// Nothing to write in the worker's transaction: every delivery was recorded in its
		// own, which is not an oversight but the point — see [deliver].
		return Completion.NOTHING
	}

	override fun classify(error: Exception): Failure = when (error) {
		is WebhookThrottled -> Failure.Defer("rate limited", error.retryAfter)

		// No key, so no subscription can ever be signed for. Retrying cannot install one.
		is WebhooksNotConfigured -> Failure.Fatal(error.message ?: "webhooks are not configured")

		is WebhookDeliveriesFailed -> Failure.Retry(error.message ?: "delivery failed")

		// The default the interface documents: anything unrecognised is worth another go,
		// because the cost of retrying something hopeless is a row in a failures list and
		// the cost of giving up on something transient is a delivery that never leaves.
		else -> Failure.Retry(error.message ?: error.javaClass.simpleName)
	}

	/**
	 * The queue has stopped trying. Whatever is still undelivered belongs to an endpoint
	 * that has now failed `max-attempts` times, so it stops costing every *other* entity's
	 * job the same eight attempts.
	 *
	 * Without this, one dead endpoint is not eight failed attempts — it is eight per queued
	 * entity, which for a bulk edit is thousands of pointless POSTs and thousands of rows.
	 * That is the shape of the failure the ticket is about, reached from the outbound side.
	 *
	 * Runs in the transaction that marks the job failed, which is what the hook is for.
	 */
	override fun onGivenUp(job: OutboundJob, error: Exception) {
		val reason = "Kanso gave up after repeated failures: ${error.message?.take(200) ?: "no response"}"
		deliveries.forJob(job.id)
			.filter { it.status != DeliveryStatus.DELIVERED }
			.map { it.subscriptionId }
			.distinct()
			.forEach { id ->
				if (subscriptions.disable(id, reason)) {
					log.warn("Disabled webhook subscription {} after repeated failures", id)
				}
			}
	}

	/**
	 * Who to call for this job, read inside one short transaction and with no HTTP in it.
	 *
	 * The union of two sets, and `V31` explains why it is a union rather than a choice: the
	 * automatic fan-out this job was queued for, plus any replay somebody attached to it.
	 * A replay rides a job that may also be carrying a fresh change — an enqueue for an
	 * already-queued entity coalesces — and honouring only one of the two would either
	 * swallow the replay or swallow the change.
	 *
	 * Each target carries its *own* body. The automatic ones send the job's payload; a
	 * replay sends the bytes stored on its own row, which is what makes it a replay of that
	 * event rather than of whatever the entity has since become.
	 */
	private fun plan(job: OutboundJob): List<Target> {
		val body = job.payload ?: return emptyList()
		val existing = deliveries.forJob(job.id)
		val targets = mutableListOf<Target>()

		// Replays first, because somebody is watching for these. Each reads its event name
		// out of its *own* stored body rather than the job's — a replay riding a coalesced
		// job would otherwise be labelled with the newer change it happens to share a row
		// with, and the header would then contradict the body it is attached to.
		for (row in existing) {
			if (row.replayOf == null || row.status == DeliveryStatus.DELIVERED) continue
			val subscription = subscriptions.signingById(row.subscriptionId) ?: continue
			val name = parse(row.payload)?.event ?: continue
			targets += Target(subscription, row.id, row.payload, name)
		}

		// A job a replay had to create owes the fan-out nothing — `WebhookEvent.REPLAY_ONLY_JOB`
		// carries why the distinction exists and what it prevents.
		if (body == WebhookEvent.REPLAY_ONLY_JOB) return targets

		val event = parse(body) ?: return targets

		// The automatic fan-out. An existing row is *reused* rather than replaced, which is
		// the ledger doing its job: a subscription already marked delivered for this job is
		// skipped, and one that failed keeps its row so `attempts` counts across retries
		// instead of resetting.
		val automatic = existing.filter { it.replayOf == null }.associateBy { it.subscriptionId }
		for (subscription in subscriptions.liveFor(job.entityType, event.teamId)) {
			val row = automatic[subscription.id]
			if (row?.status == DeliveryStatus.DELIVERED) continue
			val deliveryId = row?.id
				?: deliveries.open(subscription.id, job.id, job.entityType, job.entityId, body)
			targets += Target(subscription, deliveryId, body, event.event)
		}
		return targets
	}

	/**
	 * A body this build cannot read is not going to become readable, so it is logged and
	 * dropped rather than retried eight times.
	 */
	private fun parse(body: String): WebhookEvent? =
		runCatching { objectMapper.readValue(body, WebhookEvent::class.java) }.getOrElse {
			log.warn("Dropping a webhook payload this build cannot read: {}", it.message)
			null
		}

	/**
	 * One POST, and its outcome written down in **its own transaction**.
	 *
	 * Not in the `Completion` the interface offers, and this is the one place this handler
	 * departs from `NotionOutboundHandler`'s shape on purpose. A `Completion` runs only if
	 * [handle] returns normally — and [handle] throws whenever any endpoint in the fan-out
	 * failed, which is the common case with several subscribers. Deferring the record would
	 * therefore discard the *successes* of every pass that had one failure in it, and the
	 * retry would find an empty ledger and POST again to everybody who had already answered
	 * 200. Recording as it happens is what makes the shared retry safe.
	 *
	 * @return a sentence if this failure is worth another attempt, null otherwise.
	 */
	private suspend fun deliver(target: Target): String? {
		val secret = try {
			secrets.decrypt(target.subscription.secretCipher)
		} catch (e: WebhooksNotConfigured) {
			// No key at all is the job's problem, not this subscription's — let it become a
			// Fatal so the instance's configuration is what gets blamed.
			throw e
		} catch (e: Exception) {
			stop(target, "The signing secret could not be decrypted: ${e.message}")
			return null
		}

		val headers = mapOf(
			// Signed now, not when the job was queued. The timestamp inside the signature is
			// what makes a captured body un-replayable, so it has to be this attempt's — see
			// `WebhookSignature`, step 5.
			WebhookSignature.HEADER to WebhookSignature.header(target.body, secret, Instant.now()),
			// Stable across retries of this delivery, so a receiver can dedupe on it while
			// the signature above stays fresh. Two facts, two headers.
			WebhookSignature.DELIVERY_HEADER to target.deliveryId.toString(),
			WebhookSignature.EVENT_HEADER to target.event,
		)

		val response = try {
			sender.post(target.subscription.url, target.body, headers)
		} catch (e: WebhookUnreachable) {
			// Null status, because nothing answered — `V31` keeps that distinct from a 500.
			record(target) { deliveries.markFailed(target.deliveryId, null, e.message ?: "unreachable") }
			return e.message ?: "unreachable"
		}

		return when {
			response.status in 200..299 -> {
				record(target) { deliveries.markDelivered(target.deliveryId, response.status) }
				null
			}

			// The receiver asking us to slow down. Treated exactly like our own limiter
			// refusing: the row is left alone and the job defers, so their backpressure
			// costs the delivery nothing.
			response.status == TOO_MANY_REQUESTS -> throw WebhookThrottled(1, limit.retryAfter)

			// A refusal of the request itself. It will be refused identically next time.
			response.status in 400..499 -> {
				stop(
					target,
					"The endpoint refused the delivery with HTTP ${response.status}: ${response.body}",
					response.status,
				)
				null
			}

			else -> {
				val message = "HTTP ${response.status}"
				record(target) {
					deliveries.markFailed(target.deliveryId, response.status, "$message ${response.body}".trim())
				}
				message
			}
		}
	}

	/**
	 * A failure this subscription cannot be retried out of: written down, and switched off.
	 *
	 * [responseStatus] is passed through rather than hard-coded null, and the distinction is
	 * `V31`'s: a null there means *nothing answered*. A 4xx is an endpoint that is very much
	 * there and refusing, so recording it as null would tell a configurator to go looking for
	 * a network problem that does not exist. Null is right for the other caller — a secret
	 * this instance's key cannot open, where no request was ever made.
	 */
	private fun stop(target: Target, reason: String, responseStatus: Int? = null) {
		record(target) {
			deliveries.markFailed(target.deliveryId, responseStatus, reason)
			subscriptions.disable(target.subscription.id, reason)
		}
		log.warn("Disabled webhook subscription {}: {}", target.subscription.id, reason)
	}

	private fun record(target: Target, block: () -> Unit) {
		try {
			tx.executeWithoutResult { block() }
		} catch (e: Exception) {
			// The POST already happened. Losing the journal entry is bad; letting a failed
			// journal write look like a failed delivery would be worse, because the retry
			// would send it again.
			log.error("Could not record webhook delivery {}", target.deliveryId, e)
		}
	}

	private companion object {
		const val TOO_MANY_REQUESTS = 429
	}
}

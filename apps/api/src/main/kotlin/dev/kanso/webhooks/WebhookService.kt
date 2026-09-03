package dev.kanso.webhooks

import dev.kanso.auth.CurrentUser
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundOperation
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.NotFoundException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Who may point Kanso's data at somebody else's server, and the four gestures that do it.
 *
 * ## Every method is a configurator's, and the argument is not "it feels administrative"
 *
 * A subscription is an **instance-wide data egress rule**. Creating one sends every matching
 * change to an address of the creator's choosing, for as long as it exists, with no further
 * consent from anybody it concerns — including for teams the creator is not in, since a
 * subscription with a null `team_id` matches the whole instance. That is the same kind of
 * decision `TeamService.requireConfigurator` describes as "instance configuration, not daily
 * work: who reports to whom decides what everyone else sees", and it is a wider one.
 *
 * The dispensation that might have argued otherwise is `NotionPeople.link`'s — the import's
 * matching screen, which an ordinary importer may use because, as that file puts it,
 * changing a correspondence "needs no configurator rights". It does not reach here, and the
 * test is the one that file itself applies: linking changes who an existing row *is*, and
 * grants nobody any access they did not have. A webhook grants a new reader.
 *
 * **`V31`'s thin payload is what keeps that grant narrow rather than what excuses it.** A
 * subscriber learns that ticket `X` changed and must come back through `api_tokens` to learn
 * anything about it, where the seat and the scopes decide. So the worst a subscription leaks
 * on its own is the shape of activity — which ids exist, in which team, how often — and that
 * is still an instance-level disclosure, which is why the guard stays.
 *
 * Reading is guarded too, and deliberately: the delivery log is a list of every change that
 * happened and where it was sent, which is the same disclosure as receiving them.
 *
 * ## No update, only create and delete
 *
 * There is no `PUT`. `V31` explains what depends on that — the delivery log answers "who was
 * called" by joining to the subscription, so a mutable URL would make every historical row
 * report the address configured now. Re-pointing a webhook is delete and create, which also
 * forces a new secret onto the new receiver, which is the correct thing to happen when the
 * receiver changes.
 */
@Service
class WebhookService(
	private val currentUser: CurrentUser,
	private val subscriptions: WebhookSubscriptionRepository,
	private val deliveries: WebhookDeliveryRepository,
	private val secrets: WebhookSecret,
	private val jobs: OutboundJobRepository,
) {

	@Transactional(readOnly = true)
	fun list(): List<WebhookSubscription> {
		requireConfigurator()
		return subscriptions.all()
	}

	/**
	 * The only method that ever returns a plaintext secret, and it returns a type that
	 * exists to say so — `ApiTokenService.create`'s shape, for its reason.
	 *
	 * The secret is generated here and never accepted from the caller: an endpoint that
	 * could be handed one is an endpoint a fixture or a future caller could hand a weak one
	 * to, and there is no reason for that door to exist.
	 */
	@Transactional
	fun create(
		url: String,
		description: String,
		entities: Collection<String>?,
		teamId: UUID?,
	): NewWebhookSubscription {
		requireConfigurator()

		// Refused before a row is written, rather than storing a signing secret in
		// plaintext on an instance that has nowhere safe to put one.
		// `KansoProperties.Webhooks.signingKey` argues why this is a refusal and not a
		// default.
		if (!secrets.configured) throw WebhooksNotConfigured()

		val trimmedUrl = url.trim()
		val trimmedDescription = description.trim()

		// The same bounds `V31`'s CHECKs hold, refused here so the answer is a sentence
		// rather than a constraint violation flattened into a 409 — `ApiTokenService.create`
		// makes the same choice.
		if (trimmedDescription.isEmpty() || trimmedDescription.length > DESCRIPTION_MAX) {
			throw BadRequestException("A webhook's description must be between 1 and $DESCRIPTION_MAX characters")
		}
		if (!isDeliverable(trimmedUrl)) {
			throw BadRequestException(
				"A webhook URL must be https, or http on loopback for local development",
			)
		}

		val requested = WebhookEntities.requested(entities)
		val secret = secrets.generate()
		val subscription = subscriptions.insert(
			url = trimmedUrl,
			description = trimmedDescription,
			secretCipher = secrets.encrypt(secret),
			secretPrefix = WebhookSecret.prefixOf(secret),
			entities = requested,
			teamId = teamId,
			createdBy = currentUser.requireId(),
		)
		return NewWebhookSubscription(subscription, secret)
	}

	@Transactional
	fun delete(id: UUID) {
		requireConfigurator()
		if (!subscriptions.delete(id)) throw NotFoundException("No webhook subscription $id")
	}

	/** Back on after the receiver has been fixed — `WebhookSubscriptionRepository.enable` says why this exists. */
	@Transactional
	fun enable(id: UUID): WebhookSubscription {
		requireConfigurator()
		subscriptions.findById(id) ?: throw NotFoundException("No webhook subscription $id")
		subscriptions.enable(id)
		return subscriptions.findById(id) ?: throw NotFoundException("No webhook subscription $id")
	}

	@Transactional(readOnly = true)
	fun deliveries(subscriptionId: UUID, limit: Int): List<WebhookDelivery> {
		requireConfigurator()
		subscriptions.findById(subscriptionId) ?: throw NotFoundException("No webhook subscription $subscriptionId")
		return deliveries.recent(subscriptionId, limit.coerceIn(1, DELIVERIES_MAX))
	}

	/**
	 * Send that delivery again.
	 *
	 * **A new row, and it goes through the queue.** Both halves are decisions `V31` argues:
	 * a delivery row records one HTTP request that actually happened, so folding a replay
	 * into it would overwrite the evidence of the failure that prompted it — and a replay
	 * that POSTed straight from this method would be a second sender with no retry, no
	 * backoff and no rate limit, which is exactly the forking the ticket forbids.
	 *
	 * So this writes a `pending` row pointing at the subscription's original bytes, attaches
	 * it to a queue job for the same entity, and returns. The next drain signs it with a
	 * fresh timestamp and delivers it under a new `X-Kanso-Delivery` — new on purpose, since
	 * a receiver deduping a replay away would make this button do nothing.
	 *
	 * @return the id of the new delivery, so a caller can watch it.
	 */
	@Transactional
	fun replay(deliveryId: UUID): UUID {
		requireConfigurator()
		val original = deliveries.findById(deliveryId)
			?: throw NotFoundException("No webhook delivery $deliveryId")

		// Refused rather than queued against a subscription nothing drains: `liveFor` and
		// `signingById` both skip disabled rows, so a replay attached to one would sit
		// 'pending' forever and look like a delivery that was lost. Re-enable, then replay.
		val subscription = subscriptions.findById(original.subscriptionId)
			?: throw NotFoundException("No webhook subscription ${original.subscriptionId}")
		if (!subscription.live) {
			throw BadRequestException(
				"This subscription is disabled (${subscription.disabledReason}) — enable it before replaying",
			)
		}

		// `enqueueQuiet` and not `enqueue`: the payload here is an old event, and letting it
		// overwrite a genuinely pending change's payload would send that change out
		// describing something already superseded. The method carries the argument.
		//
		// `UPSERT` because `outbound_jobs.operation` is a mirror's vocabulary and this
		// handler never reads it — the bytes a subscriber receives come from the delivery
		// row. It is here because the column is NOT NULL.
		val jobId = jobs.enqueueQuiet(
			destination = Destination.WEBHOOK,
			entityType = original.entityType,
			entityId = original.entityId,
			operation = OutboundOperation.UPSERT,
			payload = WebhookEvent.REPLAY_ONLY_JOB,
		)

		return deliveries.open(
			subscriptionId = original.subscriptionId,
			jobId = jobId,
			entityType = original.entityType,
			entityId = original.entityId,
			payload = original.payload,
			replayOf = original.id,
			requestedBy = currentUser.requireId(),
		)
	}

	/**
	 * The same rule `V31`'s `webhook_subscriptions_url_chk` holds, so the answer is a
	 * sentence. Loopback over plain http is admitted for local development and nothing else
	 * is; the CHECK is the backstop, this is the mechanism.
	 */
	private fun isDeliverable(url: String): Boolean =
		url.startsWith("https://") ||
			LOOPBACK_PREFIXES.any { url.startsWith("http://$it:") || url == "http://$it/" }

	private fun requireConfigurator() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the owner or an admin can manage outbound webhooks")
		}
	}

	private companion object {
		const val DESCRIPTION_MAX = 80
		const val DELIVERIES_MAX = 200
		val LOOPBACK_PREFIXES = listOf("localhost", "127.0.0.1")
	}
}

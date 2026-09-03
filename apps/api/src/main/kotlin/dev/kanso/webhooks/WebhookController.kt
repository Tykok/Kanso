package dev.kanso.webhooks

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A subscription as the settings screen lists it.
 *
 * There is no `secret` field and [WebhookSubscription] has no such property to map from —
 * see that type for why the plaintext is absent from the type system rather than merely
 * absent from this constructor call.
 */
data class WebhookResponse(
	val id: UUID,
	val url: String,
	val description: String,
	val entities: List<String>,
	/** Null means the whole instance. */
	val teamId: UUID?,
	/** `kanso_whsec_AbCdEf` — enough to recognise, not enough to verify with. */
	val secretPrefix: String,
	/** Null while healthy. Anything else is why Kanso stopped, and the row is not being delivered to. */
	val disabledReason: String?,
	val createdAt: OffsetDateTime,
) {
	companion object {
		fun of(subscription: WebhookSubscription) = WebhookResponse(
			id = subscription.id,
			url = subscription.url,
			description = subscription.description,
			entities = subscription.entities.map { it.wire }.sorted(),
			teamId = subscription.teamId,
			secretPrefix = subscription.secretPrefix,
			disabledReason = subscription.disabledReason,
			createdAt = subscription.createdAt,
		)
	}
}

/**
 * The response to a creation, and the only one that ever carries a secret.
 *
 * [warning] is prose in the payload, which `NewApiTokenResponse` argues for at length and
 * which applies here more strongly: the person setting a webhook up is usually holding two
 * consoles, and the secret has to be pasted into the *other* one before this response
 * scrolls away.
 */
data class NewWebhookResponse(
	val subscription: WebhookResponse,
	/** Shown once. Kanso keeps it encrypted and this endpoint is the only one that prints it. */
	val secret: String,
	val warning: String,
) {
	companion object {
		const val SHOWN_ONCE =
			"Copy this signing secret now — it is shown once. Kanso keeps it encrypted so it can " +
				"sign with it, and no endpoint will print it again. If you lose it, delete this " +
				"webhook and create another."

		fun of(created: NewWebhookSubscription) = NewWebhookResponse(
			subscription = WebhookResponse.of(created.subscription),
			secret = created.secret,
			warning = SHOWN_ONCE,
		)
	}
}

/**
 * One row of the journal.
 *
 * No `payload`. The bytes are on the row — a replay needs them — and printing them here
 * would put every change's identifiers into a screen that already tells you which entity
 * each row is about. `V31`'s thin body means there is nothing in it a reader of this list
 * does not already have.
 */
data class WebhookDeliveryResponse(
	val id: UUID,
	val entityType: String,
	val entityId: UUID,
	val status: String,
	val attempts: Int,
	/** Null when nothing answered at all — a timeout, a refused connection. Not the same as a 500. */
	val responseStatus: Int?,
	val error: String?,
	/** Set when this row is a replay of another, so the screen can say so. */
	val replayOf: UUID?,
	val createdAt: OffsetDateTime,
	val deliveredAt: OffsetDateTime?,
) {
	companion object {
		fun of(delivery: WebhookDelivery) = WebhookDeliveryResponse(
			id = delivery.id,
			entityType = delivery.entityType.wire,
			entityId = delivery.entityId,
			status = delivery.status.wire,
			attempts = delivery.attempts,
			responseStatus = delivery.responseStatus,
			error = delivery.error,
			replayOf = delivery.replayOf,
			createdAt = delivery.createdAt,
			deliveredAt = delivery.deliveredAt,
		)
	}
}

/**
 * What a configurator asked for. `entities` is not defaulted, for
 * `CreateApiTokenRequest`'s reason: a subscription whose filter was decided by a missing
 * field is one nobody chose, and `WebhookEntities.requested` refuses the empty case with a
 * sentence naming the four that exist.
 */
data class CreateWebhookRequest(
	val url: String,
	val description: String,
	val entities: List<String>?,
	/** Null means every team, which is the wider grant — so it is stated, never inferred. */
	val teamId: UUID?,
)

/** What a replay returns: the id of the *new* delivery, so a caller can watch it. */
data class ReplayResponse(val deliveryId: UUID)

/**
 * `/api/webhooks` and not `/api/me/webhooks`, which is the difference from
 * `ApiTokenController` and the whole of what these two surfaces disagree about.
 *
 * A token is a fact about the person asking, so its path says `me` and its service takes no
 * user id. A subscription is a fact about the *instance* — it outlives the admin who created
 * it (`V31`'s `created_by` is `SET NULL`, not `CASCADE`) and it can carry changes from teams
 * that admin is not in. Putting it under `/api/me` would suggest an ownership that does not
 * exist and a scoping that would be wrong to enforce.
 *
 * Every route is a configurator's, reads included. `WebhookService` carries the argument.
 */
@RestController
@RequestMapping("/api/webhooks")
class WebhookController(private val webhooks: WebhookService) {

	@GetMapping
	fun list(): List<WebhookResponse> = webhooks.list().map(WebhookResponse::of)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@RequestBody request: CreateWebhookRequest): NewWebhookResponse =
		NewWebhookResponse.of(
			webhooks.create(request.url, request.description, request.entities, request.teamId),
		)

	/** Takes the delivery log with it, by `V31`'s cascade — the journal of a gone endpoint answers nothing. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) {
		webhooks.delete(id)
	}

	/**
	 * Re-arms a subscription Kanso switched off. A `POST` and not a `PUT`, because this is
	 * not an edit of the row's configuration — there is no such endpoint, and `V31` says
	 * what depends on there not being one.
	 */
	@PostMapping("/{id}/enable")
	fun enable(@PathVariable id: UUID): WebhookResponse = WebhookResponse.of(webhooks.enable(id))

	@GetMapping("/{id}/deliveries")
	fun deliveries(
		@PathVariable id: UUID,
		@RequestParam(defaultValue = "50") limit: Int,
	): List<WebhookDeliveryResponse> = webhooks.deliveries(id, limit).map(WebhookDeliveryResponse::of)

	/**
	 * Send that delivery again.
	 *
	 * `202` rather than `200`: this returns as soon as the replay is queued, and the POST
	 * happens on the next drain. Saying `200` would claim the receiver has been called, which
	 * is exactly the thing the caller is going to check the journal for.
	 */
	@PostMapping("/deliveries/{deliveryId}/replay")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun replay(@PathVariable deliveryId: UUID): ReplayResponse = ReplayResponse(webhooks.replay(deliveryId))
}

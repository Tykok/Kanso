package dev.kanso.webhooks

import dev.kanso.outbox.OutboundEntityType
import dev.kanso.service.BadRequestException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A subscription as anybody is ever allowed to see it again: everything about it except the
 * one thing it holds.
 *
 * There is no `secret` field and no nullable one standing in for it — [ApiToken]'s trick,
 * for [ApiToken]'s reason. "The secret is shown once" is then a fact about the type system
 * rather than a rule every future endpoint has to remember, and a listing cannot serialise
 * a secret it has no field for.
 *
 * @see dev.kanso.tokens.ApiToken
 */
data class WebhookSubscription(
	val id: UUID,
	val url: String,
	val description: String,
	val entities: Set<OutboundEntityType>,
	val teamId: UUID?,
	val secretPrefix: String,
	val disabledReason: String?,
	val createdAt: OffsetDateTime,
) {
	/** Null `disabled_reason` is the healthy state — `V31` argues why it is one column. */
	val live: Boolean get() = disabledReason == null
}

/**
 * The one and only time the secret exists outside the subscriber's own hands.
 *
 * Returned by `WebhookService.create` and by nothing else. Unlike a PAT there *is* a column
 * it could be recovered from — the signer needs the bytes back — so this type is carrying
 * more weight here than [dev.kanso.tokens.NewApiToken] does: the guarantee is a policy
 * decision rather than a physical impossibility, and the way it is kept is that no read
 * path in this package ever decrypts except [SigningSubscription]'s, which no controller
 * can reach.
 */
data class NewWebhookSubscription(val subscription: WebhookSubscription, val secret: String)

/**
 * A subscription as the *handler* needs it: where to POST, and what to sign with.
 *
 * Distinct from [WebhookSubscription] for the reason `AuthenticatedToken` is distinct from
 * `ApiToken` — the two carry different fields on purpose. This one has the ciphertext a
 * signer must decrypt and none of the `description`/`createdAt` a screen draws, so neither
 * type is a superset standing in for both and no response object has a secret-shaped field
 * on it at all.
 *
 * The ciphertext and not the plaintext, so the decryption happens at the moment of signing
 * and the key material's lifetime is one function call rather than the lifetime of whatever
 * list this was read into.
 */
data class SigningSubscription(
	val id: UUID,
	val url: String,
	val secretCipher: String,
)

/** Where one delivery got to. Closed here and again by `webhook_deliveries_status_chk`. */
enum class DeliveryStatus(val wire: String) {
	/** Written before the POST, so the ledger cannot miss a request that was made. */
	PENDING("pending"),
	DELIVERED("delivered"),

	/**
	 * The last attempt failed. Not final on its own — the job may retry, and the row moves
	 * back through [PENDING] when it does. What makes a failure final is the *job* giving
	 * up, which `V31` explains is shared across the fan-out.
	 */
	FAILED("failed");

	companion object {
		fun from(raw: String): DeliveryStatus = entries.firstOrNull { it.wire == raw }
			?: throw IllegalArgumentException("Unknown webhook delivery status '$raw'")
	}
}

/**
 * One row of the journal: who was called, with what, how it went, and how many tries it
 * took. `V31` explains why this table is also the ledger the retry reads.
 */
data class WebhookDelivery(
	val id: UUID,
	val subscriptionId: UUID,
	val jobId: Long?,
	val entityType: OutboundEntityType,
	val entityId: UUID,
	val payload: String,
	val status: DeliveryStatus,
	val attempts: Int,
	val responseStatus: Int?,
	val error: String?,
	val replayOf: UUID?,
	val createdAt: OffsetDateTime,
	val deliveredAt: OffsetDateTime?,
)

/**
 * What a configurator asked to be notified about, checked before a row is written.
 *
 * Rejects rather than filters, which is `ApiTokenScopes.requested`'s decision and its
 * reason: quietly dropping a noun Kanso does not know would hand back a subscription that
 * is narrower than the one that was asked for, and the failure surfaces much later as "we
 * never get any webhooks" with nothing to point at. `V31`'s CHECK would refuse the row
 * anyway — this is the same refusal with a sentence attached.
 *
 * The vocabulary is `OutboundEntityType` and not a new one, for `V31`'s reason: a second
 * list of nouns would be a second answer to what kinds of thing Kanso has.
 */
object WebhookEntities {

	fun requested(raw: Collection<String>?): Set<OutboundEntityType> {
		val asked = raw.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
		val known = OutboundEntityType.entries.associateBy { it.wire }
		if (asked.isEmpty()) {
			throw BadRequestException(
				"A subscription needs at least one entity: ${known.keys.joinToString(", ")}",
			)
		}
		val unknown = asked - known.keys
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"Unknown entity ${unknown.sorted().joinToString(", ")} — " +
					"Kanso sends ${known.keys.joinToString(", ")}",
			)
		}
		return asked.map { known.getValue(it) }.toSet()
	}
}

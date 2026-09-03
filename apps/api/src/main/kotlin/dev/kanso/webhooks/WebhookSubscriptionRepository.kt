package dev.kanso.webhooks

import dev.kanso.outbox.OutboundEntityType
// Star imports, as `InvitationService` and `DocFolderRepository` use: the null predicates
// below (`isNull`, `isNotNull`) are the reason both of those files do too.
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Exposed's view of `webhook_subscriptions`, in the slice's own package for the reason
 * `ApiTokens` gives — several branches are cut from one commit, and appending to
 * `db/Tables.kt` is one conflict per branch for nothing.
 *
 * `entities` is a real `TEXT[]`, for `ApiTokens.scopes`' reason: a comma-joined string
 * cannot be constrained, and `V31`'s CHECK — the closed vocabulary, in the database — is
 * the thing this column exists to be able to have.
 */
object WebhookSubscriptions : Table("webhook_subscriptions") {
	val id = javaUUID("id")
	val url = text("url")
	val description = text("description")
	val secretCipher = text("secret_cipher")
	val secretPrefix = text("secret_prefix")
	val entities = array<String>("entities", TextColumnType())
	val teamId = javaUUID("team_id").nullable()
	val createdBy = javaUUID("created_by").nullable()
	val disabledReason = text("disabled_reason").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

@Repository
class WebhookSubscriptionRepository(private val jdbc: JdbcClient) {

	/**
	 * "Is anybody listening at all" — asked once per entity change, and the reason this one
	 * method is not Exposed.
	 *
	 * [WebhookFanout] calls it from `EventPublisher.publish`, which runs on whatever
	 * transaction the mutating service happens to have open — and sometimes on none, since
	 * that method's own code branches on exactly that. Exposed refuses a query without a
	 * transaction; Spring's `JdbcClient` uses the transaction-bound connection when there
	 * is one and autocommit when there is not, which is precisely the behaviour the caller
	 * needs and cannot promise.
	 *
	 * It exists so that an instance with no webhooks pays nothing for the feature. Without
	 * it, every ticket edit anywhere would write an `outbound_jobs` row that nothing would
	 * ever want — a queue filling up on behalf of nobody, which is a slower version of the
	 * failure the ticket is about. With it the cost is one probe of a partial index over a
	 * table that holds one instance's webhook configuration.
	 */
	fun anyLive(): Boolean = jdbc
		.sql("SELECT EXISTS (SELECT 1 FROM webhook_subscriptions WHERE disabled_reason IS NULL)")
		.query(Boolean::class.java)
		.single()

	/**
	 * Every live subscription that could want a change to this entity, in the scope it
	 * happened in.
	 *
	 * The team predicate is SQL and rides `webhook_subscriptions_live_idx`; the entity
	 * filter is Kotlin, over a `Set`. That split is `V31`'s ("the database closes the
	 * vocabulary; the set-ness is Kotlin's") and it costs nothing here — the rows this
	 * narrows are one instance's webhook configuration, a handful, not a table.
	 *
	 * **A null [teamId] matches only the instance-wide subscriptions**, and that is a
	 * decision rather than a fallthrough. A teamless ticket exists (`V20`), and a
	 * subscription scoped to a team asked for *that team's* changes — handing it something
	 * with no team because the comparison was null-friendly would be a filter that leaks
	 * in exactly the case nobody tested.
	 */
	fun liveFor(entityType: OutboundEntityType, teamId: UUID?): List<SigningSubscription> =
		WebhookSubscriptions
			.select(
				WebhookSubscriptions.id,
				WebhookSubscriptions.url,
				WebhookSubscriptions.secretCipher,
				WebhookSubscriptions.entities,
			)
			.where {
				val healthy = WebhookSubscriptions.disabledReason.isNull()
				if (teamId == null) {
					healthy and WebhookSubscriptions.teamId.isNull()
				} else {
					healthy and (
						WebhookSubscriptions.teamId.isNull() or (WebhookSubscriptions.teamId eq teamId)
						)
				}
			}
			.filter { entityType.wire in it[WebhookSubscriptions.entities] }
			.map {
				SigningSubscription(
					id = it[WebhookSubscriptions.id],
					url = it[WebhookSubscriptions.url],
					secretCipher = it[WebhookSubscriptions.secretCipher],
				)
			}

	/** The settings screen's list. Newest first, because the one somebody wants is the one they just made. */
	fun all(): List<WebhookSubscription> =
		WebhookSubscriptions.selectAll()
			.orderBy(WebhookSubscriptions.createdAt to SortOrder.DESC, WebhookSubscriptions.id to SortOrder.DESC)
			.map(::read)

	fun findById(id: UUID): WebhookSubscription? =
		WebhookSubscriptions.selectAll().where { WebhookSubscriptions.id eq id }.singleOrNull()?.let(::read)

	/** For the replay, which has to sign with the same secret the original delivery used. */
	fun signingById(id: UUID): SigningSubscription? =
		WebhookSubscriptions
			.select(WebhookSubscriptions.id, WebhookSubscriptions.url, WebhookSubscriptions.secretCipher)
			.where { (WebhookSubscriptions.id eq id) and WebhookSubscriptions.disabledReason.isNull() }
			.singleOrNull()
			?.let {
				SigningSubscription(
					id = it[WebhookSubscriptions.id],
					url = it[WebhookSubscriptions.url],
					secretCipher = it[WebhookSubscriptions.secretCipher],
				)
			}

	fun insert(
		url: String,
		description: String,
		secretCipher: String,
		secretPrefix: String,
		entities: Set<OutboundEntityType>,
		teamId: UUID?,
		createdBy: UUID?,
	): WebhookSubscription {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		WebhookSubscriptions.insert {
			it[WebhookSubscriptions.id] = id
			it[WebhookSubscriptions.url] = url
			it[WebhookSubscriptions.description] = description
			it[WebhookSubscriptions.secretCipher] = secretCipher
			it[WebhookSubscriptions.secretPrefix] = secretPrefix
			// Sorted, so two subscriptions asking for the same changes hold the same array
			// and a reader comparing two rows by eye is comparing them by content —
			// `ApiTokenRepository.insert` makes the same choice for the same reason.
			it[WebhookSubscriptions.entities] = entities.map { entity -> entity.wire }.sorted()
			it[WebhookSubscriptions.teamId] = teamId
			it[WebhookSubscriptions.createdBy] = createdBy
			it[WebhookSubscriptions.createdAt] = now
		}
		return WebhookSubscription(
			id = id,
			url = url,
			description = description,
			entities = entities,
			teamId = teamId,
			secretPrefix = secretPrefix,
			disabledReason = null,
			createdAt = now,
		)
	}

	/**
	 * Stops delivering, and says why on the row.
	 *
	 * Not a delete: a subscription Kanso gave up on is the one a configurator most needs to
	 * see, and deleting it would take the delivery log with it (`V31`'s cascade) at exactly
	 * the moment somebody wants to read the failures. `V27`'s "revoking deletes the row"
	 * does not transfer, because that argument is about a *credential* whose row is what a
	 * filter authenticates; nothing authenticates against this one.
	 */
	fun disable(id: UUID, reason: String): Boolean =
		WebhookSubscriptions.update({
			(WebhookSubscriptions.id eq id) and WebhookSubscriptions.disabledReason.isNull()
		}) {
			it[disabledReason] = reason.take(500)
		} > 0

	/**
	 * Back on, after whatever went wrong has been fixed.
	 *
	 * This endpoint exists because [disable] is automatic, and an automatic off switch with
	 * no manual on switch is a feature that quietly deletes itself: a ten-minute outage
	 * would cost a subscription that only a `DELETE` and a re-registration could recover,
	 * and re-registering means a new secret on the receiver for no reason.
	 *
	 * Clears the reason rather than keeping it beside a flag, because `V31` made those the
	 * same column so the two could never disagree about whether this row is being delivered
	 * to. The history of why it was off lives in the delivery log, which is still there.
	 */
	fun enable(id: UUID): Boolean =
		WebhookSubscriptions.update({
			(WebhookSubscriptions.id eq id) and WebhookSubscriptions.disabledReason.isNotNull()
		}) {
			it[disabledReason] = null
		} > 0

	/** Deleting takes the journal with it, by `V31`'s cascade — the log of a gone endpoint answers nothing. */
	fun delete(id: UUID): Boolean =
		WebhookSubscriptions.deleteWhere { WebhookSubscriptions.id eq id } > 0

	private fun read(row: ResultRow) = WebhookSubscription(
		id = row[WebhookSubscriptions.id],
		url = row[WebhookSubscriptions.url],
		description = row[WebhookSubscriptions.description],
		entities = row[WebhookSubscriptions.entities].mapNotNull { wire ->
			OutboundEntityType.entries.firstOrNull { it.wire == wire }
		}.toSet(),
		teamId = row[WebhookSubscriptions.teamId],
		secretPrefix = row[WebhookSubscriptions.secretPrefix],
		disabledReason = row[WebhookSubscriptions.disabledReason],
		createdAt = row[WebhookSubscriptions.createdAt],
	)
}

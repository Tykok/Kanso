package dev.kanso.service

import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoAgentUser
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.User
import dev.kanso.repo.ActivityRecord
import dev.kanso.repo.ActivityRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One entry of the log, with the person resolved. [actor] is null for a row whose
 * author has since been deleted — `actor_id` is `ON DELETE SET NULL`, because losing
 * the account must not lose the history.
 *
 * [payload] is the jsonb document as it was written, plus exactly one resolved key: `ref`,
 * the ticket's `KAN-142`. It is here rather than beside [actor] because `ref` is the name
 * a *sentence* reads the row under — see [ActivityService.refsFor] for why it is resolved
 * at all, and why it is resolved here rather than written seven times.
 */
data class ActivityRow(
	val id: UUID,
	val entity: ActivityEntity,
	val entityId: UUID,
	val actor: User?,
	val kind: ActivityKind,
	val payload: Map<String, Any?>,
	val createdAt: OffsetDateTime,
)

/**
 * What happened, written where it happened.
 *
 * [record] is called by the services that already publish events, at the same point in
 * the same transaction as the change — never by a listener on `pg_notify`.
 * `EventPublisher` fires on `afterCommit` and its payload is capped at 8000 bytes: a
 * receiver that was not listening never learns, which would make the log lossy in
 * exactly the case it exists to explain. The consequence for tests is the useful one:
 * the row is there to be asserted while the transaction is still open.
 *
 * Reads are open, as `architecture.md` states. There is no [TicketAccess] call here and
 * no actor parameter on [forEntity] — but an activity row can name a private ticket, so
 * the public surfaces (slice F) read none of it.
 */
@Service
class ActivityService(
	private val activity: ActivityRepository,
	private val users: UserRepository,
	/** Read only by [refsFor], and only for a ticket's feed — never on the write path. */
	private val tickets: TicketRepository,
	private val teams: TeamRepository,
	private val json: ObjectMapper,
	/** Read for provenance only — see [clientTyping]. Never to decide anything. */
	private val currentUser: CurrentUser,
) {

	/**
	 * [payload] carries the before and after of one scalar and nothing else. The shared
	 * mapper omits nulls, so a bound that was unset comes back as an absent key rather
	 * than an explicit null — the same fact either way for anything drawing a feed.
	 */
	@Transactional
	fun record(
		entity: ActivityEntity,
		entityId: UUID,
		actorId: UUID?,
		kind: ActivityKind,
		payload: Map<String, Any?> = emptyMap(),
	) {
		activity.insert(
			id = UUID.randomUUID(),
			entity = entity,
			entityId = entityId,
			actorId = actorId,
			kind = kind,
			payload = json.writeValueAsString(payload),
			createdAt = OffsetDateTime.now(),
			viaClientId = clientTyping(actorId),
		)
	}

	/**
	 * Which application typed this row, read off the request rather than passed in.
	 *
	 * Every caller of [record] is a service in the middle of a write it was asked to make,
	 * and none of them knows or should know how the asking arrived — `TicketService.create`
	 * is the same method whether a person pressed `c` or an agent called `kanso_create_ticket`.
	 * Threading a client id through forty call sites would put that knowledge in all of
	 * them; reading it here puts it in one, and it is the one place that already exists for
	 * "who is this request", so a second derivation cannot drift from it.
	 *
	 * The public `client_id`, which is what [dev.kanso.mcp.McpBearerFilter] puts on the
	 * principal and what `V19__activity_via_client_id_target.sql` made a legal foreign-key
	 * target so this line could be written at all.
	 *
	 * Attached only when the row's actor **is** the grant's owner. Nothing writes such a
	 * row today, and the guard is cheap: a change recorded against somebody else while an
	 * agent's request happens to be on the thread would name a client that did not type it,
	 * which is worse than naming none — provenance that is sometimes wrong is provenance
	 * nobody can use.
	 */
	private fun clientTyping(actorId: UUID?): String? =
		(currentUser.principalOrNull() as? KansoAgentUser)
			?.takeIf { it.kansoUserId == actorId }
			?.clientId

	/** Newest first: every reader of this list draws a feed, and a feed starts at the top. */
	@Transactional(readOnly = true)
	fun forEntity(entity: ActivityEntity, entityId: UUID, limit: Int = 50): List<ActivityRow> {
		val records = activity.forEntity(entity, entityId, limit)
		if (records.isEmpty()) return emptyList()
		// One query for the whole page's actors instead of one per row — the same shape
		// `TicketService.decorate` uses, and a feed repeats the same few people.
		val actors = users.findAllById(records.mapNotNull { it.actorId }.toSet()).associateBy { it.id }
		val refs = refsFor(records)
		return records.map { record ->
			ActivityRow(
				id = record.id,
				entity = record.entity,
				entityId = record.entityId,
				actor = record.actorId?.let { actors[it] },
				kind = record.kind,
				// The resolved `ref` last, so it wins over anything a writer ever puts under
				// that key: the point of resolving here is that the name is the *current* one.
				payload = decode(record.payload) + refsKey(refs[record.entityId]),
				createdAt = record.createdAt,
			)
		}
	}

	/**
	 * `KAN-142` for every ticket a page of the feed names — the key `payload.ref` was always
	 * read under, supplied on read because nothing was writing it.
	 *
	 * **Resolved and not stored, like every other derived value in this schema.** It is the
	 * argument `TicketDetail.branchName` already makes in those words, and both of the
	 * columns behind an identifier move: `TeamService.update` takes a new `key`, and crossing
	 * teams takes a fresh `number` from the destination's counter, because `UNIQUE (team_id,
	 * number)` leaves no choice. A `ref` frozen at write time would therefore make a ticket's
	 * own feed disagree with the ticket's own header — every line above the move naming
	 * something the reader can no longer find. Resolving here also names the rows already in
	 * the table, which is what makes this the fix with no migration behind it: `KAN-81`'s
	 * precedent is not to recompute history, and read-side resolution never has to.
	 *
	 * Two statements for the whole page and never one per row — the shape the `actors` line
	 * above already uses, and the distinction `KAN-81` settled by measuring rather than by
	 * assuming. Zero statements for a project, team, doc or account feed: none of those rows
	 * is about a ticket, and `activitySentence`'s branches for them consult no `ref`.
	 *
	 * Absent, not blank, for a draft: a ticket no team has claimed has no identifier at all,
	 * and the feed's own fallback for that is the sentence "created a ticket". The same
	 * absence covers a row whose ticket has since been destroyed — the history outlives it.
	 */
	private fun refsFor(records: List<ActivityRecord>): Map<UUID, String> {
		val ids = records.filter { it.entity == ActivityEntity.TICKET }.map { it.entityId }.toSet()
		if (ids.isEmpty()) return emptyMap()
		val found = tickets.findAllById(ids)
		val keys = teams.findAllById(found.mapNotNull { it.teamId }.toSet()).associate { it.id to it.key }
		return found.mapNotNull { ticket ->
			ticketIdentifier(ticket.teamId?.let { keys[it] }, ticket.number)?.let { ticket.id to it }
		}.toMap()
	}

	/** One key or none, so an unresolvable ticket leaves the payload exactly as it was written. */
	private fun refsKey(ref: String?): Map<String, Any?> =
		ref?.let { mapOf("ref" to it) } ?: emptyMap()

	/** Whatever [record] was handed, back out again. jsonb objects have string keys. */
	@Suppress("UNCHECKED_CAST")
	private fun decode(payload: String): Map<String, Any?> =
		json.readValue(payload, Map::class.java) as Map<String, Any?>
}

package dev.kanso.realtime

import java.time.OffsetDateTime
import java.util.UUID

enum class ChangeKind { CREATED, UPDATED, DELETED }

/**
 * What one client tells the others. Deliberately thin: the id and enough scope to
 * decide whether a given view cares. Receivers refetch or patch their own cache —
 * shipping whole entities through the bus would mean two sources of truth for
 * shape and a payload that outgrows `pg_notify`'s 8000-byte limit.
 */
data class KansoEvent(
	val entity: String,
	val kind: ChangeKind,
	val id: UUID,
	val teamId: UUID? = null,
	val projectId: UUID? = null,
	/**
	 * Which block of a document moved, for a `docs` event and nothing else.
	 *
	 * The one field on this class that is not scope, and it earns the exception: two
	 * people on one page is the case `KAN-25` exists for, so a receiver has to be able to
	 * tell "the block I am typing in" from "a block somebody else is typing in" before it
	 * decides whether to repaint. Without it every keystroke committed anywhere on the
	 * page would be indistinguishable from a keystroke committed *in this caret*, and the
	 * repaint would land under the person's own hands.
	 *
	 * Still not the block's content — that stays a refetch, for the reason the class doc
	 * gives. This is an identity, which is all the rest of the payload is.
	 */
	val blockId: UUID? = null,
	/**
	 * Who wrote the row, carried for one case: a ticket with no team.
	 *
	 * A draft is the one entity on this bus whose *existence* is private.
	 * `TicketAccess.requireReadable` answers 404 rather than 403 for one — a 403 tells
	 * somebody walking UUIDs that the row is there, which is the single thing a draft must
	 * not say — and [destinations] used to say it anyway, to every open tab in the instance,
	 * with the id and the moment it was touched. Scope, and the same kind of scope as
	 * [teamId]; it is not carried for any other reason and no receiver reads it.
	 */
	val createdBy: UUID? = null,
	/** "kanso" for a user action, "notion" when the inbound poller applied it. */
	val origin: String = "kanso",
	val at: OffsetDateTime = OffsetDateTime.now(),
) {
	/**
	 * Broadcast destinations. Most rows land on the entity topic and team-scoped views get a
	 * narrower one beside it; presence and a team-less ticket get the narrow one instead.
	 */
	fun destinations(): List<String> = buildList {
		// Presence is the one entity with no wide topic, and the exception is the point of
		// it. "Who is reading page X" interests the people reading page X and nobody else,
		// so a `/topic/doc_viewers` beside this would deliver every join and every leave in
		// the instance to every open tab, to be discarded. The narrow destination is also
		// what a client *subscribes* to in order to be present at all — see `DocPresence` —
		// which is why the string is computed here, once, and not spelled out on both sides
		// of the socket.
		if (entity == VIEWERS) {
			add(viewersTopic(id))
			return@buildList
		}
		// A ticket with no team is a draft, and the wide topic is exactly what it may not
		// have: every browser in the instance is subscribed to `/topic/tickets`, so a row
		// `GET /api/tickets/{id}` answers 404 for was announced to all of them. Its author
		// is the one person the HTTP layer would show it to, so they are the one person told
		// — and when nobody can be named, nobody is told. Fail-closed on purpose: a draft
		// whose author is unknown here is a draft this bus has nothing safe to say about,
		// and a tab that misses the update refetches on its next navigation. Only
		// `tickets`, because a team-less *project* is transverse by design and instance-wide
		// on purpose — see `ProjectService`.
		if (entity == TICKETS && teamId == null) {
			createdBy?.let { add(authorTopic(it, entity)) }
			return@buildList
		}
		add("/topic/$entity")
		teamId?.let { add("/topic/teams/$it/$entity") }
	}

	companion object {
		/** The [entity] presence travels under. Not a topic anybody subscribes to directly. */
		const val VIEWERS = "doc_viewers"

		/** The [entity] whose team-less rows are private rather than transverse. */
		const val TICKETS = "tickets"

		/**
		 * Where a row nobody else may see is announced to the one person who may.
		 *
		 * Mirrored by `topicsFor` in `apps/web/src/lib/realtime-events.ts`, which is the
		 * usual hazard of a computed destination: a mismatch is a feature that silently
		 * does nothing. Here it would be quieter still, because the wide topic this
		 * replaces carried the same events — so the symptom is not "drafts stopped
		 * updating", it is "drafts stopped updating for their author only".
		 */
		fun authorTopic(authorId: UUID, entity: String): String = "/topic/users/$authorId/$entity"

		/**
		 * Where a page's presence is broadcast, and where a reader declares their own by
		 * subscribing. One function, called by the server to send and mirrored by
		 * `topicsFor` to subscribe — a mismatch here would be a feature that silently does
		 * nothing, which is the worst failure this design has available to it.
		 */
		fun viewersTopic(pageId: UUID): String = "/topic/docs/$pageId/viewers"

		/**
		 * [createdBy] matters only when [teamId] is null, and is then the difference between
		 * telling the author and telling nobody — so a caller that has the row in hand
		 * passes it, and one that does not accepts that a draft it touched goes unannounced.
		 */
		fun ticket(
			kind: ChangeKind,
			id: UUID,
			teamId: UUID?,
			projectId: UUID?,
			createdBy: UUID? = null,
			origin: String = "kanso",
		) = KansoEvent(TICKETS, kind, id, teamId, projectId, createdBy = createdBy, origin = origin)

		fun project(kind: ChangeKind, id: UUID, teamId: UUID?, origin: String = "kanso") =
			KansoEvent("projects", kind, id, teamId, null, origin = origin)

		fun team(kind: ChangeKind, id: UUID, origin: String = "kanso") =
			KansoEvent("teams", kind, id, id, null, origin = origin)

		/**
		 * A document, and optionally which of its blocks — `KAN-25`'s fourth entity.
		 *
		 * [id] is the **page**, never the block, and that is the decision this factory
		 * exists to fix in one place. Every reader of a document reads it a page at a time
		 * (`GET /api/docs/pages/{id}`), the tree and screen 22's list are keyed on pages,
		 * and a bus whose `id` sometimes meant a block would make `applyEvents` guess which
		 * it had been handed. The block is [blockId], beside it, absent when the change was
		 * to the page itself — a retitle, a refile, a deletion.
		 *
		 * `entity = "docs"` deliberately matches nothing in `WebhookEvent.entityTypeOf`, so
		 * documents are not broadcast to third-party subscribers by the act of appearing on
		 * this bus. That function says in its own comment that an unmapped topic returning
		 * null is the right default and that widening the webhook contract stays one
		 * deliberate line; this ticket does not spend it.
		 */
		fun doc(kind: ChangeKind, pageId: UUID, teamId: UUID, blockId: UUID? = null) =
			KansoEvent("docs", kind, pageId, teamId, null, blockId)

		/**
		 * Who is *reading* a page changed — presence, and the one event on this bus that is
		 * not about committed state.
		 *
		 * Its own entity rather than a `docs` event, because the two mean opposite things to
		 * a receiver: a `docs` event says the document is not what you drew, and this says
		 * the document is exactly what you drew and somebody else is looking at it. Folding
		 * them together would make every join and every leave refetch a page's blocks, and
		 * on a page two people have open that is a refetch every time either of them
		 * navigates.
		 *
		 * Always [ChangeKind.UPDATED]: a roster is never created or deleted, only different.
		 * The names are not in the payload — `DocPresence` broadcasts this straight to the
		 * local broker and the receiver reads the roster back over HTTP, which is the same
		 * thin-event contract the class doc argues for and the reason this needs no shape of
		 * its own.
		 *
		 * And it is the one event on this bus that does **not** go through [EventPublisher].
		 * That class defers to `afterCommit` and fans out over `pg_notify` so no client can
		 * refetch ahead of a commit; presence has no commit to be ahead of, and the roster
		 * it points at lives in this instance's memory. Sent through Postgres it would tell
		 * a client on another instance to re-read a roster that instance cannot see, which
		 * is a round trip to learn nothing. The notification and the memory it describes
		 * travel together or they lie.
		 */
		fun docViewers(pageId: UUID) = KansoEvent(VIEWERS, ChangeKind.UPDATED, pageId)
	}
}

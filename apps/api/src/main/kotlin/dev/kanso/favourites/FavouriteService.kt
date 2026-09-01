package dev.kanso.favourites

import dev.kanso.service.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The Favourites section, from the server's side.
 *
 * Nothing here branches on a kind: each has a [FavouriteSource] bean and this file looks
 * it up. The split is the trash's — the **pin** is this service's (it writes it, removes
 * it and decides the order), the **entity** is the source's — and it is what keeps a
 * source six lines and makes a fifth kind a bean and a column rather than an edit here.
 *
 * **Nothing is published on the realtime channel, deliberately.** `KansoEvent.destinations`
 * is `/topic/<entity>` plus a per-*team* topic, and every connected client is subscribed
 * to the first of those — there is no per-person destination anywhere in `realtime/`. A
 * favourite is one person's, so an event about one would push somebody's private sidebar
 * to everybody else's browser, and inventing a `/topic/users/<id>/…` to avoid that would
 * be a whole addressing scheme bought for a list only its owner reads. The one client that
 * needs to know is the one that made the change, and it already does. What the *others*
 * need is covered without a new event: a project they delete already fires
 * `KansoEvent.project(DELETED, …)`, and `lib/realtime-events.ts` invalidates the
 * favourites key on the back of it — the pin has already cascaded away by then.
 */
@Service
class FavouriteService(
	sources: List<FavouriteSource>,
	private val rows: FavouriteRepository,
) {

	private val byKind: Map<FavouriteKind, FavouriteSource> = sources.associateBy { it.kind }

	/**
	 * One person's pins, drawn, in the order they made them.
	 *
	 * No permission gate. Kanso's rule is read open, writes scoped, and this is a read of
	 * things whose names `GET /api/teams` and `GET /api/projects` already hand to anybody
	 * signed in — a gate here would be a second, narrower answer to a question already
	 * settled somewhere else.
	 *
	 * Every pin that can be drawn, archived ones included and flagged as such. Which of
	 * those the column actually shows is the sidebar's "Show archived" toggle's business —
	 * `useUi` state that never reaches this side — and answering it here would be guessing
	 * at a switch the server cannot see. A pin whose source drops it, which means one in the
	 * trash, is simply not in the answer: that is `describe`'s contract, and the reason this
	 * list can never contain a row the sidebar has nothing to draw.
	 */
	@Transactional(readOnly = true)
	fun list(userId: UUID): List<FavouriteItem> {
		val pins = rows.findByUser(userId)
		if (pins.isEmpty()) return emptyList()

		// One query per kind for the whole set, never one per pin — the rule every trash
		// source keeps, and the reason both `findAllLive` reads take a collection.
		val drawn = pins.groupBy { it.kind }
			.flatMap { (kind, group) ->
				val source = byKind[kind] ?: return@flatMap emptyList()
				source.describe(group.map { it.entityId })
			}
			.associateBy { it.kind to it.id }

		// Back into the reader's order, which the grouping above scattered.
		return pins.mapNotNull { drawn[it.kind to it.entityId] }
	}

	/**
	 * Pins [entityId], or does nothing if it is already pinned.
	 *
	 * Refused when there is nothing to draw, and refused *here* rather than left to the
	 * foreign key: a violation would surface as a 500, and the honest answer to pinning a
	 * saved view somebody threw away is that it is in the trash, not that the server broke.
	 *
	 * Archived counts as drawable — you can see an archived team in the sidebar with the
	 * toggle on, so you can pin one — while trashed does not, because a pin on a
	 * thirty-day countdown is a pin that quietly expires.
	 */
	@Transactional
	fun add(userId: UUID, kind: FavouriteKind, entityId: UUID) {
		val source = sourceFor(kind)
		if (source.describe(listOf(entityId)).isEmpty()) {
			throw NotFoundException(
				"No ${kind.wire} $entityId to favourite — it may have been deleted, or be in the trash",
			)
		}
		rows.add(userId, kind, entityId)
	}

	/** Un-pinning something that was not pinned is not an error: the gesture is a toggle. */
	@Transactional
	fun remove(userId: UUID, kind: FavouriteKind, entityId: UUID) {
		sourceFor(kind)
		rows.remove(userId, kind, entityId)
	}

	/**
	 * A kind with no bean is a wiring mistake, not a request problem — `FavouriteKind` and
	 * `V22`'s four columns are the same closed vocabulary, so the only way here is to add a
	 * kind and forget its `@Component`.
	 */
	private fun sourceFor(kind: FavouriteKind): FavouriteSource =
		byKind[kind] ?: error("No FavouriteSource registered for ${kind.wire}")
}

package dev.kanso.realtime

import dev.kanso.auth.KansoAuthenticatedUser
import dev.kanso.docs.DocViewer
import dev.kanso.repo.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.core.Authentication
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent
import java.security.Principal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who has a document open, right now — `KAN-25`'s presence, and **it is not a table**.
 *
 * `V40`'s header carries the argument at length; the short form is that presence is a fact
 * about a live socket, and a row outlives the socket that wrote it. A
 * `doc_page_viewers (page_id, user_id, last_seen_at)` would need a heartbeat to be
 * believed and a sweeper to be cleaned, and in between it would be a table of ghosts:
 * people shown reading pages nobody has open. The house rule that derived values are
 * computed on read has an obvious reading here — "who is on this page" is a function of
 * the set of open connections — and the set of open connections is not something a row can
 * hold.
 *
 * So the map below *is* the answer, and the three events it listens to are the whole
 * lifecycle:
 *
 *   * **subscribe** to a page's viewers topic — arriving. Subscribing is the declaration;
 *     there is no announce call to forget to make.
 *   * **unsubscribe** — navigating to another document, without dropping the socket.
 *   * **disconnect** — the tab closed, the network went, or the laptop shut. STOMP's
 *     10-second heartbeats (`WebSocketConfig`, `realtime.ts`) are what make this arrive
 *     within seconds of a lid closing rather than never.
 *
 * The caveat is the one `auth/UserSessions.kt` already carries and it is stated rather
 * than hidden: **this is per instance.** With two API containers, two people on one page
 * see each other's *edits* and each other's *locks* — both of those go through Postgres,
 * which is the whole reason the lock is a row — but each sees only the readers who
 * happened to connect to the same instance. The fix, if an instance ever scales out, is
 * for each one to publish its own slice and for the roster to be the union; it is not
 * worth the machinery for a product that ships as one container, and doing it wrong is
 * worse than not doing it, because a partial roster fanned out over `pg_notify` would
 * overwrite a correct local one.
 */
@Component
class DocPresence(
	private val messaging: SimpMessagingTemplate,
	private val users: UserRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * page -> STOMP session -> who.
	 *
	 * Keyed by **session** and not by user, so the same person in two tabs is one name in
	 * the roster and two entries here — and closing one tab does not remove them from the
	 * page they still have open in the other. Deduplication happens on the way out, in
	 * [viewersOf], because that is where "who is here" is asked; storing the deduplicated
	 * answer would be the stored derived value this class exists to argue against.
	 */
	private val byPage = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Seat>>()

	/** What a session is: whose it is, and the name a roster prints. */
	private data class Seat(val userId: UUID, val displayName: String)

	/**
	 * Presence, deduplicated by person and ordered by name.
	 *
	 * Ordered so two clients drawing the same roster draw it in the same order — a list of
	 * avatars that reshuffles on every unrelated join reads as a change to the page.
	 */
	fun viewersOf(pageId: UUID): List<DocViewer> =
		byPage[pageId]?.values
			?.map { DocViewer(it.userId, it.displayName) }
			?.distinctBy { it.userId }
			?.sortedBy { it.displayName }
			.orEmpty()

	/**
	 * `@Transactional` for one reason: [seatOf] reads `users` through Exposed, which needs
	 * a transaction, and an `@EventListener` is called on no thread that has one. Read-only
	 * because that is all it does — nothing about somebody arriving on a page is written.
	 */
	@EventListener
	@Transactional(readOnly = true)
	fun onSubscribe(event: SessionSubscribeEvent) {
		val accessor = accessor(event)
		val pageId = accessor.destination?.let(::pageOf) ?: return
		val sessionId = accessor.sessionId ?: return
		val seat = seatOf(event.user) ?: return

		byPage.computeIfAbsent(pageId) { ConcurrentHashMap() }[sessionId] = seat
		log.debug("{} is reading document {}", seat.displayName, pageId)
		announce(pageId)
	}

	/** Left this page, kept the socket — what navigating between two documents looks like. */
	@EventListener
	fun onUnsubscribe(event: SessionUnsubscribeEvent) {
		val sessionId = accessor(event).sessionId ?: return
		// The UNSUBSCRIBE frame carries the *subscription id*, not the destination, so
		// there is nothing here to parse a page out of. Dropping the session from every
		// page is correct rather than lazy: a session holds at most one viewers topic —
		// `topicsFor` computes one open document — and forgetting it from a page it was
		// never on is a no-op.
		forget(sessionId)
	}

	/** The tab closed, the network went, or the lid did. */
	@EventListener
	fun onDisconnect(event: SessionDisconnectEvent) = forget(event.sessionId)

	// --- helpers -------------------------------------------------------------

	private fun forget(sessionId: String) {
		byPage.forEach { (pageId, seats) ->
			seats.remove(sessionId) ?: return@forEach
			// Emptied rather than left as an empty map: the ceiling on this structure is
			// "pages currently open somewhere", and a page that closes should not cost a
			// map key until the process restarts.
			if (seats.isEmpty()) byPage.remove(pageId, seats)
			announce(pageId)
		}
	}

	/**
	 * Straight to the local broker, deliberately not through [EventPublisher] — the
	 * factory's own doc says why. Uses `destinations()` so the topic is the one string
	 * `viewersTopic` computes for everybody.
	 */
	private fun announce(pageId: UUID) {
		val event = KansoEvent.docViewers(pageId)
		event.destinations().forEach { messaging.convertAndSend(it, event) }
	}

	/**
	 * The Kanso identity behind a STOMP session.
	 *
	 * The handshake is authenticated by the security filter chain (`SecurityConfig`), so
	 * what arrives here is the `Authentication` the session cookie produced and its
	 * principal is one of the five in `auth/Principals.kt` — every one of which implements
	 * [KansoAuthenticatedUser]. Anything else is not a Kanso reader and is ignored rather
	 * than guessed at: an unnamed seat in a roster is worse than an absent one.
	 *
	 * The display name comes from `users`, not from `Principal.getName()`. Those agree for
	 * a dev or local session and do not for an OIDC one, where `getName()` is the
	 * provider's subject — and a roster showing a Google subject id beside an avatar is the
	 * "Last used Invalid Date" of this feature.
	 */
	private fun seatOf(principal: Principal?): Seat? {
		val kanso = when (principal) {
			is Authentication -> principal.principal as? KansoAuthenticatedUser
			is KansoAuthenticatedUser -> principal
			else -> null
		} ?: return null
		val user = users.findById(kanso.kansoUserId) ?: return null
		return Seat(user.id, user.displayName)
	}

	private fun accessor(event: AbstractSubProtocolEvent) = StompHeaderAccessor.wrap(event.message)

	/**
	 * The page id out of `/topic/docs/{uuid}/viewers`, or null for every other topic.
	 *
	 * Matched against the shape [KansoEvent.viewersTopic] builds rather than against a
	 * hand-written regex of it, so the two cannot drift: a subscription that is not exactly
	 * what that function would have produced for the uuid it contains is not a presence
	 * declaration.
	 */
	private fun pageOf(destination: String): UUID? {
		val id = destination
			.removePrefix("/topic/docs/")
			.removeSuffix("/viewers")
			.takeIf { it.length == UUID_LENGTH }
			?: return null
		val parsed = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
		return parsed.takeIf { KansoEvent.viewersTopic(it) == destination }
	}

	private companion object {
		const val UUID_LENGTH = 36
	}
}

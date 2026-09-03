package dev.kanso.webhooks

import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundOperation
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.OutboundJobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Turns a change into a queued webhook job. One call, from one place.
 *
 * ## Why `EventPublisher` and not the forty `outbox.enqueue` call sites
 *
 * `V26` said the destination argument has no default so that "the call site is exactly
 * where it will be obvious that one change now has to reach two places", and it was right
 * about the obligation. It is worth being precise about why the answer here is not to
 * discharge it forty times.
 *
 * Every `outbox.enqueue(Destination.NOTION, …)` in `TicketService`, `TeamService`,
 * `ProjectService` and `ScheduleService` sits beside an `events.publish(KansoEvent…)` that
 * says the same thing to the browser. Notion's calls are *not* uniform — they are woven
 * into archive cascades and dependency fix-ups, and several are conditional on what the
 * mirror is doing — so a mechanical second call beside each would be forty chances to put
 * it inside the wrong branch, in four files three other branches are also editing. The
 * realtime publish, by contrast, is already the one funnel every mutation passes through
 * exactly once, and "this entity changed, in this way, in this team" is precisely the
 * message a webhook carries. So webhooks ride the funnel that exists.
 *
 * The obligation `V26` describes is met, just not per-site: adding a destination still
 * meant finding where changes are announced and saying so deliberately. That is this file.
 *
 * ## The enqueue is transactional; the notification is not
 *
 * [record] is called from `EventPublisher.publish` **before** it registers its
 * `afterCommit` hook, so the `outbound_jobs` row is written inside the caller's own
 * transaction. That is not a detail — it is the outbox's founding guarantee, which
 * `OutboundJobRepository.enqueue` states as "called inside the business transaction, so the
 * job cannot be lost if the process dies right after the commit". Hooking the enqueue onto
 * `afterCommit` instead would have been the natural-looking thing and would have thrown
 * that away: a crash in the window between commit and hook would lose the webhook silently,
 * with the change itself durably applied.
 *
 * The realtime `NOTIFY` stays on `afterCommit`, for the reasons that file gives. The two
 * halves want opposite treatment and now get it — a lost notification is cosmetic, a lost
 * delivery is not.
 */
@Component
class WebhookFanout(
	private val subscriptions: WebhookSubscriptionRepository,
	private val jobs: OutboundJobRepository,
	private val objectMapper: ObjectMapper,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun record(event: KansoEvent) {
		val entityType = WebhookEvent.entityTypeOf(event.entity) ?: return

		// Asked before anything is written, so an instance with no webhooks pays one index
		// probe per change rather than a queue row per change — `anyLive` carries the point.
		if (!subscriptions.anyLive()) return

		val body = objectMapper.writeValueAsString(WebhookEvent.of(event, entityType))

		// One job per entity per pending window, because that is what `enqueue` does: it
		// coalesces onto the existing pending row. Inherited rather than chosen, and it
		// happens to be half the answer to the ticket's own worry — a loop editing one
		// ticket a thousand times is one queued row, not a thousand. The cost is that a
		// subscriber sees the latest change and not every intermediate one, which is the
		// same contract `Events.kt` gives the browser.
		val operation =
			if (WebhookEvent.isDelete(event.kind)) OutboundOperation.DELETE else OutboundOperation.UPSERT

		// Caught so that a webhook cannot be the reason somebody's ticket refused to save,
		// which is `EventPublisher.notify`'s judgement ("never let it fail or roll back a
		// write") applied one layer earlier.
		//
		// **And it is a weaker guarantee than that one, deliberately stated rather than
		// implied.** `notify` runs after the commit, so swallowing there really does leave
		// the write untouched. This runs *inside* the transaction — which is the whole point,
		// see above — and Postgres aborts a transaction on any failed statement, so a genuine
		// SQL failure here poisons the caller's transaction and the save fails at commit
		// whatever this block does. What the catch actually covers is everything that is not
		// the database: a payload that would not serialise, a null nobody expected, a bug in
		// this file. That is worth covering and it is not "webhooks can never break a save";
		// the way this stays true is that [record] does nothing clever, and it should not
		// start.
		try {
			jobs.enqueue(Destination.WEBHOOK, entityType, event.id, operation, body)
		} catch (e: Exception) {
			log.warn("Could not queue a webhook for {} {}: {}", entityType.wire, event.id, e.message)
		}
	}
}

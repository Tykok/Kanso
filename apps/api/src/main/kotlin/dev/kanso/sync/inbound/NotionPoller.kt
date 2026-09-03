package dev.kanso.sync.inbound

import dev.kanso.config.KansoProperties
import dev.kanso.domain.EffortPoints
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.Ticket
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.RequestBase
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.service.NotificationKind
import dev.kanso.service.NotificationService
import dev.kanso.service.ScheduleService
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionProps
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Pulls edits made in Notion back into Postgres.
 *
 * Notion has no push worth relying on, so this walks each data source ordered by
 * `last_edited_time` ascending from a stored cursor. Ascending order matters: with
 * descending order, a page edited while we paginate can move across the page
 * boundary and never be seen.
 *
 * Only scalar fields come back — name, status, priority, dates, archived. Relations
 * and people stay Kanso-authoritative on purpose: Notion replaces relation arrays
 * wholesale, so accepting them would turn a concurrent edit into silent data loss,
 * and `people` cannot represent a Kanso user who has no Notion account.
 *
 * **One base is polled under the opposite rule.** A requests base (`V37`) is not a mirror:
 * Kanso never wrote it, never pushes to it, and adopts each of its pages exactly once, as a
 * ticket in a team's triage queue. Everything above about echoes, conflicts and "Kanso
 * wins" is about reconciling two copies of one row and none of it applies — there is no
 * second copy, there is a request. The walk itself is identical, which is why it is here
 * rather than in a second poller: same ascending order, same cursor, same overlap window,
 * same `last_error` the sync badge reads. [RequestSiphon] holds the part that differs.
 */
@Component
class NotionPoller(
	private val props: KansoProperties,
	private val client: NotionClient,
	private val meta: NotionMetaRepository,
	private val requestBases: RequestBaseRepository,
	private val siphon: RequestSiphon,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val jobs: OutboundJobRepository,
	private val schedule: ScheduleService,
	private val notifications: NotificationService,
	private val events: EventPublisher,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${kanso.sync.inbound.interval-ms:30000}")
	fun poll() {
		if (!props.sync.inbound.enabled || !client.enabled) return

		val sources = tx.execute { sources() }.orEmpty()
		if (sources.isEmpty()) return

		runBlocking {
			val botId = runCatching { client.botUserId() }.getOrNull()
			for (source in sources) {
				try {
					pollSource(source, botId)
				} catch (e: Exception) {
					log.warn("Inbound poll of {} failed: {}", source.kind, e.message)
					tx.executeWithoutResult { meta.saveCursor(source.dataSourceId, null, e.message) }
				}
			}
		}
	}

	/**
	 * The mirrored databases, then the requests bases — two tables because they are two
	 * different relationships with Notion, and `V37` argues that keeping them apart is what
	 * makes "nothing pushes to a requests base" structural.
	 *
	 * Mirrors first so that a slow or broken requests base cannot delay the reconciliation
	 * the rest of the product depends on. Each source's failure is already caught and
	 * recorded per source, so neither can stop the other.
	 */
	private fun sources(): List<Polled> =
		meta.findAll().filter { it.kind in POLLED_KINDS }.map { Polled(it.kind, it.dataSourceId, null) } +
			requestBases.findAll().map { Polled(REQUESTS, it.dataSourceId, it) }

	/** One thing to walk: a mirrored kind, or a requests base carrying the team it feeds. */
	private data class Polled(val kind: String, val dataSourceId: String, val requests: RequestBase?)

	private suspend fun pollSource(source: Polled, botUserId: String?) {
		val dataSourceId = source.dataSourceId
		val cursor = tx.execute { meta.cursor(dataSourceId) }
		// Re-scan a short window each time: Notion's clock is not ours, and a page
		// edited in the same second as the last one we saw would otherwise be missed.
		val since = cursor?.lastEditTime?.minus(props.sync.inbound.overlap)

		var startCursor: String? = null
		var highWatermark = cursor?.lastEditTime
		var seen = 0

		do {
			val page = client.queryDataSource(
				dataSourceId = dataSourceId,
				editedOnOrAfter = since,
				startCursor = startCursor,
				pageSize = props.sync.inbound.pageSize,
				includeArchived = true,
			)
			for (notionPage in page.pages) {
				seen++
				notionPage.lastEditedTime?.let { edited ->
					if (highWatermark == null || edited.isAfter(highWatermark)) highWatermark = edited
				}
				tx.executeWithoutResult { apply(source, notionPage, botUserId) }
			}
			startCursor = page.nextCursor
		} while (page.hasMore && startCursor != null)

		tx.executeWithoutResult { meta.saveCursor(dataSourceId, highWatermark, null) }
		if (seen > 0) log.info("Inbound poll of {}: examined {} page(s)", source.kind, seen)
	}

	private fun apply(source: Polled, page: NotionPage, botUserId: String?) {
		// Guard 1: our own push coming back. Necessary but not sufficient — a human
		// can edit in the same second we do, which the timestamp guard catches.
		//
		// Inert on a requests base, since Kanso never edits one, and kept there anyway: the
		// one way it can fire is a base registered by mistake against a database the mirror
		// itself writes, and skipping those pages is better than adopting the instance's own
		// tickets back into it. `RequestBaseService` refuses that registration outright, so
		// this is the second of two.
		if (botUserId != null && page.lastEditedById == botUserId) return

		val requests = source.requests
		if (requests != null) {
			siphon.adopt(requests, page)
			return
		}

		when (source.kind) {
			"tickets" -> applyTicket(page)
			"projects" -> applyProject(page)
			"teams" -> applyTeam(page)
		}
	}

	private fun applyTicket(page: NotionPage) {
		val ticket = tickets.findByNotionPageId(page.id) ?: return orphan("ticket", page)
		if (isEcho(page.lastEditedTime, ticket.mirror.notionLastEditedTime)) return

		if (kansoWins(page, ticket.updatedAt, ticket.mirror.notionSyncedAt)) {
			jobs.enqueue(Destination.NOTION, OutboundEntityType.TICKET, ticket.id, OutboundOperation.UPSERT)
			recordConflicts(ticket, page)
			return
		}

		val props = page.properties
		val status = select(props, NotionProps.STATUS)?.let { TicketStatus.fromLabel(it) ?: unknown("status", it) }
		val priority = select(props, NotionProps.PRIORITY)?.let { TicketPriority.fromLabel(it) ?: unknown("priority", it) }

		// Read back like a status, and refused the same way: the scale is closed, so a 7
		// somebody typed into the mirror is logged and dropped rather than adopted — the
		// corrective push puts Kanso's own value back on the page.
		val estimate = number(props, NotionProps.ESTIMATE)
			?.let { if (it in EffortPoints.SCALE) it else unknown("estimate", it.toString()) }

		val start = instant(props, NotionProps.START) ?: ticket.start
		val due = instant(props, NotionProps.DUE) ?: ticket.due

		tickets.update(
			id = ticket.id,
			teamId = ticket.teamId,
			title = title(props) ?: ticket.title,
			description = text(props, NotionProps.DESCRIPTION) ?: ticket.description,
			status = status ?: ticket.status,
			priority = priority ?: ticket.priority,
			// A property Notion sends empty is absent here, as everywhere else in this
			// method: clearing an estimate is done in Kanso, where `unset` can say it.
			estimate = estimate ?: ticket.estimate,
			start = start,
			due = due,
			// Carried through untouched: the mirror does not decide when a ticket was
			// completed, and an inbound edit must not restamp a completion.
			completedAt = ticket.completedAt,
			projectId = ticket.projectId,
			archived = page.archived,
		)
		tickets.recordNotionEdit(ticket.id, page.lastEditedTime)

		// A date edited in Notion is a date change like any other. Writing it straight
		// to the row would let it bypass the engine, and the plan would break with
		// nobody able to say why. The anti-echo guards above stop the pushes this
		// queues from coming back round.
		if (start != ticket.start || due != ticket.due) schedule.cascadeFrom(ticket.id)

		events.publish(
			KansoEvent.ticket(ChangeKind.UPDATED, ticket.id, ticket.teamId, ticket.projectId, origin = "notion")
		)
		log.info("Applied Notion edit to ticket {}", ticket.id)
	}

	private fun applyProject(page: NotionPage) {
		val project = projects.findByNotionPageId(page.id) ?: return orphan("project", page)
		if (isEcho(page.lastEditedTime, project.mirror.notionLastEditedTime)) return

		if (kansoWins(page, project.updatedAt, project.mirror.notionSyncedAt)) {
			jobs.enqueue(Destination.NOTION, OutboundEntityType.PROJECT, project.id, OutboundOperation.UPSERT)
			return
		}

		val props = page.properties
		val status = select(props, NotionProps.STATUS)?.let { ProjectStatus.fromLabel(it) ?: unknown("status", it) }

		projects.update(
			id = project.id,
			name = title(props) ?: project.name,
			status = status ?: project.status,
			start = instant(props, NotionProps.START) ?: project.start,
			end = instant(props, NotionProps.END) ?: project.end,
			leadUserId = project.leadUserId,
			teamId = project.teamId,
			archived = page.archived,
		)
		projects.markSynced(project.id, page.id, page.lastEditedTime)
		events.publish(KansoEvent.project(ChangeKind.UPDATED, project.id, project.teamId, origin = "notion"))
		log.info("Applied Notion edit to project {}", project.id)
	}

	/**
	 * Teams accept nothing from Notion. The page is acknowledged, and that is all.
	 *
	 * The parent relation is refused because Notion's self-referencing relation can be
	 * made cyclic, and repairing that after the fact is worse than never accepting it.
	 * The archived flag is refused for the same class of reason, and the reasoning
	 * transfers verbatim. Archiving a team in Kanso is a whole operation — a
	 * disposition plan deciding what becomes of its sub-teams, projects and tickets,
	 * and the invariant that an unarchived team never has an archived ancestor
	 * (`TeamService.update`, `TeamService.archive`, `TeamService.unarchive`). A
	 * checkbox in Notion carries none of that, and this class has no actor to authorise
	 * it either. Letting it through breaks the invariant in *both* directions —
	 * archiving a parent leaves live children under an archived ancestor, unarchiving a
	 * child leaves it under one — and nothing anywhere repairs it afterwards.
	 *
	 * A disagreement queues a corrective push instead, for the same reason [kansoWins]
	 * does: Notion converges back on its own rather than the two disagreeing forever.
	 */
	private fun applyTeam(page: NotionPage) {
		val team = teams.findByNotionPageId(page.id) ?: return orphan("team", page)
		if (isEcho(page.lastEditedTime, team.mirror.notionLastEditedTime)) return

		if (kansoWins(page, team.updatedAt, team.mirror.notionSyncedAt)) {
			jobs.enqueue(Destination.NOTION, OutboundEntityType.TEAM, team.id, OutboundOperation.UPSERT)
			return
		}
		if (team.archived != page.archived) {
			log.info(
				"Ignoring the archived flag on Notion page {}: a team's archived state is decided in Kanso, " +
					"with a disposition plan; re-pushing",
				page.id,
			)
			jobs.enqueue(
				Destination.NOTION,
				OutboundEntityType.TEAM,
				team.id,
				if (team.archived) OutboundOperation.ARCHIVE else OutboundOperation.UPSERT,
			)
		}
		teams.markSynced(team.id, page.id, page.lastEditedTime)
	}

	/** Guard 2: nothing newer than what our own push produced. */
	private fun isEcho(pageEdited: OffsetDateTime?, recorded: OffsetDateTime?): Boolean =
		pageEdited != null && recorded != null && !pageEdited.isAfter(recorded)

	/**
	 * Kanso wins. If the Postgres row moved after our last successful push, the
	 * Notion edit is discarded and a corrective push is queued, which makes Notion
	 * converge back on its own rather than leaving the two permanently disagreeing.
	 */
	private fun kansoWins(page: NotionPage, updatedAt: OffsetDateTime, syncedAt: OffsetDateTime?): Boolean {
		val localChangedSincePush = syncedAt == null || updatedAt.isAfter(syncedAt)
		if (localChangedSincePush) {
			log.info(
				"Kanso wins for Notion page {}: local row changed after the last push, re-pushing",
				page.id,
			)
		}
		return localChangedSincePush
	}

	/**
	 * Says that [kansoWins] fired, to whoever is answerable for the ticket in Kanso.
	 *
	 * This is the only place in the product where a conflict can be observed: the one moment
	 * where two versions of one field are both in hand and the rule throws one away. Nothing
	 * here changes the rule — it has already been applied and the corrective push is already
	 * queued by the time this runs — and the row it writes is what makes screen 15's chooser
	 * reachable at all. `architecture.md` calls per-field merge a v2 conversation; this is
	 * the notice, not the merge.
	 *
	 * Three restrictions, each load-bearing.
	 *
	 * **The fields are diffed.** [kansoWins] answers on timestamps alone, so it fires on
	 * every poll of a row that moved since the last push — including a page whose
	 * `last_edited_time` moved for a property Kanso does not mirror, which is a page with no
	 * disagreement on it at all. No difference, no conflict.
	 *
	 * **Only the free text.** A status or a priority coming back is a value out of a closed
	 * vocabulary that the corrective push settles on its own, and "two versions of the
	 * priority" side by side tells the reader nothing the ticket does not already say. Title
	 * and description are the two where the versions are two *sentences* and only a person
	 * can choose between them — and they are exactly the two the chooser's `Keep Notion`
	 * knows how to apply, so every chooser this opens has three working ways out.
	 *
	 * **Tickets only.** A project's discarded edit is not recorded because the chooser
	 * patches a ticket and nothing else, and a team's cannot be: `entity_type` is closed by
	 * `V13` to ticket, project and doc.
	 */
	private fun recordConflicts(ticket: Ticket, page: NotionPage) {
		val recipients = tickets.assigneeIds(ticket.id).ifEmpty {
			// Nobody holds the ticket, so nobody has been handed the disagreement. The lead
			// of its project is the next person answerable for it; failing that the conflict
			// is logged by [kansoWins] and told to no one, because the alternative is a
			// fan-out to a whole team and this table is one row per person by design.
			listOfNotNull(ticket.projectId?.let { projects.findById(it)?.leadUserId })
		}
		if (recipients.isEmpty()) return

		val pageProps = page.properties
		val differing = listOfNotNull(
			difference("title", ticket.title, title(pageProps)),
			difference("description", ticket.description.orEmpty(), text(pageProps, NotionProps.DESCRIPTION)),
		)
		for ((field, mine, theirs) in differing) {
			if (notifications.conflictRecorded(ticket.id, field, theirs)) continue
			notifications.record(
				recipients = recipients,
				kind = NotificationKind.CONFLICT,
				entityType = "ticket",
				entityId = ticket.id,
				// No actor. Whoever edited the page is a Notion account, and this class has
				// no Kanso user to name — `V13` makes the column nullable for exactly this.
				actorId = null,
				payload = mapOf(
					"field" to field,
					"mine" to mine,
					"theirs" to theirs,
					// The instant, which is where the chooser reads the hour off. Not
					// `theirActor`: the page carries Notion's own user id, `NotionClient`
					// offers no way to resolve it to a name, and a uuid where the chooser
					// draws a person reads worse than the plain "Notion" it falls back to.
					"theirEditedAt" to page.lastEditedTime?.toString(),
				),
			)
		}
	}

	/**
	 * One field's two versions, or null when there is nothing to choose between.
	 *
	 * A value Notion does not send is not a version: [title] and [text] read a blank
	 * property as absent, so a field cleared in Notion is invisible here — exactly as it is
	 * to [applyTicket], which falls back to the row's own value for the same reason.
	 */
	private fun difference(field: String, mine: String, theirs: String?): Triple<String, String, String>? =
		theirs?.takeIf { it != mine }?.let { Triple(field, mine, it) }

	/**
	 * A page Kanso never created, in a database Kanso *did*. Not adopted: a ticket needs a
	 * team and a number that Notion has no way to supply, and inventing them would produce
	 * rows nobody asked for. Logged so it is visible rather than silently dropped.
	 *
	 * A requests base is where the opposite answer is given, and the difference is not the
	 * page — it is that somebody registered that base against a team, which is the missing
	 * fact being supplied by a person rather than guessed here. A page typed into
	 * `Kanso · Tickets` still has nobody who chose where it belongs, so it still gets this.
	 */
	private fun orphan(kind: String, page: NotionPage) {
		log.debug("Ignoring {} page {} — created in Notion, not adopted by Kanso v1", kind, page.id)
	}

	private fun <T> unknown(field: String, value: String): T? {
		log.warn("Ignoring unknown {} '{}' from Notion; Kanso's vocabulary is closed", field, value)
		return null
	}

	// --- property readers ----------------------------------------------------

	private fun title(props: JsonNode?): String? =
		props?.path(NotionProps.NAME)?.path("title")?.joinToString("") { it.path("plain_text").asText("") }
			?.takeIf { it.isNotBlank() }

	private fun text(props: JsonNode?, name: String): String? =
		props?.path(name)?.path("rich_text")?.joinToString("") { it.path("plain_text").asText("") }
			?.takeIf { it.isNotBlank() }

	private fun number(props: JsonNode?, name: String): Int? =
		props?.path(name)?.path("number")?.takeIf { it.isNumber }?.asInt()

	private fun select(props: JsonNode?, name: String): String? =
		props?.path(name)?.path("select")?.path("name")?.asText(null)?.takeIf { it.isNotBlank() }

	/**
	 * Notion writes `2026-08-12` for a day and a full ISO instant for a moment; the
	 * length of the string is what tells them apart, and it decides `hasTime`.
	 *
	 * Anything unparseable is ignored rather than thrown: the value comes from a
	 * system Kanso does not control, and one bad page must not stop the poll.
	 */
	private fun instant(props: JsonNode?, name: String): KansoInstant? {
		val raw = props?.path(name)?.path("date")?.path("start")?.asText(null)?.takeIf { it.isNotBlank() }
			?: return null
		return runCatching {
			if (raw.length <= 10) KansoInstant(LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC), false)
			else KansoInstant(OffsetDateTime.parse(raw), true)
		}.getOrNull()
	}

	private companion object {
		val POLLED_KINDS = setOf("teams", "projects", "tickets")

		/**
		 * Not a fifth member of [POLLED_KINDS]: that set names rows of `notion_databases`,
		 * and `V37` spends its header on why a requests base is not one of those. This is a
		 * label for the log and the failure path, nothing reads it back.
		 */
		const val REQUESTS = "requests"
	}
}

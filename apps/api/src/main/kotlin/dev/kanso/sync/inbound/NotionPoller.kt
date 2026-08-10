package dev.kanso.sync.inbound

import dev.kanso.config.KansoProperties
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.realtime.ChangeKind
import dev.kanso.realtime.EventPublisher
import dev.kanso.realtime.KansoEvent
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.service.ScheduleService
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncOperation
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
 */
@Component
class NotionPoller(
	private val props: KansoProperties,
	private val client: NotionClient,
	private val meta: NotionMetaRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val jobs: SyncJobRepository,
	private val schedule: ScheduleService,
	private val events: EventPublisher,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${kanso.sync.inbound.interval-ms:30000}")
	fun poll() {
		if (!props.sync.inbound.enabled || !client.enabled) return

		val sources = tx.execute { meta.findAll() }.orEmpty()
			.filter { it.kind in POLLED_KINDS }
		if (sources.isEmpty()) return

		runBlocking {
			val botId = runCatching { client.botUserId() }.getOrNull()
			for (source in sources) {
				try {
					pollSource(source.kind, source.dataSourceId, botId)
				} catch (e: Exception) {
					log.warn("Inbound poll of {} failed: {}", source.kind, e.message)
					tx.executeWithoutResult { meta.saveCursor(source.dataSourceId, null, e.message) }
				}
			}
		}
	}

	private suspend fun pollSource(kind: String, dataSourceId: String, botUserId: String?) {
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
				tx.executeWithoutResult { apply(kind, notionPage, botUserId) }
			}
			startCursor = page.nextCursor
		} while (page.hasMore && startCursor != null)

		tx.executeWithoutResult { meta.saveCursor(dataSourceId, highWatermark, null) }
		if (seen > 0) log.info("Inbound poll of {}: examined {} page(s)", kind, seen)
	}

	private fun apply(kind: String, page: NotionPage, botUserId: String?) {
		// Guard 1: our own push coming back. Necessary but not sufficient — a human
		// can edit in the same second we do, which the timestamp guard catches.
		if (botUserId != null && page.lastEditedById == botUserId) return

		when (kind) {
			"tickets" -> applyTicket(page)
			"projects" -> applyProject(page)
			"teams" -> applyTeam(page)
		}
	}

	private fun applyTicket(page: NotionPage) {
		val ticket = tickets.findByNotionPageId(page.id) ?: return orphan("ticket", page)
		if (isEcho(page.lastEditedTime, ticket.mirror.notionLastEditedTime)) return

		if (kansoWins(page, ticket.updatedAt, ticket.mirror.notionSyncedAt)) {
			jobs.enqueue(SyncEntityType.TICKET, ticket.id, SyncOperation.UPSERT)
			return
		}

		val props = page.properties
		val status = select(props, NotionProps.STATUS)?.let { TicketStatus.fromLabel(it) ?: unknown("status", it) }
		val priority = select(props, NotionProps.PRIORITY)?.let { TicketPriority.fromLabel(it) ?: unknown("priority", it) }

		val start = instant(props, NotionProps.START) ?: ticket.start
		val due = instant(props, NotionProps.DUE) ?: ticket.due

		tickets.update(
			id = ticket.id,
			teamId = ticket.teamId,
			title = title(props) ?: ticket.title,
			description = text(props, NotionProps.DESCRIPTION) ?: ticket.description,
			status = status ?: ticket.status,
			priority = priority ?: ticket.priority,
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
			jobs.enqueue(SyncEntityType.PROJECT, project.id, SyncOperation.UPSERT)
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
			jobs.enqueue(SyncEntityType.TEAM, team.id, SyncOperation.UPSERT)
			return
		}
		if (team.archived != page.archived) {
			log.info(
				"Ignoring the archived flag on Notion page {}: a team's archived state is decided in Kanso, " +
					"with a disposition plan; re-pushing",
				page.id,
			)
			jobs.enqueue(
				SyncEntityType.TEAM,
				team.id,
				if (team.archived) SyncOperation.ARCHIVE else SyncOperation.UPSERT,
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
	 * A page Kanso never created. v1 does not adopt it: a ticket needs a team and a
	 * number that Notion has no way to supply, and inventing them would produce
	 * rows nobody asked for. Logged so it is visible rather than silently dropped.
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
	}
}

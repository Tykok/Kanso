package dev.kanso.sync.outbound

import dev.kanso.domain.SyncState
import dev.kanso.outbox.Completion
import dev.kanso.outbox.Destination
import dev.kanso.outbox.Failure
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundJob
import dev.kanso.outbox.OutboundJobHandler
import dev.kanso.outbox.OutboundOperation
import dev.kanso.repo.DocRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionRateLimited
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/** Nothing can be pushed before the mirrored databases exist. */
private class NotBootstrapped(kind: String) :
	RuntimeException("No Notion database registered for '$kind' — run the bootstrap first")

/**
 * A delete has to carry the Notion page id: by the time the handler runs, the
 * Postgres row is gone and there is nothing left to look it up from.
 *
 * It lives beside the handler rather than with the queue because it is the contract
 * between whoever enqueues a Notion delete and whoever performs one — a payload
 * shape no other destination will share.
 */
fun deletePayload(notionPageId: String?): String? =
	notionPageId?.let { """{"notionPageId":"$it"}""" }

/**
 * The Notion half of the outbox: what a job *means* when its destination is Notion.
 *
 * Draining, retrying, backing off and giving up are not here — they are the same for
 * every destination and live in `OutboundWorker`. This file is only the answer to
 * "what does pushing a ticket to Notion involve", which is what a second consumer
 * will write its own version of.
 */
@Component
class NotionOutboundHandler(
	private val client: NotionClient,
	private val mapper: NotionMapper,
	private val meta: NotionMetaRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val docs: DocRepository,
	private val tx: TransactionTemplate,
	private val objectMapper: ObjectMapper,
) : OutboundJobHandler {

	override val destination = Destination.NOTION

	override suspend fun handle(job: OutboundJob): Completion {
		if (!client.enabled) {
			// The queue still ran; there is simply nowhere to push. Say so in the
			// row's state so the UI shows "mirror off" rather than "pending" forever.
			return Completion { markState(job, SyncState.DISABLED) }
		}

		return when (val plan = tx.execute { plan(job) } ?: Plan.Nothing) {
			is Plan.Nothing -> Completion.NOTHING

			is Plan.Archive -> {
				client.updatePage(plan.pageId, archived = true)
				Completion { if (plan.keepEntity) markState(job, SyncState.SYNCED) }
			}

			is Plan.Upsert -> {
				val page = if (plan.pageId == null) {
					client.createPage(plan.dataSourceId, plan.properties)
				} else {
					client.updatePage(plan.pageId, plan.properties, archived = false)
				}
				Completion { recordSynced(job, page.id, page.lastEditedTime) }
			}
		}
	}

	/**
	 * Which of Notion's refusals are worth another go.
	 *
	 * A rate limit and a dependency that hasn't landed are not the job's fault, so
	 * they are deferred without spending an attempt: counting them would fail
	 * perfectly good work for being queued behind something slow.
	 */
	override fun classify(error: Exception): Failure = when (error) {
		is DependencyNotReady -> Failure.Defer(error.message ?: "dependency not ready", DEPENDENCY_DELAY)
		is NotBootstrapped -> Failure.Defer(error.message ?: "not bootstrapped", BOOTSTRAP_DELAY)
		is NotionRateLimited -> Failure.Defer("rate limited", error.retryAfter)

		is NotionApiException ->
			if (error.retryable) Failure.Retry(message(error)) else Failure.Fatal(message(error))

		else -> Failure.Retry(message(error))
	}

	/** `sync_state` on the row is what makes a stuck mirror visible without reading logs. */
	override fun onGivenUp(job: OutboundJob, error: Exception) = markState(job, SyncState.FAILED)

	private sealed interface Plan {
		/** The entity is gone and carried no page id — nothing to mirror. */
		data object Nothing : Plan

		data class Upsert(
			val dataSourceId: String,
			val pageId: String?,
			val properties: Map<String, Any?>,
		) : Plan

		data class Archive(val pageId: String, val keepEntity: Boolean) : Plan
	}

	/** Runs inside a read transaction; may throw [DependencyNotReady] or [NotBootstrapped]. */
	private fun plan(job: OutboundJob): Plan {
		if (job.operation == OutboundOperation.DELETE) {
			// The row is already gone, so the page id has to come from the payload.
			val pageId = job.payload
				?.let { runCatching { objectMapper.readTree(it).path("notionPageId").asText(null) }.getOrNull() }
			return pageId?.let { Plan.Archive(it, keepEntity = false) } ?: Plan.Nothing
		}

		val dataSourceId = { kind: String ->
			meta.find(kind)?.dataSourceId ?: throw NotBootstrapped(kind)
		}

		return when (job.entityType) {
			OutboundEntityType.TEAM -> {
				val team = teams.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, team.mirror.notionPageId, team.archived)
					?: Plan.Upsert(dataSourceId("teams"), team.mirror.notionPageId, mapper.teamProperties(team))
			}

			OutboundEntityType.PROJECT -> {
				val project = projects.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, project.mirror.notionPageId, project.archived)
					?: Plan.Upsert(dataSourceId("projects"), project.mirror.notionPageId, mapper.projectProperties(project))
			}

			OutboundEntityType.TICKET -> {
				val ticket = tickets.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, ticket.mirror.notionPageId, ticket.archived)
					?: Plan.Upsert(dataSourceId("tickets"), ticket.mirror.notionPageId, mapper.ticketProperties(ticket))
			}

			OutboundEntityType.DOC -> {
				val doc = docs.findById(job.entityId) ?: return Plan.Nothing
				Plan.Upsert(dataSourceId("docs"), doc.mirrorPageId, mapper.docProperties(doc))
			}
		}
	}

	/**
	 * An archive of something never pushed is a no-op: there is no page to archive,
	 * and creating one only to archive it would litter the mirror.
	 */
	private fun archiveOr(job: OutboundJob, pageId: String?, archived: Boolean): Plan? =
		if (job.operation == OutboundOperation.ARCHIVE || archived) {
			pageId?.let { Plan.Archive(it, keepEntity = true) } ?: Plan.Nothing
		} else {
			null
		}

	private fun recordSynced(job: OutboundJob, pageId: String, lastEdited: OffsetDateTime?) {
		// Storing Notion's own last_edited_time here is half of the echo guard: the
		// inbound poller ignores any page not edited later than this.
		when (job.entityType) {
			OutboundEntityType.TEAM -> teams.markSynced(job.entityId, pageId, lastEdited)
			OutboundEntityType.PROJECT -> projects.markSynced(job.entityId, pageId, lastEdited)
			OutboundEntityType.TICKET -> tickets.markSynced(job.entityId, pageId, lastEdited)
			OutboundEntityType.DOC -> docs.markSynced(job.entityId, pageId, lastEdited)
		}
	}

	private fun markState(job: OutboundJob, state: SyncState) {
		when (job.entityType) {
			OutboundEntityType.TEAM -> teams.markSyncState(job.entityId, state)
			OutboundEntityType.PROJECT -> projects.markSyncState(job.entityId, state)
			OutboundEntityType.TICKET -> tickets.markSyncState(job.entityId, state)
			OutboundEntityType.DOC -> docs.markSyncState(job.entityId, state)
		}
	}

	private fun message(error: Exception) = error.message ?: error.javaClass.simpleName

	private companion object {
		val DEPENDENCY_DELAY: Duration = Duration.ofSeconds(3)
		val BOOTSTRAP_DELAY: Duration = Duration.ofSeconds(30)
	}
}

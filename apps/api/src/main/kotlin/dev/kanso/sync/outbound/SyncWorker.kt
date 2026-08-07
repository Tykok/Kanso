package dev.kanso.sync.outbound

import dev.kanso.config.KansoProperties
import dev.kanso.domain.SyncState
import dev.kanso.repo.DocRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.SyncJobRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.sync.SyncEntityType
import dev.kanso.sync.SyncJob
import dev.kanso.sync.SyncOperation
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionRateLimited
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/** Nothing can be pushed before the mirrored databases exist. */
private class NotBootstrapped(kind: String) :
	RuntimeException("No Notion database registered for '$kind' — run the bootstrap first")

/**
 * Drains the outbox towards Notion.
 *
 * Jobs are claimed in their own committed transaction so peers immediately see
 * them as taken; the HTTP call then happens with no transaction open, and the
 * result is recorded in a third. Holding a database connection across a Notion
 * round trip (hundreds of milliseconds, sometimes seconds) would exhaust the pool
 * long before it exhausted the rate limit.
 */
@Component
class SyncWorker(
	private val props: KansoProperties,
	private val jobs: SyncJobRepository,
	private val client: NotionClient,
	private val mapper: NotionMapper,
	private val meta: NotionMetaRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val docs: DocRepository,
	private val tx: TransactionTemplate,
	private val objectMapper: ObjectMapper,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Identifies this process in `sync_jobs.locked_by`, for debugging a stuck queue. */
	private val workerId: String = runCatching { InetAddress.getLocalHost().hostName }
		.getOrDefault("unknown") + "/" + UUID.randomUUID().toString().take(8)

	@Scheduled(fixedDelayString = "\${kanso.sync.outbound.poll-interval-ms:500}")
	fun drain() {
		if (!props.sync.outbound.enabled) return

		val batch = tx.execute { jobs.claimBatch(props.sync.outbound.batchSize, workerId) }.orEmpty()
		if (batch.isEmpty()) return

		// Sequential on purpose: the rate limiter is the bottleneck, so running
		// these in parallel would only queue them inside the limiter while losing
		// the priority order that keeps teams ahead of tickets.
		runBlocking {
			batch.forEach { job ->
				try {
					push(job)
				} catch (e: Exception) {
					handleFailure(job, e)
				}
			}
		}
	}

	@Scheduled(fixedDelay = 60_000)
	fun reclaimAbandoned() {
		if (!props.sync.outbound.enabled) return
		val reclaimed = tx.execute { jobs.reclaimStuck(props.sync.outbound.stuckJobTimeout) } ?: 0
		if (reclaimed > 0) log.warn("Requeued {} job(s) abandoned by a dead worker", reclaimed)
	}

	private suspend fun push(job: SyncJob) {
		if (!client.enabled) {
			// The queue still ran; there is simply nowhere to push. Say so in the
			// row's state so the UI shows "mirror off" rather than "pending" forever.
			tx.executeWithoutResult {
				markState(job, SyncState.DISABLED)
				jobs.markDone(job.id)
			}
			return
		}

		val plan = tx.execute { plan(job) } ?: return
		when (plan) {
			is Plan.Nothing -> tx.executeWithoutResult { jobs.markDone(job.id) }

			is Plan.Archive -> {
				client.updatePage(plan.pageId, archived = true)
				tx.executeWithoutResult {
					if (plan.keepEntity) markState(job, SyncState.SYNCED)
					jobs.markDone(job.id)
				}
			}

			is Plan.Upsert -> {
				val page = if (plan.pageId == null) {
					client.createPage(plan.dataSourceId, plan.properties)
				} else {
					client.updatePage(plan.pageId, plan.properties, archived = false)
				}
				tx.executeWithoutResult {
					recordSynced(job, page.id, page.lastEditedTime)
					jobs.markDone(job.id)
				}
			}
		}
	}

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
	private fun plan(job: SyncJob): Plan {
		if (job.operation == SyncOperation.DELETE) {
			// The row is already gone, so the page id has to come from the payload.
			val pageId = job.payload
				?.let { runCatching { objectMapper.readTree(it).path("notionPageId").asText(null) }.getOrNull() }
			return pageId?.let { Plan.Archive(it, keepEntity = false) } ?: Plan.Nothing
		}

		val dataSourceId = { kind: String ->
			meta.find(kind)?.dataSourceId ?: throw NotBootstrapped(kind)
		}

		return when (job.entityType) {
			SyncEntityType.TEAM -> {
				val team = teams.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, team.mirror.notionPageId, team.archived)
					?: Plan.Upsert(dataSourceId("teams"), team.mirror.notionPageId, mapper.teamProperties(team))
			}

			SyncEntityType.PROJECT -> {
				val project = projects.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, project.mirror.notionPageId, project.archived)
					?: Plan.Upsert(dataSourceId("projects"), project.mirror.notionPageId, mapper.projectProperties(project))
			}

			SyncEntityType.TICKET -> {
				val ticket = tickets.findById(job.entityId) ?: return Plan.Nothing
				archiveOr(job, ticket.mirror.notionPageId, ticket.archived)
					?: Plan.Upsert(dataSourceId("tickets"), ticket.mirror.notionPageId, mapper.ticketProperties(ticket))
			}

			SyncEntityType.DOC -> {
				val doc = docs.findById(job.entityId) ?: return Plan.Nothing
				Plan.Upsert(dataSourceId("docs"), doc.mirrorPageId, mapper.docProperties(doc))
			}
		}
	}

	/**
	 * An archive of something never pushed is a no-op: there is no page to archive,
	 * and creating one only to archive it would litter the mirror.
	 */
	private fun archiveOr(job: SyncJob, pageId: String?, archived: Boolean): Plan? =
		if (job.operation == SyncOperation.ARCHIVE || archived) {
			pageId?.let { Plan.Archive(it, keepEntity = true) } ?: Plan.Nothing
		} else {
			null
		}

	private fun recordSynced(job: SyncJob, pageId: String, lastEdited: OffsetDateTime?) {
		// Storing Notion's own last_edited_time here is half of the echo guard: the
		// inbound poller ignores any page not edited later than this.
		when (job.entityType) {
			SyncEntityType.TEAM -> teams.markSynced(job.entityId, pageId, lastEdited)
			SyncEntityType.PROJECT -> projects.markSynced(job.entityId, pageId, lastEdited)
			SyncEntityType.TICKET -> tickets.markSynced(job.entityId, pageId, lastEdited)
			SyncEntityType.DOC -> docs.markSynced(job.entityId, pageId, lastEdited)
		}
	}

	private fun markState(job: SyncJob, state: SyncState) {
		when (job.entityType) {
			SyncEntityType.TEAM -> teams.markSyncState(job.entityId, state)
			SyncEntityType.PROJECT -> projects.markSyncState(job.entityId, state)
			SyncEntityType.TICKET -> tickets.markSyncState(job.entityId, state)
			SyncEntityType.DOC -> docs.markSyncState(job.entityId, state)
		}
	}

	private fun handleFailure(job: SyncJob, error: Exception) {
		when (error) {
			// Not the job's fault: wait and try again without spending an attempt.
			is DependencyNotReady -> defer(job, error.message ?: "dependency not ready", DEPENDENCY_DELAY)
			is NotBootstrapped -> defer(job, error.message ?: "not bootstrapped", BOOTSTRAP_DELAY)
			is NotionRateLimited -> defer(job, "rate limited", error.retryAfter)

			is NotionApiException -> if (error.retryable) retry(job, error) else fail(job, error)
			else -> retry(job, error)
		}
	}

	private fun defer(job: SyncJob, reason: String, delay: Duration) {
		log.debug("Deferring {} {} for {}s: {}", job.entityType.wire, job.entityId, delay.toSeconds(), reason)
		tx.executeWithoutResult { jobs.defer(job, reason, delay) }
	}

	private fun retry(job: SyncJob, error: Exception) {
		if (job.attempts >= props.sync.outbound.maxAttempts) {
			fail(job, error)
			return
		}
		val delay = backoff(job.attempts)
		log.warn(
			"Sync of {} {} failed (attempt {}/{}), retrying in {}s: {}",
			job.entityType.wire, job.entityId, job.attempts, props.sync.outbound.maxAttempts,
			delay.toSeconds(), error.message,
		)
		tx.executeWithoutResult { jobs.scheduleRetry(job, error.message ?: error.javaClass.simpleName, delay) }
	}

	private fun fail(job: SyncJob, error: Exception) {
		log.error("Giving up on {} {}: {}", job.entityType.wire, job.entityId, error.message)
		tx.executeWithoutResult {
			markState(job, SyncState.FAILED)
			jobs.markFailed(job.id, error.message ?: error.javaClass.simpleName)
		}
	}

	/**
	 * Exponential with jitter. The jitter matters here: without it, a Notion outage
	 * makes every queued job retry in the same instant and immediately trip the
	 * rate limit again.
	 */
	private fun backoff(attempts: Int): Duration {
		val exponential = min(BASE_DELAY_SECONDS shl attempts.coerceAtMost(10), MAX_DELAY_SECONDS)
		val jitter = Random.nextDouble(0.5, 1.5)
		return Duration.ofMillis((exponential * 1000 * jitter).toLong())
	}

	private companion object {
		const val BASE_DELAY_SECONDS = 2L
		const val MAX_DELAY_SECONDS = 300L
		val DEPENDENCY_DELAY: Duration = Duration.ofSeconds(3)
		val BOOTSTRAP_DELAY: Duration = Duration.ofSeconds(30)
	}
}

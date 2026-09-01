package dev.kanso.api

import dev.kanso.config.KansoProperties
import dev.kanso.outbox.Destination
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.service.BadRequestException
import dev.kanso.sync.bootstrap.BootstrapNotPossible
import dev.kanso.sync.bootstrap.NotionBootstrap
import dev.kanso.sync.notion.NotionClient
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class SyncStatusResponse(
	val mirrorEnabled: Boolean,
	val bootstrapped: Boolean,
	val databases: List<MirroredDatabase>,
	val jobs: Map<String, Long>,
	val failed: List<FailedJob>,
	val cursors: List<CursorStatus>,
)

data class MirroredDatabase(val kind: String, val databaseId: String, val dataSourceId: String)
data class FailedJob(val id: Long, val entity: String, val entityId: String, val attempts: Int, val error: String?)
data class CursorStatus(val dataSourceId: String, val lastEditTime: String?, val lastRunAt: String?, val lastError: String?)

/**
 * Operational surface for the mirror. Everything here is diagnosable from the UI
 * or a terminal, because "the mirror is behind" has to be answerable without
 * reading logs.
 *
 * Every queue question it asks is asked of [Destination.NOTION] alone. The outbox is
 * shared now, and a screen that says "the mirror" while counting another consumer's
 * backlog — or whose Retry button silently requeued it — would be answering a
 * different question from the one it was drawn to answer.
 */
@RestController
@RequestMapping("/api/admin")
class SyncAdminController(
	private val props: KansoProperties,
	private val client: NotionClient,
	private val jobs: OutboundJobRepository,
	private val meta: NotionMetaRepository,
	private val bootstrap: NotionBootstrap,
	private val tx: TransactionTemplate,
) {

	@GetMapping("/sync")
	@Transactional(readOnly = true)
	fun status(): SyncStatusResponse {
		val databases = meta.findAll()
		return SyncStatusResponse(
			mirrorEnabled = client.enabled,
			bootstrapped = databases.size >= 4,
			databases = databases.map { MirroredDatabase(it.kind, it.databaseId, it.dataSourceId) },
			jobs = jobs.countsByStatus(Destination.NOTION),
			failed = jobs.findFailed(Destination.NOTION).map {
				FailedJob(it.id, it.entityType.wire, it.entityId.toString(), it.attempts, it.lastError)
			},
			cursors = databases.mapNotNull { db ->
				meta.cursor(db.dataSourceId)?.let {
					CursorStatus(
						it.dataSourceId,
						it.lastEditTime?.toString(),
						it.lastRunAt?.toString(),
						it.lastError,
					)
				}
			},
		)
	}

	/** Creates the four Notion databases. Safe to call twice. */
	@PostMapping("/notion/bootstrap")
	fun bootstrapNotion(@RequestParam(defaultValue = "false") force: Boolean): SyncStatusResponse {
		try {
			runBlocking { bootstrap.run(force) }
		} catch (e: BootstrapNotPossible) {
			throw BadRequestException(e.message ?: "Bootstrap not possible")
		}
		return status()
	}

	/** Rewrites every page from Postgres. The way out of a mirror that drifted. */
	@PostMapping("/notion/reconcile")
	fun reconcile(): Map<String, Any> {
		val queued = tx.execute { bootstrap.enqueueEverything() } ?: 0
		return mapOf("queued" to queued, "mirrorEnabled" to client.enabled)
	}

	@PostMapping("/sync/retry-failed")
	@Transactional
	fun retryFailed(): Map<String, Any> = mapOf("requeued" to jobs.retryAllFailed(Destination.NOTION))
}

package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.outbox.Destination
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.service.BadRequestException
import dev.kanso.sync.bootstrap.BootstrapNotPossible
import dev.kanso.sync.bootstrap.NotionBootstrap
import dev.kanso.sync.notion.NotionClient
import kotlinx.coroutines.runBlocking
import org.springframework.security.access.AccessDeniedException
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
 *
 * **Two of the four are owner-or-admin and two are not, and the split is not the path.**
 * `/api/admin` named the prefix before anything under it checked anybody, which is the
 * trap: creating the mirror's four databases and rewriting every page in the instance are
 * instance configuration, the same act `SetupController` guards on every one of its
 * methods, and they were reachable by any member. The other two stayed open on purpose —
 * see [status] and [retryFailed], each of which says why beside itself, because a rule
 * that differs between siblings has to be argued at each of them or it reads as an
 * oversight in whichever direction the reader guesses.
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
	private val currentUser: CurrentUser,
) {

	/**
	 * Open to anyone signed in, deliberately: this is what the status bar reads, on every
	 * shell in the web app, every ten seconds, for every member. Narrowing it to admins
	 * would take the sync badge away from the people it is drawn for.
	 *
	 * It is not free — [MirroredDatabase] carries Notion database and data-source ids, and
	 * [FailedJob] carries the mirror's own error strings — and that disclosure is the price
	 * of the badge rather than something this method is unaware of. Neither is a credential
	 * and neither reaches Notion without the token, which is never on this wire.
	 */
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

	/**
	 * Creates the four Notion databases. Safe to call twice.
	 *
	 * Owner or admin. It writes into the connected workspace, and with `force` it writes
	 * four *new* databases over the mirror's existing pointers — which is the same act as
	 * connecting Notion in the first place, guarded the same way `SetupController` guards
	 * that. The web app only ever calls it from the wizard and from the connections
	 * section, both of which are already drawn for a configurator, so this refuses nothing
	 * a screen offers.
	 */
	@PostMapping("/notion/bootstrap")
	fun bootstrapNotion(@RequestParam(defaultValue = "false") force: Boolean): SyncStatusResponse {
		requireInstanceAdmin()
		try {
			runBlocking { bootstrap.run(force) }
		} catch (e: BootstrapNotPossible) {
			throw BadRequestException(e.message ?: "Bootstrap not possible")
		}
		return status()
	}

	/**
	 * Rewrites every page from Postgres. The way out of a mirror that drifted.
	 *
	 * Owner or admin, and the least debatable of the two: it enqueues every ticket, team,
	 * project and doc in the instance against one integration's rate limit, so a member
	 * who could reach it could stall the mirror for everybody by pressing a button nothing
	 * in the web app draws. No screen calls it at all — it is the terminal's way out — and
	 * an operational lever with no caller is exactly the one to lock.
	 */
	@PostMapping("/notion/reconcile")
	fun reconcile(): Map<String, Any> {
		requireInstanceAdmin()
		val queued = tx.execute { bootstrap.enqueueEverything() } ?: 0
		return mapOf("queued" to queued, "mirrorEnabled" to client.enabled)
	}

	/**
	 * Open to anyone signed in, deliberately, and the one place this controller's prefix
	 * lies about its contents.
	 *
	 * `Retry` on a failure row is drawn on the inbox, which every member has, and the
	 * endpoint takes no id because the row's own job is almost never alone — see
	 * `useRetryFailedPushes`. What it does is requeue work that already failed: it creates
	 * nothing, destroys nothing, and discloses nothing, so the worst a member achieves
	 * with it is Notion traffic the instance was going to retry anyway.
	 */
	@PostMapping("/sync/retry-failed")
	@Transactional
	fun retryFailed(): Map<String, Any> = mapOf("requeued" to jobs.retryAllFailed(Destination.NOTION))

	/**
	 * `SetupController`'s sentence, said the same way, because a member who trips this and
	 * a member who trips that one have run into one rule and should not be able to tell
	 * the two doors apart. Read off the session's own row rather than off anything a
	 * caller sends.
	 */
	private fun requireInstanceAdmin() {
		if (!currentUser.require().instanceRole.canConfigureInstance) {
			throw AccessDeniedException("Only the instance owner or an admin can change these settings")
		}
	}
}

package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.config.KansoProperties
import dev.kanso.outbox.Destination
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.RequestBaseRepository
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

/**
 * Everything the mirror knows about itself, for whoever configured it.
 *
 * The identifiers are the point of this shape rather than an accident of it: a
 * configurator debugging "why is this database not filling" needs the very
 * [MirroredDatabase.dataSourceId] the cursor errored on, and [FailedJob.error] is the
 * mirror quoting Notion's own refusal back — which is the only place the reason is written
 * down at all. Both are why [SyncSummaryResponse] exists: they cannot travel to every
 * member every ten seconds, and the badge never needed them.
 */
data class SyncStatusResponse(
	val mirrorEnabled: Boolean,
	val bootstrapped: Boolean,
	val databases: List<MirroredDatabase>,
	val jobs: Map<String, Long>,
	val failed: List<FailedJob>,
	val cursors: List<CursorStatus>,
)

/**
 * The same mirror, answered to the question the status bar actually asks: is it on, is it
 * up, and is it behind.
 *
 * Three fields and each is a fact about this instance's own queue. What is *not* here is
 * the deliberate part — no Notion database or data-source id, and no error string — and
 * dropping them costs the badge nothing, which is the whole argument: those ids name pages
 * in a workspace Kanso does not own, and an error string is Notion's sentence about a page
 * whose title it tends to quote. A member reads "behind by 12" without being handed the
 * addressable name of anything.
 *
 * [jobs] carries the failure count under its own `failed` key rather than a field beside
 * it, because [SyncStatusResponse.failed] is capped at fifty rows: a count derived from the
 * list would say "50" for an instance with three hundred, and the badge's one job is to be
 * believed.
 */
data class SyncSummaryResponse(
	val mirrorEnabled: Boolean,
	val bootstrapped: Boolean,
	val jobs: Map<String, Long>,
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
 * **Three of the five are owner-or-admin and two are not, and the split is not the path.**
 * `/api/admin` named the prefix before anything under it checked anybody, which is the
 * trap: creating the mirror's four databases and rewriting every page in the instance are
 * instance configuration, the same act `SetupController` guards on every one of its
 * methods, and they were reachable by any member. The other two stayed open on purpose —
 * see [status] and [retryFailed], each of which says why beside itself, because a rule
 * that differs between siblings has to be argued at each of them or it reads as an
 * oversight in whichever direction the reader guesses.
 *
 * The third guard arrived differently from the first two, and how is worth keeping: [detail]
 * was not a route somebody had left open, it was the tail of [status]'s own answer. Guarding
 * what was there would have taken the sync badge off every member's status bar to hide four
 * ids nothing on that bar reads, so the answer was split in two instead — the question every
 * member asks, answered to every member, and the identifiers moved to a second route for the
 * person who has a use for them. A response too generous is not always a door to close.
 */
@RestController
@RequestMapping("/api/admin")
class SyncAdminController(
	private val props: KansoProperties,
	private val client: NotionClient,
	private val jobs: OutboundJobRepository,
	private val meta: NotionMetaRepository,
	private val requestBases: RequestBaseRepository,
	private val bootstrap: NotionBootstrap,
	private val tx: TransactionTemplate,
	private val currentUser: CurrentUser,
) {

	/**
	 * Open to anyone signed in, deliberately: this is what the status bar reads, on every
	 * shell in the web app, every ten seconds, for every member. Narrowing it to admins
	 * would take the sync badge away from the people it is drawn for.
	 *
	 * It used to answer with [detail]'s whole shape, and that was the disclosure this route
	 * could not justify: Notion database and data-source ids, plus the mirror's own error
	 * strings, sent to every member on a ten-second timer for a badge that reads three
	 * booleans and a count. Not credentials — none of it reaches Notion without the token,
	 * which is never on this wire — but an id names a page in a workspace this instance
	 * does not control, and an error string quotes Notion talking about one. So the answer
	 * was cut where the badge stops reading, and what a member receives now cannot address
	 * anything.
	 *
	 * The row count comes from `countsByStatus`, which is `GROUP BY status` over the whole
	 * queue, not from the fifty rows [detail] lists: "behind" has to stay true past fifty.
	 */
	@GetMapping("/sync")
	@Transactional(readOnly = true)
	fun status(): SyncSummaryResponse = SyncSummaryResponse(
		mirrorEnabled = client.enabled,
		bootstrapped = meta.findAll().size >= 4,
		jobs = jobs.countsByStatus(Destination.NOTION),
	)

	/**
	 * The same reading with the identifiers in it, for the person who connected Notion.
	 *
	 * Owner or admin, on `SetupController`'s argument rather than on a new one: which
	 * database the mirror is writing into, which data source a poller cursor is stuck on
	 * and what Notion said when it refused a page are answers about the instance's
	 * configuration, and they are read by exactly the screens already drawn for a
	 * configurator. A member who lands on the mirror's queue from the inbox still learns
	 * how many writes were refused, from [status] — what they lose is which pages and why,
	 * which is the part that was never theirs to read.
	 *
	 * A separate route rather than a wider [status] for the reader's sake: a shape whose
	 * fields are null half the time teaches nobody which half they are entitled to, and the
	 * two questions have two audiences and two refresh rates.
	 */
	@GetMapping("/sync/detail")
	@Transactional(readOnly = true)
	fun detail(): SyncStatusResponse {
		requireInstanceAdmin()
		val databases = meta.findAll()
		return SyncStatusResponse(
			mirrorEnabled = client.enabled,
			bootstrapped = databases.size >= 4,
			databases = databases.map { MirroredDatabase(it.kind, it.databaseId, it.dataSourceId) },
			jobs = jobs.countsByStatus(Destination.NOTION),
			failed = jobs.findFailed(Destination.NOTION).map {
				FailedJob(it.id, it.entityType.wire, it.entityId.toString(), it.attempts, it.lastError)
			},
			// The requests bases' cursors too, and *only* their cursors — `V37` keeps them out
			// of `databases` above because every row of that list is somewhere the mirror
			// publishes, which a requests base is not, and because `bootstrapped` counts it.
			// A cursor is the other thing entirely: `notion_sync_cursors` is keyed on a data
			// source and knows nothing about which relationship Kanso has with it, so the
			// poller records `last_error` there for a requests base exactly as it does for a
			// mirrored one. Left out of this list, "why is the Demandes base not filling"
			// would be answerable only from the logs — and this route exists precisely so
			// that it is not.
			cursors = (databases.map { it.dataSourceId } + requestBases.findAll().map { it.dataSourceId })
				.mapNotNull { dataSourceId ->
					meta.cursor(dataSourceId)?.let {
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
	 * that. The web app has one caller left now that the wizard is gone — the connections
	 * screen's Save, already drawn for a configurator — so this refuses nothing a screen
	 * offers.
	 */
	@PostMapping("/notion/bootstrap")
	fun bootstrapNotion(@RequestParam(defaultValue = "false") force: Boolean): SyncStatusResponse {
		requireInstanceAdmin()
		try {
			runBlocking { bootstrap.run(force) }
		} catch (e: BootstrapNotPossible) {
			throw BadRequestException(e.message ?: "Bootstrap not possible")
		}
		// The detailed shape, unchanged from before the split: this route is already the
		// configurator's, and the four ids it just created are the one thing worth reading
		// back from it.
		return detail()
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
	 *
	 * **It stays open, and the split that reached [status] deliberately did not reach here.**
	 * That split was about disclosure, and this answers with one number: how many rows went
	 * back in the queue. There is nothing in its response to move behind [requireInstanceAdmin]
	 * and nothing in the act to argue about, so tidying it into the guarded group would take
	 * Retry off the inbox every member has and buy nothing at all.
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

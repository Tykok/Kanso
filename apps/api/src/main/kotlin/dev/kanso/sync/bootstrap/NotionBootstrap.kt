package dev.kanso.sync.bootstrap

import dev.kanso.outbox.Destination
import dev.kanso.outbox.OutboundEntityType
import dev.kanso.outbox.OutboundOperation
import dev.kanso.repo.DocRepository
import dev.kanso.repo.NotionDatabaseRef
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.OutboundJobRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.settings.InstanceSettingsService
import dev.kanso.sync.notion.NotionApiException
import dev.kanso.sync.notion.NotionClient
import dev.kanso.sync.notion.NotionProps
import dev.kanso.sync.notion.NotionSchema
import dev.kanso.sync.notion.RelationDialect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class BootstrapNotPossible(message: String) : RuntimeException(message)

/**
 * Creates the four mirrored databases under a page you share with the integration.
 *
 * Two passes, because a relation property needs its target to exist: create all
 * four databases with their scalar properties, then wire the relations between
 * them. Idempotent — a kind already recorded in `notion_databases` is left alone
 * unless `force` is set.
 */
@Component
class NotionBootstrap(
	private val settings: InstanceSettingsService,
	private val client: NotionClient,
	private val meta: NotionMetaRepository,
	private val teams: TeamRepository,
	private val projects: ProjectRepository,
	private val tickets: TicketRepository,
	private val docs: DocRepository,
	private val jobs: OutboundJobRepository,
	private val tx: TransactionTemplate,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Remembered once discovered, so later relations don't re-probe. */
	@Volatile
	private var relationDialect: RelationDialect? = null

	suspend fun run(force: Boolean = false): List<NotionDatabaseRef> {
		if (!client.enabled) {
			throw BootstrapNotPossible("No NOTION_TOKEN configured; there is nothing to bootstrap")
		}
		// Resolved rather than read from the environment: the setup wizard stores this
		// too, and env-only would ignore whatever the owner just typed in.
		val parentPageId = tx.execute { settings.notionParentPageId() }?.takeIf { it.isNotBlank() }
			?: throw BootstrapNotPossible(
				"No Notion parent page configured. Create a page in Notion, share it with the " +
					"integration, and set it in the setup wizard or as NOTION_PARENT_PAGE_ID — " +
					"Kanso creates its databases under that page."
			)

		val schemas = linkedMapOf(
			"teams" to (TITLES.getValue("teams") to NotionSchema.teams()),
			"projects" to (TITLES.getValue("projects") to NotionSchema.projects()),
			"tickets" to (TITLES.getValue("tickets") to NotionSchema.tickets()),
			"docs" to (TITLES.getValue("docs") to NotionSchema.docs()),
		)

		// Pass 1 — the databases themselves.
		val refs = linkedMapOf<String, NotionDatabaseRef>()
		for ((kind, spec) in schemas) {
			val existing = tx.execute { meta.find(kind) }
			if (existing != null && !force) {
				log.info("Notion '{}' database already registered ({})", kind, existing.databaseId)
				refs[kind] = existing
				continue
			}
			val (title, properties) = spec
			val created = client.createDatabase(parentPageId, title, properties)
			val dataSourceId = created.dataSourceIds.firstOrNull()
				?: throw BootstrapNotPossible("Notion returned no data source for the new '$kind' database")
			tx.executeWithoutResult { meta.save(kind, created.id, dataSourceId, parentPageId) }
			refs[kind] = NotionDatabaseRef(kind, created.id, dataSourceId, parentPageId)
			log.info("Created Notion '{}' database {} (data source {})", kind, created.id, dataSourceId)
		}

		// Pass 2 — relations, now that every target exists.
		val teamsRef = refs.getValue("teams")
		val projectsRef = refs.getValue("projects")
		val ticketsRef = refs.getValue("tickets")
		val docsRef = refs.getValue("docs")

		applyRelations(teamsRef, mapOf(NotionProps.PARENT_TEAM to teamsRef))
		applyRelations(
			projectsRef,
			mapOf(NotionProps.TEAM to teamsRef, NotionProps.DOCS to docsRef),
		)
		applyRelations(
			ticketsRef,
			mapOf(
				NotionProps.TEAM to teamsRef,
				NotionProps.PROJECT to projectsRef,
				NotionProps.DOCS to docsRef,
				// The dependency arrows: self-referencing, the same shape the teams'
				// parent relation uses, pointed back at the Tickets database itself.
				NotionProps.BLOCKED_BY to ticketsRef,
			),
		)

		// Everything that already exists in Postgres now has somewhere to go.
		val queued = tx.execute { enqueueEverything() } ?: 0
		log.info("Bootstrap complete; queued {} entities for the initial push", queued)

		return refs.values.toList()
	}

	/**
	 * Adds relation properties to a data source.
	 *
	 * The 2025-09-03 API points relations at data sources, but the published schema
	 * reference still documents `database_id`. Rather than guess, the newer shape is
	 * tried first and a validation error falls back to the older one — the working
	 * dialect is then logged and reused.
	 */
	private suspend fun applyRelations(target: NotionDatabaseRef, relations: Map<String, NotionDatabaseRef>) {
		if (relations.isEmpty()) return

		val order = relationDialect?.let { listOf(it) }
			?: listOf(RelationDialect.DATA_SOURCE, RelationDialect.DATABASE)

		var lastError: NotionApiException? = null
		for (dialect in order) {
			val properties = relations.mapValues { (_, ref) ->
				NotionSchema.relationTo(ref.databaseId, ref.dataSourceId, dialect)
			}
			try {
				client.updateDataSourceSchema(target.dataSourceId, properties)
				if (relationDialect != dialect) {
					log.info("Notion accepts relation targets as {}", dialect)
					relationDialect = dialect
				}
				return
			} catch (e: NotionApiException) {
				// Only a rejected body is worth retrying with the other spelling;
				// auth or server errors mean something else is wrong.
				if (e.status != 400) throw e
				log.debug("Relation dialect {} rejected: {}", dialect, e.message)
				lastError = e
			}
		}
		throw lastError ?: BootstrapNotPossible("Could not create relations on ${target.kind}")
	}

	/**
	 * Queues a push for every row, in dependency order. Also the way to recover a
	 * mirror that drifted: re-running it rewrites every page from Postgres.
	 */
	fun enqueueEverything(): Int {
		var count = 0
		val push = { type: OutboundEntityType, id: UUID ->
			jobs.enqueue(Destination.NOTION, type, id, OutboundOperation.UPSERT)
			count++
		}
		docs.findAll().forEach { push(OutboundEntityType.DOC, it.id) }
		teams.findAll(includeArchived = true).forEach { push(OutboundEntityType.TEAM, it.id) }
		projects.search(teamIds = null, includeArchived = true).forEach { push(OutboundEntityType.PROJECT, it.id) }
		val batch = tickets.search(includeArchived = true, limit = MAX_RECONCILE)
		batch.forEach { push(OutboundEntityType.TICKET, it.id) }
		if (batch.size == MAX_RECONCILE) {
			log.warn(
				"Reconcile queued only the {} most recently updated tickets. Run it again after this " +
					"batch drains to cover the rest.",
				MAX_RECONCILE,
			)
		}
		return count
	}

	private companion object {
		val TITLES = mapOf(
			"teams" to "Kanso · Teams",
			"projects" to "Kanso · Projects",
			"tickets" to "Kanso · Tickets",
			"docs" to "Kanso · Docs",
		)

		/**
		 * A full reconcile is bounded so one call cannot enqueue an unbounded batch.
		 * When it truncates, the log says so — a silent cap would read as "all done".
		 */
		const val MAX_RECONCILE = 500
	}
}

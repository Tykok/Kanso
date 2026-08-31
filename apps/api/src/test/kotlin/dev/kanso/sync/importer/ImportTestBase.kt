package dev.kanso.sync.importer

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.Team
import dev.kanso.domain.User
import dev.kanso.docs.DocFolderRepository
import dev.kanso.docs.DocPageRepository
import dev.kanso.repo.ImportOriginRepository
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.ProjectRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.ProjectService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketAccess
import dev.kanso.sync.notion.NotionClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The wiring every import test needs, in one place.
 *
 * The service is built by hand rather than autowired for the reason the poller tests
 * give: the client has to be one the test controls. Only [NotionDiscovery] holds it, so
 * everything else — the writer, the access rule, the repositories — is the real bean,
 * and a test that passes has exercised the real ticket and document services.
 */
abstract class ImportTestBase : PostgresTest() {

	@Autowired protected lateinit var meta: NotionMetaRepository
	@Autowired protected lateinit var originRows: ImportOriginRepository
	@Autowired protected lateinit var ticketRows: TicketRepository
	@Autowired protected lateinit var projectRows: ProjectRepository
	@Autowired protected lateinit var pageRows: DocPageRepository
	@Autowired protected lateinit var folderRows: DocFolderRepository
	@Autowired protected lateinit var access: TicketAccess
	@Autowired protected lateinit var writer: ImportWriter
	@Autowired protected lateinit var tx: TransactionTemplate
	@Autowired protected lateinit var teamService: TeamService
	@Autowired protected lateinit var projectService: ProjectService
	@Autowired protected lateinit var users: UserRepository
	@Autowired protected lateinit var encoder: PasswordEncoder

	protected val admin: User by lazy {
		users.createLocalUser(
			email = "import-${UUID.randomUUID()}@kanso.test",
			displayName = "Import admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	protected val team: Team by lazy {
		teamService.create(admin, "Import", "I${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	/** An import service reading [databases] and writing for real. */
	protected fun importerFor(
		client: NotionClient,
		limits: KansoProperties.Notion.Import = KansoProperties.Notion.Import(),
	) = NotionImportService(
		discovery = NotionDiscovery(
			props = KansoProperties(notion = KansoProperties.Notion(import = limits)),
			client = client,
		),
		meta = meta,
		originRows = originRows,
		access = access,
		writer = writer,
		tx = tx,
	)

	protected fun importerFor(vararg databases: FakeDatabase) = importerFor(FakeNotionWorkspace(databases.toList()))

	/**
	 * Everything an import can create, counted.
	 *
	 * Screen 24's central promise is that nothing is written before the third step, and
	 * the only way to assert an absence of writes is to count the rows on both sides of
	 * the call. Counted through the repositories rather than the events, because the suite
	 * is `@Transactional` and rolls back — no test in it ever reaches `pg_notify`.
	 */
	protected data class RowCounts(val tickets: Int, val projects: Int, val pages: Int, val folders: Int)

	protected fun rowCounts() = RowCounts(
		tickets = ticketRows.search(includeArchived = true, limit = 500).size,
		projects = projectRows.search(teamIds = null, includeArchived = true).size,
		pages = pageRows.search(null, null, 500).size,
		folders = folderRows.findByTeam(null).size,
	)

	protected fun plan(vararg rows: Pair<FakeDatabase, ImportTarget>) =
		rows.map { (database, target) -> ImportPlanEntry(database.dataSourceId, target) }

	/**
	 * One plan row carrying the mapping the request would carry for it.
	 *
	 * Separate from [plan] because most tests need no mapping at all, and a helper that
	 * took one would put an empty `ColumnMapping()` on every call site that does not care.
	 * Only the columns, never the values: a test about which column answers which field
	 * has nothing to say about what its options mean.
	 */
	protected fun mapped(
		base: FakeDatabase,
		target: ImportTarget,
		vararg columns: Pair<ImportField, String>,
	) = ImportPlanEntry(base.dataSourceId, target, ColumnMapping(columns = columns.toMap()))
}

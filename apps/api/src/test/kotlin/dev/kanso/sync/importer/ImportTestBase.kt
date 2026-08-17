package dev.kanso.sync.importer

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.Team
import dev.kanso.domain.User
import dev.kanso.repo.NotionMetaRepository
import dev.kanso.repo.UserRepository
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
	@Autowired protected lateinit var access: TicketAccess
	@Autowired protected lateinit var writer: ImportWriter
	@Autowired protected lateinit var tx: TransactionTemplate
	@Autowired protected lateinit var teamService: TeamService
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
		access = access,
		writer = writer,
		tx = tx,
	)

	protected fun importerFor(vararg databases: FakeDatabase) = importerFor(FakeNotionWorkspace(databases.toList()))
}

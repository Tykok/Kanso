package dev.kanso.settings

import dev.kanso.PostgresTest
import dev.kanso.config.SecretBox
import dev.kanso.service.BadRequestException
import dev.kanso.setup.NotionOAuth
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The integration itself pinned in the environment.
 *
 * `NOTION_TOKEN` already answers "which workspace does this instance mirror". These two
 * answer a different question — "which door may a browser be sent to" — and an operator
 * who sets them once stops being asked on every fresh database. That is the whole point:
 * a wizard step that is a button, not a form, from the second instance onward.
 */
@Transactional
@TestPropertySource(
	properties = [
		"kanso.notion.app.client-id=env-client",
		"kanso.notion.app.client-secret=env-secret",
	],
)
class NotionAppFromEnvironmentTest : PostgresTest() {

	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var repo: InstanceSettingsRepository
	@Autowired lateinit var secrets: SecretBox
	@Autowired lateinit var oauth: NotionOAuth

	/** Same reason as `NotionConnectTest`: a singleton cache outlives a rollback. */
	@AfterTransaction
	fun dropCaches() {
		settings.invalidate()
	}

	@Test
	fun `a database that has never seen an integration can still send a browser to Notion`() {
		val url = oauth.authorizeUrl("http://localhost:8080/api/setup/notion/callback", "state-1")

		assertContains(url, "client_id=env-client")
		assertEquals("env-client" to "env-secret", settings.notionApp())
	}

	@Test
	fun `the environment wins over an integration saved through the wizard`() {
		repo.updateNotionApp("stored-client", secrets.encrypt("stored-secret"))
		settings.invalidate()

		assertEquals("env-client" to "env-secret", settings.notionApp())
	}

	@Test
	fun `the wizard is told the integration is managed elsewhere, and still offers the button`() {
		val state = settings.state().notion

		// Both, and they are not the same claim: managed says the fields are not yours to
		// edit, configured says consent can be asked. A screen that saw only the first
		// would hide the button that is the entire reason for pinning these.
		assertTrue(state.appManagedByEnvironment)
		assertTrue(state.appConfigured)
	}

	@Test
	fun `saving an integration is refused while the environment pins one`() {
		val refused = assertFailsWith<BadRequestException> {
			settings.saveNotionApp("client-abc", "secret-xyz")
		}

		// Named, both of them: the person reading this has to know which two lines to
		// unset, and `saveGoogle` sets the sentence they have already read once.
		assertContains(refused.message!!, "NOTION_CLIENT_ID")
		assertContains(refused.message!!, "NOTION_CLIENT_SECRET")
	}
}

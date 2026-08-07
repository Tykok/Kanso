package dev.kanso.settings

import dev.kanso.PostgresTest
import dev.kanso.config.KansoProperties
import dev.kanso.config.SecretBox
import dev.kanso.service.BadRequestException
import dev.kanso.sync.notion.ReloadableNotionClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class InstanceSettingsTest : PostgresTest() {

	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var repo: InstanceSettingsRepository
	@Autowired lateinit var secrets: SecretBox
	@Autowired lateinit var props: KansoProperties
	@Autowired lateinit var notionClient: ReloadableNotionClient
	@Autowired lateinit var objectMapper: ObjectMapper

	/**
	 * The service and the client are singletons while every test rolls back, so a
	 * value cached inside a transaction that never committed would leak into the
	 * next test. After the transaction, not after the test: reloading while it is
	 * still open would cache exactly the value about to be discarded.
	 */
	@AfterTransaction
	fun dropCaches() {
		settings.invalidate()
		notionClient.reload()
	}

	@Test
	fun `a saved secret survives encryption and comes back intact`() {
		settings.saveNotion("ntn_top_secret", "page-abc")
		settings.invalidate()

		assertEquals("ntn_top_secret", settings.notionToken())
		assertEquals("page-abc", settings.notionParentPageId())

		val stored = String(repo.read().notionTokenEnc!!, Charsets.ISO_8859_1)
		assertFalse(stored.contains("ntn_top_secret"), "the column must hold ciphertext, not the token")
	}

	@Test
	fun `saving only the page id keeps the token`() {
		settings.saveNotion("ntn_top_secret", "page-abc")
		settings.saveNotion(null, "page-def")

		assertEquals("ntn_top_secret", settings.notionToken(), "the UI never re-sends a stored secret")
		assertEquals("page-def", settings.notionParentPageId())
	}

	@Test
	fun `the state the API answers with carries no secret`() {
		settings.saveNotion("ntn_top_secret", "page-abc")
		settings.saveGoogle("client-id.apps.googleusercontent.com", "GOCSPX-very-secret")

		val json = objectMapper.writeValueAsString(settings.state())

		assertFalse(json.contains("ntn_top_secret"), "state was $json")
		assertFalse(json.contains("GOCSPX-very-secret"), "state was $json")
		assertTrue(json.contains("client-id.apps.googleusercontent.com"), "a client id is not a secret")
		assertTrue(settings.state().notion.configured)
		assertTrue(settings.state().google.configured)
	}

	@Test
	fun `an environment token wins over a stored one and is reported as such`() {
		settings.saveNotion("ntn_stored", "page-stored")

		// A second service over the same row, as if NOTION_TOKEN were set: operator
		// config and wizard config must not be able to silently disagree.
		val withEnv = InstanceSettingsService(
			repo,
			props.copy(notion = props.notion.copy(token = "ntn_from_env", parentPageId = "page-from-env")),
			secrets,
		)

		assertEquals("ntn_from_env", withEnv.notionToken())
		assertEquals("page-from-env", withEnv.notionParentPageId())
		assertTrue(withEnv.state().notion.managedByEnvironment)
		assertFalse(settings.state().notion.managedByEnvironment, "nothing is set in this test's environment")
	}

	@Test
	fun `saving a token the environment already provides is refused, not ignored`() {
		val withEnv = InstanceSettingsService(
			repo,
			props.copy(notion = props.notion.copy(token = "ntn_from_env")),
			secrets,
		)

		assertFailsWith<BadRequestException>("a save that could never take effect has to say so") {
			withEnv.saveNotion("ntn_from_the_wizard", "page-abc")
		}
	}

	@Test
	fun `saving a token flips the client from the no-op to the HTTP implementation`() {
		assertFalse(notionClient.enabled, "no token configured yet")

		settings.saveNotion("ntn_top_secret", "page-abc")
		notionClient.reload()

		assertTrue(notionClient.enabled, "the wizard must not end in a restart")
	}
}

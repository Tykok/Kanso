package dev.kanso.settings

import dev.kanso.PostgresTest
import dev.kanso.service.BadRequestException
import dev.kanso.setup.NotionOAuth
import dev.kanso.sync.notion.ReloadableNotionClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connecting Notion by consent, as far as a test can follow it: everything up to the
 * browser leaving for Notion, and everything after a grant comes back. The round trip
 * itself is Notion's, and nothing here pretends to stand in for it.
 */
@Transactional
class NotionConnectTest : PostgresTest() {

	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var repo: InstanceSettingsRepository
	@Autowired lateinit var oauth: NotionOAuth
	@Autowired lateinit var notionClient: ReloadableNotionClient

	/** Same reason as `InstanceSettingsTest`: a singleton cache outlives a rollback. */
	@AfterTransaction
	fun dropCaches() {
		settings.invalidate()
		notionClient.reload()
	}

	@Test
	fun `an instance with no integration is told what to create, not sent to a consent screen`() {
		val refused = assertFailsWith<BadRequestException> {
			oauth.authorizeUrl("http://localhost:8080/api/setup/notion/callback", "state-1")
		}
		// The sentence has to name the thing to go and make. A redirect to Notion with an
		// unknown client id would be refused *there*, in Notion's words, on Notion's page,
		// to somebody who was configuring Kanso.
		assertContains(refused.message!!, "public integration")
	}

	@Test
	fun `the consent url carries the client id, the callback and the page picker`() {
		settings.saveNotionApp("client-abc", "secret-xyz")
		settings.invalidate()

		val url = oauth.authorizeUrl("https://kanso.example/api/setup/notion/callback", "state-2")

		assertTrue(url.startsWith("https://api.notion.com/v1/oauth/authorize?"), url)
		assertContains(url, "client_id=client-abc")
		assertContains(url, "response_type=code")
		// `owner=user` is what makes Notion offer the page picker. Without it the screen
		// asks for nothing, and the whole point of the button is that screen.
		assertContains(url, "owner=user")
		assertContains(url, "state=state-2")
		assertContains(url, "redirect_uri=https%3A%2F%2Fkanso.example%2Fapi%2Fsetup%2Fnotion%2Fcallback")
		// The secret is used to authenticate the *exchange*, over HTTP Basic. A secret in
		// a URL is a secret in a browser history, a proxy log and a Referer header.
		assertFalse(url.contains("secret-xyz"), "the client secret must never reach the browser")
	}

	@Test
	fun `two states are never the same`() {
		assertNotEquals(oauth.newState(), oauth.newState())
	}

	@Test
	fun `saving the integration does not connect anything`() {
		settings.saveNotionApp("client-abc", "secret-xyz")
		settings.invalidate()

		val state = settings.state().notion
		// The two questions the wizard has to be able to tell apart: an integration exists
		// for consent to be asked through, and no consent has been given yet.
		assertTrue(state.appConfigured)
		assertFalse(state.configured)
		assertNull(state.workspaceName)
	}

	@Test
	fun `a grant stores the token as ciphertext and names the workspace it came from`() {
		settings.saveNotionGrant("ntn_granted_token", "ws-1", "Pictarine", "bot-9")
		settings.invalidate()

		assertEquals("ntn_granted_token", settings.notionToken())

		val state = settings.state().notion
		assertTrue(state.configured)
		// A token names nothing a person recognises; this is what the settings screen prints.
		assertEquals("Pictarine", state.workspaceName)

		val stored = repo.read()
		assertEquals("ws-1", stored.notionWorkspaceId)
		assertEquals("bot-9", stored.notionBotId)
		assertFalse(
			String(stored.notionTokenEnc!!, Charsets.ISO_8859_1).contains("ntn_granted_token"),
			"the column must hold ciphertext",
		)
	}

	@Test
	fun `a grant does not disturb the parent page already chosen`() {
		settings.saveNotion("ntn_pasted", "page-chosen-earlier")
		settings.saveNotionGrant("ntn_granted_token", "ws-1", "Pictarine", "bot-9")
		settings.invalidate()

		// Re-connecting is not re-configuring: the page the databases live under is a
		// separate decision and survives a new token.
		assertEquals("page-chosen-earlier", settings.notionParentPageId())
		assertEquals("ntn_granted_token", settings.notionToken())
	}

	@Test
	fun `saving the integration keeps the secret when the browser sends none back`() {
		settings.saveNotionApp("client-abc", "secret-xyz")
		// The UI is told a secret exists and never its value, so this is what a second save
		// looks like — a changed id, and nothing to re-send for the secret.
		settings.saveNotionApp("client-def", null)
		settings.invalidate()

		assertTrue(settings.state().notion.appConfigured)
		assertEquals("client-def", repo.read().notionClientId)
	}
}

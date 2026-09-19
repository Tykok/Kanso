package dev.kanso.api

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.settings.InstanceSettingsService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/setup/state` is the one route on [SetupController] without a
 * `requireInstanceAdmin()`, and this is what it may say to whoever is on the other end.
 *
 * It has to stay open: the sign-in screen decides whether to offer the wizard from
 * `needsOwner`, and it draws that before anybody has signed in. What it used to answer with
 * besides was the whole connection state — `notion.parentPageId`, which names a page in a
 * workspace this instance does not own, `notion.workspaceName`, which names the
 * organisation, and `google.clientId` — to any `curl` that could reach the instance.
 * `SyncAdminController` had already split `status` from `detail` to keep ids of exactly that
 * kind from signed-in *members*; this route was handing them to strangers.
 *
 * Three callers because the rule has three answers and only the middle one is surprising: a
 * member is signed in, is not a stranger, and still may not see them — the line is
 * `canConfigureInstance`, the same line every other route on this controller draws.
 *
 * The booleans are asserted to *survive*, which is the half a redaction gets wrong. The app
 * shell and the onboarding checklist read `configured` on every screen a member sees; a fix
 * that emptied the whole object would have told them Notion was not set up, and the symptom
 * would have been a checklist quietly asking for work already done.
 */
@Transactional
class SetupStateDisclosureTest : PostgresTest() {

	@Autowired lateinit var controller: SetupController
	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "ssd-${UUID.randomUUID()}@kanso.test",
		displayName = "Setup state ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	/** Stands in for the auth filter, which has no servlet request here to run inside. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
	}

	/** Same reason as `InstanceSettingsTest`: a singleton cache outlives a rollback. */
	@AfterTransaction
	fun dropCaches() {
		settings.invalidate()
	}

	/**
	 * A workspace worth not naming. `saveNotionGrant` is the path a real consent takes, and
	 * the only one that stores a workspace name — a pasted token names nothing.
	 */
	private fun connectNotion() {
		settings.saveNotion(token = "secret_notion_token", parentPageId = PARENT_PAGE)
		settings.saveNotionGrant(
			token = "secret_notion_token",
			workspaceId = "ws-1",
			workspaceName = WORKSPACE,
			botId = "bot-1",
		)
		settings.saveGoogle(clientId = CLIENT_ID, clientSecret = "secret")
	}

	@Test
	fun `a stranger is told whether to offer the wizard and nothing that names anybody`() {
		connectNotion()
		SecurityContextHolder.clearContext()

		val state = controller.state()

		assertNull(state.notion.parentPageId, "a page id in a workspace this instance does not own")
		assertNull(state.notion.workspaceName, "the name of the organisation running this instance")
		assertNull(state.google.clientId, "the instance's OAuth client, to an unauthenticated caller")
		assertTrue(state.notion.configured, "the booleans are what the sign-in screen came for")
	}

	@Test
	fun `a member is signed in and still not shown them`() {
		connectNotion()
		actAs(user(InstanceRole.MEMBER))

		val state = controller.state()

		assertNull(state.notion.parentPageId, "`canConfigureInstance` is the line, not `is signed in`")
		assertNull(state.notion.workspaceName, "same line: a member cannot change these settings either")
		assertNull(state.google.clientId, "same line")
		assertTrue(
			state.notion.configured,
			"`app-shell` and the onboarding checklist read this on every screen a member sees",
		)
	}

	@Test
	fun `an admin is shown the settings they are the one who can change`() {
		connectNotion()
		actAs(user(InstanceRole.ADMIN))

		val state = controller.state()

		assertEquals(PARENT_PAGE, state.notion.parentPageId, "the wizard's own field, prefilled")
		assertEquals(WORKSPACE, state.notion.workspaceName, "`notion-connect` says which workspace")
		assertEquals(CLIENT_ID, state.google.clientId, "`connections-section` prefills this field")
	}

	private companion object {
		const val PARENT_PAGE = "1f2c0000000080008000000000000001"
		const val WORKSPACE = "Acme Engineering"
		const val CLIENT_ID = "8123.apps.googleusercontent.com"
	}
}

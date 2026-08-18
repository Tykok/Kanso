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
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The Google credential check as the controller offers it: who may ask, and what
 * happens when there is nothing to ask about.
 *
 * The round trip itself is `GoogleCredentialProbeTest`'s, with the endpoint answered by
 * hand. Everything here stops before the network on purpose — a refusal is a sentence,
 * not a request to Google — so nothing in this class can reach the internet.
 */
@Transactional
class GoogleSetupTest : PostgresTest() {

	@Autowired lateinit var controller: SetupController
	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "gs-${UUID.randomUUID()}@kanso.test",
		displayName = "Google setup ${role.wire}",
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

	@Test
	fun `a member cannot check the instance's Google credentials`() {
		actAs(user(InstanceRole.MEMBER))

		// Same guard as its neighbours: the check speaks to Google as the instance, and
		// its answer is a statement about instance configuration.
		assertFailsWith<AccessDeniedException> {
			controller.testGoogle(GoogleTestRequest("some-client-id", "some-secret"))
		}
	}

	@Test
	fun `nothing submitted and nothing stored is a refusal rather than a round trip`() {
		actAs(user(InstanceRole.ADMIN))

		val answer = controller.testGoogle(GoogleTestRequest())

		assertFalse(answer.ok)
		assertContains(answer.detail, "client id")
	}

	@Test
	fun `an id with no secret anywhere names the secret as what is missing`() {
		actAs(user(InstanceRole.ADMIN))

		// The UI never receives a stored secret to send back, so this is what a check
		// looks like on an instance that has saved an id and nothing else.
		val answer = controller.testGoogle(GoogleTestRequest(clientId = "1234.apps.googleusercontent.com"))

		assertFalse(answer.ok)
		assertContains(answer.detail, "client secret")
	}
}

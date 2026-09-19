package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.settings.PreferencesService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * `LocalAuthTest` and `InvitationTest` each assert the same stamp for the two
 * account-creation paths that go through the wizard's own endpoints. These are the
 * other two: OIDC sign-in and the dev-header filter both create a user row through
 * this class, neither ever calls `claimOwner` or `InvitationService.accept`, and an
 * unstamped row reaching `app-shell.tsx`'s routing guard is sent to a `/setup` that,
 * now one screen, has nothing left to ask it — so it bounces between `/` and `/setup`
 * forever instead of opening the board.
 */
@Transactional
class UserProvisioningTest : PostgresTest() {

	@Autowired lateinit var provisioning: UserProvisioning
	@Autowired lateinit var preferences: PreferencesService

	@Test
	fun `signing in through OIDC stamps the onboarding`() {
		val user = provisioning.provision(
			provider = "google",
			subject = UUID.randomUUID().toString(),
			email = "oidc-${UUID.randomUUID()}@kanso.test",
			displayName = "OIDC",
			avatarUrl = null,
		)

		assertNotNull(preferences.get(user.id).onboardedAt)
	}

	@Test
	fun `the dev-header filter stamps the onboarding too`() {
		val user = provisioning.findOrCreateByEmail("dev-${UUID.randomUUID()}@kanso.test")

		assertNotNull(preferences.get(user.id).onboardedAt)
	}
}

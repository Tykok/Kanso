package dev.kanso.auth

import dev.kanso.PostgresTest
import dev.kanso.config.KansoProperties
import dev.kanso.settings.InstanceSettingsService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.transaction.AfterTransaction
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug this exists for: a Google client saved in the wizard was offered until the next
 * restart and then was not.
 *
 * `DynamicClientRegistrationRepository` builds itself from `KansoProperties`, the save
 * reloaded it, and nothing read `instance_settings` again — so the process came back with
 * an empty list and the sign-in screen said no provider was configured, over a client id
 * and secret that had been in the database the whole time. On an instance whose admin signs
 * in with Google that is a locked door, and the only way through it is a shell.
 *
 * The restart is played rather than performed: a fresh process leaves the repository
 * holding exactly what its constructor put there, which is what the environment says, and
 * that is one line to reproduce. What cannot be played here is the `ApplicationReadyEvent`
 * wiring — every context this suite starts fires it already.
 */
@Transactional
class ConfiguredProvidersTest : PostgresTest() {

	@Autowired lateinit var providers: ConfiguredProviders
	@Autowired lateinit var registrations: DynamicClientRegistrationRepository
	@Autowired lateinit var settings: InstanceSettingsService
	@Autowired lateinit var props: KansoProperties

	/**
	 * The repository and the settings cache are singletons while every test rolls back, so
	 * a registration built from a row that never committed would be offered to the next
	 * test. After the transaction, not after the test: refreshing while it is still open
	 * would cache exactly the value about to be discarded.
	 */
	@AfterTransaction
	fun dropCaches() {
		settings.invalidate()
		providers.refresh()
	}

	@Test
	fun `a client saved in the wizard is offered again after a restart`() {
		settings.saveGoogle("kanso.apps.googleusercontent.com", "a-secret")

		// What a fresh process holds: the environment, which configures nothing here.
		registrations.reload(OidcRegistrations.from(props.auth))
		assertTrue(registrations.current().isEmpty(), "the environment configures no provider in tests")

		providers.refresh()

		assertEquals(listOf("google"), registrations.current().map { it.registrationId })
		assertEquals(
			"kanso.apps.googleusercontent.com",
			registrations.current().single().clientId,
			"the id offered has to be the stored one, not a leftover",
		)
	}

	/** Nothing stored and nothing in the environment is a real state, not an error. */
	@Test
	fun `an instance nobody has configured offers nothing`() {
		providers.refresh()

		assertTrue(registrations.current().isEmpty())
	}
}

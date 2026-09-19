package dev.kanso.auth

import dev.kanso.config.KansoProperties
import dev.kanso.settings.InstanceSettingsService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/** Only providers with both an id and a secret are offered. */
object OidcRegistrations {

	/**
	 * Google's token endpoint, as the sign-in flow itself knows it.
	 *
	 * Read off a built registration rather than written out a second time: this is the URL
	 * `GoogleCredentialProbe` authenticates against, and a copy of it could drift from the
	 * one [from] hands to Spring Security — at which point a green credential check would
	 * say nothing about the flow it stands in for. The two placeholders below are never
	 * sent anywhere; the builder only insists that both fields be present before it will
	 * hand over the provider details.
	 */
	val googleTokenUri: String by lazy {
		CommonOAuth2Provider.GOOGLE.getBuilder("google")
			.clientId("unused")
			.clientSecret("unused")
			.build()
			.providerDetails
			.tokenUri
	}

	fun from(auth: KansoProperties.Auth): List<ClientRegistration> = buildList {
		if (auth.google.configured) {
			add(
				CommonOAuth2Provider.GOOGLE.getBuilder("google")
					.clientId(auth.google.clientId)
					.clientSecret(auth.google.clientSecret)
					.build()
			)
		}
		if (auth.github.configured) {
			add(
				CommonOAuth2Provider.GITHUB.getBuilder("github")
					.clientId(auth.github.clientId)
					.clientSecret(auth.github.clientSecret)
					.build()
			)
		}
	}
}

/**
 * Which providers exist is a runtime question, not a bean-definition one: the
 * setup wizard can add Google to a running instance, and the conditional bean this
 * replaces would have made that a restart.
 *
 * So the bean always exists and may hold nothing. Zero registrations is not a
 * failure — it is what a fresh instance looks like — which is why the map is kept
 * here rather than delegated to `InMemoryClientRegistrationRepository`, whose
 * constructor rejects an empty list.
 */
@Component
class DynamicClientRegistrationRepository(props: KansoProperties) : ClientRegistrationRepository {

	/**
	 * Replaced wholesale rather than mutated in place, so a request in flight sees
	 * either the old set or the new one and never a half-applied reload. That plus
	 * `@Volatile` is the whole of the thread safety needed; a lock on the read path
	 * would buy nothing.
	 */
	@Volatile
	private var byId: Map<String, ClientRegistration> = index(OidcRegistrations.from(props.auth))

	override fun findByRegistrationId(registrationId: String): ClientRegistration? = byId[registrationId]

	fun current(): List<ClientRegistration> = byId.values.toList()

	fun reload(registrations: List<ClientRegistration>) {
		byId = index(registrations)
	}

	private fun index(registrations: List<ClientRegistration>): Map<String, ClientRegistration> =
		registrations.associateBy { it.registrationId }
}

/**
 * The providers *this instance* has, which is not the set the environment has.
 *
 * [DynamicClientRegistrationRepository] is built from `KansoProperties` and from nothing
 * else, and until something reloads it that is the whole set for the life of the process.
 * Saving Google in the wizard reloaded it, so the button appeared without a restart — and
 * then the next restart built the repository from the environment again and the credentials
 * stayed in `instance_settings`, unread. The instance came back up telling whoever opened
 * it that no sign-in provider was configured, which on an instance whose only admin signs
 * in with Google is a locked door.
 *
 * So the rebuild has two callers now: the save, and the start. Neither is the primary one;
 * the setting is stored in the database and the repository is in memory, and this is what
 * carries the one into the other.
 */
@Component
class ConfiguredProviders(
	private val settings: InstanceSettingsService,
	private val registrations: DynamicClientRegistrationRepository,
	private val props: KansoProperties,
) {

	/**
	 * After the context is ready rather than during its construction: this reads the
	 * database, and at construction time Flyway has not necessarily migrated it — while
	 * nothing can sign in before the context serves requests anyway.
	 */
	@EventListener(ApplicationReadyEvent::class)
	fun onApplicationReady() = refresh()

	/**
	 * Rebuilds the whole list rather than only Google: `reload` replaces the repository's
	 * contents, so a GitHub registration coming from the environment would otherwise
	 * disappear the moment someone saves Google credentials.
	 *
	 * The environment still wins — `ResolvedSettings` decides that, not this — so an
	 * instance pinned by `GOOGLE_CLIENT_ID` rebuilds to the same list it booted with.
	 */
	fun refresh() {
		val resolved = settings.resolved()
		registrations.reload(
			OidcRegistrations.from(
				props.auth.copy(
					google = KansoProperties.Provider(
						clientId = resolved.googleClientId.orEmpty(),
						clientSecret = resolved.googleClientSecret.orEmpty(),
					),
				)
			)
		)
	}
}

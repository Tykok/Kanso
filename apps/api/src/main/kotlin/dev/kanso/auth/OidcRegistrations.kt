package dev.kanso.auth

import dev.kanso.config.KansoProperties
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/** Only providers with both an id and a secret are offered. */
object OidcRegistrations {

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

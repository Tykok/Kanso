package dev.kanso.oauth

import dev.kanso.config.KansoProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository

/**
 * The beans behind `/connect/register`, kept out of [AuthorizationServerConfig] so that
 * the file describing the filter chain stays about the filter chain.
 */
@Configuration
class OAuthRegistrationConfig {

	@Bean
	fun redirectUriPolicy(properties: KansoProperties): RedirectUriPolicy =
		RedirectUriPolicy(properties.oauth.registration.allowedRedirectHosts.map { it.lowercase() }.toSet())

	@Bean
	fun registrationRateLimit(properties: KansoProperties): RegistrationRateLimit =
		RegistrationRateLimit(properties.oauth.registration.perIpPerHour)

	@Bean
	fun clientRegistrationService(
		clients: RegisteredClientRepository,
		policy: RedirectUriPolicy,
		properties: KansoProperties,
		jdbc: JdbcClient,
	): ClientRegistrationService = ClientRegistrationService(
		clients = clients,
		policy = policy,
		// The repository has no count, and the service must stay constructible without a
		// database — so the query lives here and the rule lives there.
		clientCount = {
			jdbc.sql("SELECT count(*) FROM oauth2_registered_client").query(Int::class.java).single() ?: 0
		},
		maxClients = properties.oauth.registration.maxClients,
	)
}

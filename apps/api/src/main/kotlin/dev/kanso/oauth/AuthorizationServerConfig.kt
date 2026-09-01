package dev.kanso.oauth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter

/**
 * The library's chain, ahead of Kanso's own.
 *
 * Two chains rather than one set of rules, because they answer to different callers:
 * this one serves a machine that has no session and is holding a code or a token, and
 * `SecurityConfig`'s serves a browser holding a cookie. Ordered rather than merged —
 * the configurer installs a dozen filters of its own, and interleaving them with
 * `oauth2Login`'s would be a chain nobody can read.
 *
 * `securityMatcher` is what keeps them apart, and it is the load-bearing line in this
 * file: Kanso already serves `/oauth2/authorization/{provider}` for signing *in* with
 * Google, and this chain serves `/oauth2/authorize` for signing *out* to an agent.
 * Adjacent prefixes, opposite directions. `configurer.endpointsMatcher` derives the
 * matcher from the library's own endpoint settings, which is exactly right and exactly
 * why we do not write a prefix here by hand.
 *
 * `AuthorizationServerSettings` is declared because nothing else declares it.
 * `OAuth2AuthorizationServerConfiguration` supplies a default only when that class is
 * imported, which it is not, and Boot's autoconfiguration is gated on
 * `spring.security.oauth2.authorizationserver.client.*` properties Kanso does not set —
 * it registers its client in code. So without this bean the context does not start, and
 * that is the real reason this task cannot be split from the ones that follow it.
 */
@Configuration
class AuthorizationServerConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	fun authorizationServerChain(http: HttpSecurity): SecurityFilterChain {
		val configurer = OAuth2AuthorizationServerConfigurer()
		http
			.securityMatcher(configurer.endpointsMatcher)
			.with(configurer) { }
			// Disabled for the same bounded reason `SecurityConfig` gives, and because
			// `POST /oauth2/token` is a machine call carrying no cookie at all — there is
			// no ambient credential for a forged form to ride.
			.csrf { it.disable() }
			// Only `/oauth2/authorize` is actually reached by this rule. The token,
			// revocation and registration filters write their own response and never
			// continue the chain, so they are never asked for an authentication.
			.authorizeHttpRequests { it.anyRequest().authenticated() }
			.addFilterBefore(AgentPrincipalFilter(), AbstractPreAuthenticatedProcessingFilter::class.java)
		return http.build()
	}

	@Bean
	fun authorizationServerSettings(): AuthorizationServerSettings =
		AuthorizationServerSettings.builder().build()
}

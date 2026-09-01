package dev.kanso.oauth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.core.OAuth2Token
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator
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
/**
 * Said once, in one place, because it is said in three: a startup log, the `/api/mcp`
 * refusal, and the settings screen.
 */
const val DEV_MODE_REFUSAL: String =
	"Kanso is running with KANSO_AUTH_MODE=dev, where identity comes from an unverified " +
		"header. Connecting an agent is disabled: an authorisation server behind that would " +
		"issue durable tokens to anyone who can reach it. Switch to oidc to enable it."

/**
 * Where the library sends the browser to ask the question — a page served by the API
 * itself, not by `apps/web`. The browser is already on the API origin when it reaches
 * `/oauth2/authorize`, so the decision posts back same-origin with the session cookie
 * attached, which a `SameSite=Lax` cookie will not do cross-site.
 */
const val CONSENT_PAGE: String = "/oauth/consent"

@Configuration
class AuthorizationServerConfig {

	/**
	 * Absent in dev mode, which is the whole of the refusal: with no chain there is no
	 * `/oauth2/authorize` and no `/oauth2/token`, so there is nothing to reach rather
	 * than something that says no.
	 *
	 * The gate is on this bean and not on the class. Gating the configuration would take
	 * `OAuth2AuthorizationService` with it, and `McpBearerFilter` needs that bean present
	 * in order to look a token up and reject it — a refusal path that cannot be
	 * constructed is not a refusal path.
	 *
	 * An expression rather than `havingValue = "oidc"`, because that comparison is
	 * literal: `KANSO_AUTH_MODE=OIDC` would leave the door closed and `/api/mcp` would
	 * then explain that the instance is in dev mode, which would be false.
	 * `Auth.effectiveMode` lowercases before it reads; so does this.
	 */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@ConditionalOnExpression("!'\${kanso.auth.mode:oidc}'.equalsIgnoreCase('dev')")
	fun authorizationServerChain(http: HttpSecurity, clients: RegisteredClientRepository): SecurityFilterChain {
		LoggerFactory.getLogger(javaClass).info("Authorisation server enabled — agents may connect by consent")
		val configurer = OAuth2AuthorizationServerConfigurer()
		http
			.securityMatcher(configurer.endpointsMatcher)
			.with(configurer) { server ->
				// The hour after which the connector would otherwise stop working. Every client
				// here is public, and none of the library's five converters recognises one on a
				// `grant_type=refresh_token` request — so the request arrives unauthenticated,
				// `anyRequest().authenticated()` below refuses it, and the answer is a bare 401.
				// Verified against a running instance. [PublicClientRefresh] argues the shape and
				// names the rule this does *not* satisfy.
				server.clientAuthentication { clientAuth ->
					clientAuth.authenticationConverter(PublicClientRefreshConverter())
					// Added first, which is what the configurer does with anything added here, and
					// harmless: it answers null for every request that is not its own.
					clientAuth.authenticationProvider(PublicClientRefreshProvider(clients))
				}
				server.authorizationEndpoint { endpoint ->
					endpoint.consentPage(CONSENT_PAGE)
					// RFC 9207. The library builds the redirect without `iss`; these two
					// build it with one, on the granted response and on the refused one.
					endpoint.authorizationResponseHandler(IssuerAppendingSuccessHandler())
					endpoint.errorResponseHandler(IssuerAppendingFailureHandler())
					// RFC 8707, first enforcement point. The provider is reached only
					// here — `addAuthorizationCodeRequestAuthenticationValidator` is
					// package-private, so replacing the validator on the provider is the
					// supported way in.
					endpoint.authenticationProviders { providers ->
						providers.forEach { provider ->
							if (provider is OAuth2AuthorizationCodeRequestAuthenticationProvider) {
								// The library's *whole* default, not just its redirect-uri
								// half. setAuthenticationValidator replaces rather than
								// adds, so delegating to one part would silently drop
								// scope validation — a client could then ask for a scope
								// it was never registered for.
								provider.setAuthenticationValidator(
									ResourceValidator(OAuth2AuthorizationCodeRequestAuthenticationValidator()),
								)
							}
						}
					}
				}
				server.authorizationServerMetadataEndpoint { metadata ->
					metadata.authorizationServerMetadataCustomizer { document ->
						ISS_PARAMETER_ADVERTISED.accept(document)
						// The library omits `registration_endpoint` because its own
						// registration is disabled — verified on the running server. Ours
						// is not, and a client that cannot discover the endpoint will not
						// use it.
						document.clientRegistrationEndpoint(
							AuthorizationServerContextHolder.getContext().issuer.trimEnd('/') + "/connect/register",
						)
					}
				}
			}
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

	/**
	 * Persisted, not in memory. A client that registered itself must survive a restart —
	 * otherwise every deploy silently disconnects every agent, and the member's only
	 * symptom is a tool that stopped working.
	 *
	 * These three are deliberately *outside* the dev-mode gate. They are inert with no
	 * endpoint in front of them, and `McpBearerFilter` needs the authorisation service
	 * present in order to look a token up and refuse it.
	 */
	@Bean
	fun registeredClientRepository(jdbc: JdbcOperations): RegisteredClientRepository =
		JdbcRegisteredClientRepository(jdbc)

	@Bean
	fun authorizationService(
		jdbc: JdbcOperations,
		clients: RegisteredClientRepository,
	): OAuth2AuthorizationService = JdbcOAuth2AuthorizationService(jdbc, clients)

	@Bean
	fun authorizationConsentService(
		jdbc: JdbcOperations,
		clients: RegisteredClientRepository,
	): OAuth2AuthorizationConsentService = JdbcOAuth2AuthorizationConsentService(jdbc, clients)

	/**
	 * The library's default generator, with one delegate replaced.
	 *
	 * `OAuth2ConfigurerUtils.getTokenGenerator` builds a
	 * `DelegatingOAuth2TokenGenerator(jwt?, access, refresh)` when no bean of this type
	 * exists, and its refresh delegate is the one that refuses a public client. This is
	 * that list with [PublicClientRefreshTokenGenerator] in its place and nothing else
	 * changed — the JWT half is kept exactly because it is not this file's business:
	 * Boot autoconfigures a `JwtEncoder` from the JWKS this server publishes, and
	 * dropping it would silently take self-contained access tokens away from a future
	 * client that asked for one.
	 *
	 * Outside the dev-mode gate, with the three below it and for the same reason: with no
	 * chain in front of it there is nothing to generate a token for.
	 */
	@Bean
	fun tokenGenerator(jwtEncoder: ObjectProvider<JwtEncoder>): OAuth2TokenGenerator<out OAuth2Token> {
		val access = OAuth2AccessTokenGenerator()
		val refresh = PublicClientRefreshTokenGenerator()
		return jwtEncoder.getIfAvailable()
			?.let { DelegatingOAuth2TokenGenerator(JwtGenerator(it), access, refresh) }
			?: DelegatingOAuth2TokenGenerator(access, refresh)
	}

	@Bean
	fun authorizationServerSettings(): AuthorizationServerSettings =
		AuthorizationServerSettings.builder().build()
}

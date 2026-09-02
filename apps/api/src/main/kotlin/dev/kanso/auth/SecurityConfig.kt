package dev.kanso.auth

import dev.kanso.config.KansoProperties
import dev.kanso.mcp.McpBearerFilter
import dev.kanso.oauth.CONSENT_PAGE
import dev.kanso.oauth.OAuthRoutes
import dev.kanso.oauth.ReturnUrlSuccessHandler
import dev.kanso.publik.PublicRoutes
import dev.kanso.repo.UserRepository
import dev.kanso.tokens.ApiTokenFilter
import dev.kanso.tokens.ApiTokenRateLimit
import dev.kanso.tokens.ApiTokenService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.transaction.PlatformTransactionManager

/**
 * Two ways in for a *person* — an OAuth2 round trip, or an email and a password — and one
 * way out of both: a session cookie. The same cookie authenticates the WebSocket
 * handshake, so realtime needs no token plumbing of its own.
 *
 * Two more for something that is not a browser, and they are two rather than one because
 * they answer different questions. `McpBearerFilter` holds `/api/mcp` and takes an OAuth2
 * access token — interactive, minted through a consent screen, naming the application
 * that asked. `ApiTokenFilter` holds everything else and takes a token a member created
 * for themselves — non-interactive, because a webhook signer and a cron job have no
 * browser to start a flow in. Both end in a `KansoAuthenticatedUser`, which is what keeps
 * every authorisation rule downstream ignorant of how the caller arrived.
 *
 * CSRF tokens are off, and that is a deliberate, bounded decision: the session
 * cookie is `SameSite=Lax`, so a third-party site cannot make the browser attach
 * it to a POST/PATCH/DELETE, and every mutation here is one of those. Reads are
 * side-effect free. If Kanso ever gains a cross-site cookie (`SameSite=None`) or
 * a state-changing GET, this has to come back on.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
	private val props: KansoProperties,
	private val provisioning: UserProvisioning,
	private val oidcUserService: KansoOidcUserService,
	private val oauth2UserService: KansoOAuth2UserService,
	private val clientRegistrations: DynamicClientRegistrationRepository,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Bean
	fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()



	/**
	 * Second, after the authorisation server's. It has no `securityMatcher` and so
	 * answers everything the first chain did not claim — which is what it did before
	 * there was a first chain, and the order annotation is what keeps that true.
	 */
	@Bean
	@Order(2)
	fun securityFilterChain(
		http: HttpSecurity,
		authorizations: OAuth2AuthorizationService,
		clients: RegisteredClientRepository,
		users: UserRepository,
		transactionManager: PlatformTransactionManager,
		apiTokens: ApiTokenService,
		apiTokenRateLimit: ApiTokenRateLimit,
	): SecurityFilterChain {
		http
			.cors { }
			.csrf { it.disable() }
			.authorizeHttpRequests { registry ->
				registry
					.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
					// Everything the sign-in screen and the first-run wizard need before
					// anyone can possibly be authenticated.
					.requestMatchers(
						"/api/auth/mode",
						"/api/auth/providers",
						"/api/auth/login",
						"/api/auth/accept-invitation",
						"/api/setup/state",
						"/api/setup/owner",
					).permitAll()
					// The public surfaces — screens 27, 28 and the landing page — which are
					// the only routes in Kanso that answer a reader with no session at all.
					// Named path by path and method by method from `PublicRoutes`, never a
					// prefix: `/api/public/**` would open whatever anybody puts there next,
					// and this is the one widening in the application that has to stay
					// auditable at a glance. What those routes may read is narrowed a second
					// time in the read model itself (`PublicTickets`, `PublicRoadmapRepository`),
					// because a filter chain decides who asks, not what comes back.
					.requestMatchers(HttpMethod.GET, *PublicRoutes.OPEN_GET).permitAll()
					.requestMatchers(HttpMethod.POST, *PublicRoutes.OPEN_POST).permitAll()
					// The OAuth flow's own open routes. A separate list from `PublicRoutes`
					// because that file's guard asserts every pattern is under
					// `/api/public/`, and that assertion is worth more than the reuse.
					.requestMatchers(HttpMethod.GET, *OAuthRoutes.OPEN_GET).permitAll()
					.requestMatchers(HttpMethod.POST, *OAuthRoutes.OPEN_POST).permitAll()
					// Reachable without a session precisely so it can redirect to one:
					// the controller reads the principal itself and sends an anonymous
					// visitor to the app's login screen with a return URL. Inline rather
					// than in `OAuthRoutes`, and the two facts do not contradict — that
					// list is for endpoints a machine calls with no session at all, and
					// this is a page a person is about to sign in to.
					.requestMatchers(HttpMethod.GET, CONSENT_PAGE).permitAll()
					.anyRequest().authenticated()
			}
			// A 302 to Google is useless to a fetch() call; the SPA wants a 401 and
			// will navigate to /oauth2/authorization/{provider} itself.
			.exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
			.logout {
				it.logoutUrl("/api/auth/logout")
					.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
					.deleteCookies("JSESSIONID")
			}
			// Configured whatever the providers currently are, including none of them:
			// the wizard can add Google to a running instance, and a filter chain built
			// around today's answer would need a restart to notice.
			.oauth2Login { login ->
				login
					.clientRegistrationRepository(clientRegistrations)
					.userInfoEndpoint { endpoint ->
						endpoint.oidcUserService(oidcUserService).userService(oauth2UserService)
					}
					.successHandler(ReturnUrlSuccessHandler(props.webOrigin))
					.failureHandler(SimpleUrlAuthenticationFailureHandler("${props.webOrigin}/?login_error=1"))
			}

		// Before the session filter, so a bearer on /api/mcp is answered without ever
		// touching a cookie, and before dev mode's filter, so an instance that cannot have
		// an authorisation server says so rather than handing an agent a header identity.
		// It filters itself down to that one path; see its `shouldNotFilter`.
		http.addFilterBefore(
			McpBearerFilter(authorizations, clients, users, transactionManager, props.auth.effectiveMode),
			UsernamePasswordAuthenticationFilter::class.java,
		)

		// The non-interactive door: a token a member made, for a CLI, an SDK or a webhook
		// signer. Added *after* `McpBearerFilter` and *before* dev mode's filter, and both
		// halves of that placement are load-bearing.
		//
		// After the MCP filter, because the two partition the `Authorization` header by
		// path and the MCP one must get first refusal on its own endpoint — see
		// `ApiTokenFilter`'s `shouldNotFilter`, which stands aside on `/api/mcp/**` so a
		// valid OAuth token is never looked up in `api_tokens` and refused.
		//
		// Before `DevAuthenticationFilter`, because that filter names any unauthenticated
		// caller `dev@kanso.local` — an admin. A bad Bearer that reached it would be served
		// with every right on the instance, which is the fall-through `ApiTokenFilter`
		// exists to close; it closes it by answering 401 itself and never chaining. This
		// filter is therefore wired in both modes, unlike the MCP one, since dev mode is
		// where the fall-through is worst rather than where it does not matter.
		http.addFilterBefore(
			ApiTokenFilter(apiTokens, apiTokenRateLimit),
			UsernamePasswordAuthenticationFilter::class.java,
		)

		when (props.auth.mode.lowercase()) {
			"oidc" -> {
				val configured = clientRegistrations.current()
				if (configured.isEmpty()) {
					// Not a fallback to dev auth any more: a fresh instance is meant to
					// land on the setup wizard and create a real account.
					log.info("Auth mode: oidc, no provider configured yet — sign in with a password or run first-run setup")
				} else {
					log.info("Auth mode: oidc ({})", configured.joinToString { it.registrationId })
				}
			}

			"dev" -> {
				log.warn(
					"Auth mode: DEV — identity comes from the '{}' header and nothing is verified. " +
						"Never expose an instance running like this.",
					DevAuthenticationFilter.HEADER,
				)
				http.addFilterBefore(
					DevAuthenticationFilter(provisioning),
					UsernamePasswordAuthenticationFilter::class.java,
				)
			}

			else -> throw IllegalStateException(
				"Unknown kanso.auth.mode '${props.auth.mode}' (expected 'oidc' or 'dev')"
			)
		}

		return http.build()
	}

}

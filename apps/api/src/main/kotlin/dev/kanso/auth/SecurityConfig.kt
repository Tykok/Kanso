package dev.kanso.auth

import dev.kanso.config.KansoProperties
import dev.kanso.publik.PublicRoutes
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler

/**
 * Two ways in — an OAuth2 round trip, or an email and a password — and one way
 * out of both: a session cookie. The same cookie authenticates the WebSocket
 * handshake, so realtime needs no token plumbing of its own.
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



	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
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
					.successHandler(SimpleUrlAuthenticationSuccessHandler(props.webOrigin))
					.failureHandler(SimpleUrlAuthenticationFailureHandler("${props.webOrigin}/?login_error=1"))
			}

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

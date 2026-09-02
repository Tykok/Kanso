package dev.kanso.tokens

import dev.kanso.config.KansoProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The limiter as a bean, so it is one instance for the process — which is the whole of
 * what makes it a limiter. Constructed here rather than annotated `@Component` because it
 * takes a number from configuration and no dependency, exactly as
 * [dev.kanso.oauth.OAuthRegistrationConfig] does for `RegistrationRateLimit`.
 *
 * `ApiTokenFilter` itself is *not* a bean, and must not become one:
 * [dev.kanso.auth.SecurityConfig] constructs it. The reason is in that file and in
 * `McpBearerFilter` — Boot registers a `Filter` bean with the servlet container as well as
 * in the security chain, and the container's copy runs first, so `OncePerRequestFilter`
 * lets the one inside the chain skip and the principal ends up on a context
 * `SecurityContextHolderFilter` is about to throw away.
 */
@Configuration
class ApiTokenConfig {

	@Bean
	fun apiTokenRateLimit(properties: KansoProperties): ApiTokenRateLimit =
		ApiTokenRateLimit(properties.apiTokens.perMinute)
}

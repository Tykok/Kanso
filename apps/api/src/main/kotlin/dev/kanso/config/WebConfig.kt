package dev.kanso.config

import jakarta.servlet.DispatcherType
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration
class WebConfig(private val props: KansoProperties) {

	/**
	 * `server.forward-headers-strategy: framework`'s filter, declared here rather than left
	 * to Boot, for the one argument the property cannot express: *which* forwarded header is
	 * believed. [XForwardedOnly] carries that argument.
	 *
	 * Declaring the bean is what makes Boot's `@ConditionalOnMissingFilterBean` stand aside,
	 * so the dispatcher types and the order below are its own registration copied
	 * deliberately rather than chosen: at the default order this would sit behind the
	 * security chain, which reads the address it exists to have fixed.
	 */
	@Bean
	fun forwardedHeaderFilter(): FilterRegistrationBean<ForwardedHeaderFilter> =
		FilterRegistrationBean<ForwardedHeaderFilter>(XForwardedOnly()).apply {
			setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
			order = Ordered.HIGHEST_PRECEDENCE
		}

	@Bean
	fun corsConfigurationSource(): CorsConfigurationSource {
		val config = CorsConfiguration().apply {
			allowedOrigins = listOf(props.webOrigin)
			allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
			allowedHeaders = listOf("*")
			allowCredentials = true
			maxAge = 3600
		}
		return UrlBasedCorsConfigurationSource().apply {
			registerCorsConfiguration("/**", config)
		}
	}
}

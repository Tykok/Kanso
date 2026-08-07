package dev.kanso.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class WebConfig(private val props: KansoProperties) {

	/**
	 * The browser sends the session cookie, so the allowed origin has to be exact
	 * — a wildcard is rejected by the spec once credentials are involved.
	 *
	 * In development `localhost:3000` and `localhost:8080` are different origins
	 * but the same *site*, which is why a `SameSite=Lax` cookie still travels.
	 */
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

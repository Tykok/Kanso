package dev.kanso.sync.notion

import dev.kanso.config.KansoProperties
import dev.kanso.settings.InstanceSettingsService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
class NotionClientConfig {

	/**
	 * One limiter for the whole application: Notion's ceiling is per integration,
	 * so every call site has to draw from the same budget — including the clients
	 * [ReloadableNotionClient] builds after a settings change.
	 */
	@Bean
	fun notionRateLimiter(props: KansoProperties): RateLimiter =
		RateLimiter(props.sync.outbound.requestsPerSecond)

	/**
	 * Declared as the concrete type so the setup API can ask for a reload; every
	 * other call site injects [NotionClient] and is unaware the token can change.
	 */
	@Bean
	fun notionClient(
		props: KansoProperties,
		settings: InstanceSettingsService,
		objectMapper: ObjectMapper,
		rateLimiter: RateLimiter,
	): ReloadableNotionClient = ReloadableNotionClient(props, settings, objectMapper, rateLimiter)
}

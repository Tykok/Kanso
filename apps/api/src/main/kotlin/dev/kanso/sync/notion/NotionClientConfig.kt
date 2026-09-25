package dev.kanso.sync.notion

import com.fasterxml.jackson.annotation.JsonInclude
import dev.kanso.config.KansoProperties
import dev.kanso.settings.InstanceSettingsService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * The application's mapper, with nulls written back in.
 *
 * `application.yml` sets `default-property-inclusion: non_null`, which is right for the
 * API's own responses and wrong for Notion's: there, `{"date": null}` is how a property is
 * cleared, and dropping the null sends `{}`, which Notion refuses with a 400 on every page
 * that carries one. A project with no posed start date is the common case, so the mirror
 * failed on almost every project. Scoped to this client rather than changed globally,
 * because every other response in the API was written against the setting as it is.
 */
internal fun notionWire(mapper: JsonMapper): ObjectMapper = mapper.rebuild()
	.changeDefaultPropertyInclusion {
		JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS)
	}
	.build()

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
		objectMapper: JsonMapper,
		rateLimiter: RateLimiter,
	): ReloadableNotionClient =
		ReloadableNotionClient(props, settings, notionWire(objectMapper), rateLimiter)
}

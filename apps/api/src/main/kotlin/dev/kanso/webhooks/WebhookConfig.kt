package dev.kanso.webhooks

import dev.kanso.config.KansoProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The three collaborators that take a number or a key from configuration and no dependency,
 * constructed here rather than annotated — the shape `ApiTokenConfig` and
 * `OAuthRegistrationConfig` already use, and for `ApiTokenConfig`'s reason.
 *
 * [WebhookRateLimit] especially: it is one instance for the process, which is the whole of
 * what makes it a limiter. Two beans would be two buckets and a doubled quota, which is
 * the same failure `ApiTokenRateLimit` warns about across replicas, reached without even
 * needing a second replica.
 */
@Configuration
class WebhookConfig {

	@Bean
	fun webhookSecret(properties: KansoProperties): WebhookSecret =
		WebhookSecret(properties.webhooks.signingKey)

	@Bean
	fun webhookRateLimit(properties: KansoProperties): WebhookRateLimit =
		WebhookRateLimit(properties.webhooks.perMinute)

	/**
	 * The real sender. A bean rather than a `@Component` on [HttpWebhookSender] so a test or
	 * a future `@Profile` can supply the seam's other side without the class it replaces
	 * also being a candidate — the ambiguity `OutboundWorker` refuses for handlers, avoided
	 * one layer down.
	 */
	@Bean
	fun webhookSender(properties: KansoProperties): WebhookSender =
		HttpWebhookSender(properties.webhooks.requestTimeout)
}

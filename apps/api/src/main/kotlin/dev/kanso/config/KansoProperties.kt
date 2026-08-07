package dev.kanso.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "kanso")
data class KansoProperties(
	val webOrigin: String = "http://localhost:3000",
	val auth: Auth = Auth(),
	val notion: Notion = Notion(),
	val sync: Sync = Sync(),
	val realtime: Realtime = Realtime(),
) {
	data class Auth(
		val mode: String = "oidc",
		val google: Provider = Provider(),
		val github: Provider = Provider(),
	) {
		val anyProviderConfigured: Boolean get() = google.configured || github.configured

		/**
		 * Reported as configured, with no inference.
		 *
		 * An instance with `oidc` and no provider used to be silently downgraded to
		 * `dev`, because otherwise nobody could sign in. The first-run wizard answers
		 * that now — you create an owner account with a password — so the downgrade is
		 * gone, and reporting a mode nobody chose would only mislead the sign-in screen.
		 */
		val effectiveMode: String get() = mode.lowercase()
	}

	data class Provider(val clientId: String = "", val clientSecret: String = "") {
		val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
	}

	data class Notion(
		val token: String = "",
		val parentPageId: String = "",
		val apiVersion: String = "2025-09-03",
		val baseUrl: String = "https://api.notion.com/v1",
		val requestTimeout: Duration = Duration.ofSeconds(20),
	) {
		val enabled: Boolean get() = token.isNotBlank()
	}

	data class Sync(val outbound: Outbound = Outbound(), val inbound: Inbound = Inbound())

	data class Outbound(
		val enabled: Boolean = true,
		val pollIntervalMs: Long = 500,
		val batchSize: Int = 10,
		val maxAttempts: Int = 8,
		val requestsPerSecond: Double = 2.5,
		val stuckJobTimeout: Duration = Duration.ofMinutes(5),
	)

	data class Inbound(
		val enabled: Boolean = true,
		val intervalMs: Long = 30_000,
		val pageSize: Int = 50,
		val overlap: Duration = Duration.ofSeconds(60),
	)

	data class Realtime(val channel: String = "kanso_events")
}

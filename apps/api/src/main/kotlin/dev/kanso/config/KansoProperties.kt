package dev.kanso.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "kanso")
data class KansoProperties(
	val webOrigin: String = "http://localhost:3000",
	val auth: Auth = Auth(),
	val oauth: OAuth = OAuth(),
	val apiTokens: ApiTokens = ApiTokens(),
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

	/**
	 * Kanso as an authorisation *server*, which is the opposite direction from [Auth].
	 */
	data class OAuth(val registration: Registration = Registration()) {

		/**
		 * `/connect/register` is unauthenticated and creates rows, so all three of these
		 * are security settings rather than tuning.
		 */
		data class Registration(
			/**
			 * Hosts a hosted client may be sent a code on, over https. Loopback is always
			 * allowed and is not listed here.
			 *
			 * Configuration rather than a constant: which origins a vendor's client calls
			 * back on is not Kanso's fact to hard-code, and an instance serving a
			 * different client must be able to add one without a rebuild.
			 */
			val allowedRedirectHosts: List<String> = listOf("claude.ai", "claude.com"),
			/** A refusal when reached, never a prune. */
			val maxClients: Int = 200,
			val perIpPerHour: Int = 5,
		)
	}

	/**
	 * Bearer API tokens — the non-interactive door, which is the opposite of both [Auth]
	 * (Kanso as a client) and [OAuth] (Kanso as an authorisation server).
	 */
	data class ApiTokens(
		/**
		 * Requests one token may make in a minute.
		 *
		 * Two a second sustained, which is chosen against the callers this door exists for
		 * rather than against a load test: a webhook signer sends one request per event, a
		 * GitHub sync polls, and a CLI is driven by a person's hands. None of them
		 * approaches this, and a runaway retry loop — the failure mode every one of them
		 * shares — passes it in half a second.
		 *
		 * Configuration and not a constant because the number is a property of a
		 * deployment's hardware and its integrations, and the person running the instance
		 * is the one who finds out. `ApiTokenRateLimit` records the more important caveat:
		 * this is per instance, so the real ceiling is this times the number of replicas.
		 */
		val perMinute: Int = 120,
	)

	data class Provider(val clientId: String = "", val clientSecret: String = "") {
		val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
	}

	data class Notion(
		val token: String = "",
		val parentPageId: String = "",
		/**
		 * The public integration consent is asked *through*, which is a different setting
		 * from [token] — that one says which workspace this instance mirrors, this one says
		 * which door a browser may be sent to. Pinned here, a database that has never been
		 * connected still shows a button rather than a form, which is the only way the
		 * second instance costs nothing to set up.
		 */
		val app: Provider = Provider(),
		val apiVersion: String = "2025-09-03",
		val baseUrl: String = "https://api.notion.com/v1",
		val requestTimeout: Duration = Duration.ofSeconds(20),
		val import: Import = Import(),
	) {
		val enabled: Boolean get() = token.isNotBlank()

		/**
		 * What screen 24's discovery step is willing to spend.
		 *
		 * Notion answers no total for a data source: the only way to a page count is to
		 * walk the pages, 100 at a time, at roughly 2.5 requests a second. So the walk is
		 * bounded rather than complete, and a base longer than [maxPagesPerDatabase]
		 * reports the count it reached and says it is not exact. Both numbers are
		 * configuration because the right answer depends on the workspace, and the person
		 * running the instance is the one who knows how long they will wait.
		 */
		data class Import(
			val maxDatabases: Int = 50,
			val maxPagesPerDatabase: Int = 2000,
			val pageSize: Int = 100,
		)
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

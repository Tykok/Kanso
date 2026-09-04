package dev.kanso.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "kanso")
data class KansoProperties(
	val webOrigin: String = "http://localhost:3000",
	val auth: Auth = Auth(),
	val oauth: OAuth = OAuth(),
	val apiTokens: ApiTokens = ApiTokens(),
	val webhooks: Webhooks = Webhooks(),
	val notion: Notion = Notion(),
	val github: Github = Github(),
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

	/**
	 * Outbound webhooks — Kanso as a *caller* of somebody else's HTTP, which is a fourth
	 * direction again from [Auth], [OAuth] and [ApiTokens].
	 */
	data class Webhooks(
		/**
		 * The key `webhook_subscriptions.secret_cipher` is encrypted under: 32 bytes,
		 * base64. **Unset means the feature is off**, and creating a subscription is
		 * refused rather than served by storing a signing secret in plaintext.
		 *
		 * Refusing is the only honest option. A default key compiled into the source is
		 * not a key — it is in every copy of the repository — so `secret_cipher` would be
		 * reversible by anybody who could read the column, and the column's entire
		 * purpose is that a database dump is not a set of signing keys. A silent
		 * downgrade to plaintext is worse still: the instance would work, and the
		 * property nobody could see would be the one that was gone. `WebhookSecret`
		 * carries the rest of the argument, including what encryption here does *not*
		 * buy.
		 */
		val signingKey: String = "",

		/**
		 * Deliveries one subscription may receive in a minute.
		 *
		 * The number the ticket insists on: "sans rate limit par abonnement, un webhook en
		 * boucle noie la base". Matched to [ApiTokens.perMinute] deliberately — a webhook
		 * and the API callback it provokes are two halves of one integration's traffic, and
		 * two different ceilings for them would be a number nobody could reason about.
		 *
		 * Two a second sustained. A person editing tickets never approaches it; a bulk edit
		 * of five hundred rows is paced to about four minutes, which is the cost of the
		 * protection and is paid in latency rather than in refusals — `WebhookRateLimit`
		 * defers, so nothing is dropped. `WebhookRateLimit` also records the caveat this
		 * shares with `ApiTokenRateLimit`: it is per instance.
		 */
		val perMinute: Int = 120,

		/**
		 * How long one POST may take before it counts as a failure.
		 *
		 * Short on purpose, and much shorter than [Notion.requestTimeout]'s twenty seconds.
		 * Notion is a remote Kanso must wait for because there is no alternative; a webhook
		 * receiver is somebody's own endpoint whose whole job is to accept a body and
		 * return, and one that takes longer than this is going to be retried anyway. The
		 * number also bounds a drain pass: a batch of [Outbound.batchSizeFor]'s twenty jobs
		 * against a black-holing endpoint is a hundred seconds, not seven minutes.
		 */
		val requestTimeout: Duration = Duration.ofSeconds(5),
	) {
		val enabled: Boolean get() = signingKey.isNotBlank()
	}

	data class Provider(val clientId: String = "", val clientSecret: String = "") {
		val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
	}

	/**
	 * The GitHub **App** — which is not [Auth.github], and the distinction is the whole
	 * reason this is its own group.
	 *
	 * `kanso.auth.github` is an OAuth client used to *sign people in*: it answers "who is
	 * this person". This is an App installed on repositories, and it answers "what happened
	 * to this pull request". Two credentials, two consent screens, two purposes; folding
	 * them together would mean an instance could not have one without the other, and a
	 * rotated sign-in secret would silently break the webhook.
	 *
	 * Three values now, of `V36`'s six: the webhook secret the inbound half verifies with,
	 * and the OAuth **pair** that asks a member for consent. The pair arrived together
	 * because that is what the rule `c3d1a95` established actually forbids — half of one
	 * credential from the environment and half from the database, which fails at the first
	 * signature a long way from the mistake. A client id and a private key are not halves
	 * of anything, so the remaining three (app id, slug, private key) wait for the code
	 * that signs an installation JWT.
	 */
	data class Github(
		/**
		 * Pins the webhook's HMAC key, ahead of the `instance_settings` column.
		 *
		 * Environment-first so that a `docker compose` can ship an instance whose webhook
		 * works before anybody opens a settings screen — the same reason
		 * `notionAppManagedByEnvironment` exists. It is also the only way the inbound path is
		 * usable at all until the manifest flow lands, since nothing else writes that column
		 * yet.
		 */
		val webhookSecret: String = "",
		/**
		 * The App's own OAuth client — `KANSO_GITHUB_CLIENT_ID` and
		 * `KANSO_GITHUB_CLIENT_SECRET`, which are **not** `GITHUB_CLIENT_ID` and
		 * `GITHUB_CLIENT_SECRET`. Those two are `kanso.auth.github`'s, they sign people in,
		 * and an instance can legitimately have both pairs set to different values.
		 *
		 * The near-identical names are the trap, so they are named here rather than
		 * discovered: pinning the sign-in client and expecting a member's GitHub link to
		 * work presents as "GitHub says the client id is wrong" with two client ids in the
		 * process, one of which is correct for a different purpose.
		 */
		val clientId: String = "",
		val clientSecret: String = "",
	) {
		/** Whether the webhook can verify anything. A blank secret is not a secret. */
		val webhookSecretConfigured: Boolean get() = webhookSecret.isNotBlank()

		/**
		 * Whether the environment pins the pair. **Both or neither** — half an OAuth client
		 * cannot ask anybody for consent, and an id from here married to a secret from the
		 * database is the configuration nobody intended.
		 */
		val appConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
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

		/**
		 * The default budget, and the one Notion drains on.
		 *
		 * Ten is sized against Notion's shared `RateLimiter` at ~2.5 req/s: a larger batch
		 * would not push harder, it would only queue inside the limiter, holding jobs in
		 * 'running' while they wait for a permit they could have waited for as 'pending'.
		 */
		val batchSize: Int = 10,

		/**
		 * Per-destination overrides, because a batch has always been a per-destination
		 * budget and until now every destination had the same one.
		 *
		 * A map keyed on `Destination.wire` rather than a field per destination, so adding
		 * a third consumer is a line of YAML and not a property nobody sets. Keyed on the
		 * wire string rather than the enum because `Destination` lives in `outbox` and
		 * configuration should not be the thing that drags the queue's vocabulary into
		 * this file — [batchSizeFor] does the lookup and an unknown key simply never
		 * matches.
		 *
		 * **Twenty for webhooks**, and it is not comparable to Notion's ten:
		 *
		 *   * A webhook job *fans out*. Twenty jobs across a typical one to three
		 *     subscriptions is twenty to sixty requests in a pass, so the number is
		 *     already multiplied before it reaches anybody's server.
		 *   * The ceiling that actually binds is `Webhooks.perMinute`, not this. Past two
		 *     deliveries a second per subscription the limiter refuses — and *defers*,
		 *     refunding the attempt — so oversizing this costs deferrals rather than
		 *     failures, and the only real cost of a big batch is jobs sitting in 'running'
		 *     that could have sat in 'pending'.
		 *   * The floor is drain throughput. Webhooks have no shared limiter, so unlike
		 *     Notion a bigger batch genuinely does push harder, and Notion's ten would
		 *     leave a bulk edit's backlog draining at twenty a second where the receivers
		 *     could take more.
		 *   * The worst case is bounded and survivable: twenty jobs against an endpoint
		 *     that accepts connections and never answers is twenty times
		 *     `Webhooks.requestTimeout`, a hundred seconds in one pass. That is only
		 *     survivable because of two changes that landed just before this one —
		 *     `KAN-61`'s heartbeat, so the pass is not mistaken for a dead worker and
		 *     replayed, and `KAN-57`'s per-destination clock, so those hundred seconds
		 *     are not also Notion's.
		 */
		val batchSizes: Map<String, Int> = mapOf("webhook" to 20),

		val maxAttempts: Int = 8,
		val requestsPerSecond: Double = 2.5,
		val stuckJobTimeout: Duration = Duration.ofMinutes(5),
	) {
		/** The budget for one destination, falling back to the shared default. */
		fun batchSizeFor(destinationWire: String): Int = batchSizes[destinationWire] ?: batchSize
	}

	data class Inbound(
		val enabled: Boolean = true,
		val intervalMs: Long = 30_000,
		val pageSize: Int = 50,
		val overlap: Duration = Duration.ofSeconds(60),
	)

	data class Realtime(val channel: String = "kanso_events")
}

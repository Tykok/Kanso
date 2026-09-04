package dev.kanso.settings

import dev.kanso.config.KansoProperties
import dev.kanso.config.SecretBox
import dev.kanso.domain.InstanceRole
import dev.kanso.service.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Configuration as the application should use it: environment first, database
 * second, secrets already decrypted.
 *
 * Never serialise this. [state] is what the API answers with.
 */
class ResolvedSettings(
	val setupCompletedAt: OffsetDateTime?,
	val notionToken: String?,
	val notionParentPageId: String?,
	val notionManagedByEnvironment: Boolean,
	val googleClientId: String?,
	val googleClientSecret: String?,
	val googleManagedByEnvironment: Boolean,
	/** The public integration consent is asked through, and what one grant answered. */
	val notionClientId: String?,
	val notionClientSecret: String?,
	val notionAppManagedByEnvironment: Boolean,
	val notionWorkspaceName: String?,
	/**
	 * The GitHub App's webhook HMAC key. Null when nothing has configured one, which is
	 * what `GithubWebhookController` turns into a refusal — an endpoint that cannot verify
	 * a signature must not accept an unverified one.
	 */
	val githubWebhookSecret: String?,
	val githubManagedByEnvironment: Boolean,
	/**
	 * The App's OAuth client, which is what a **member's** consent screen is built from —
	 * the user-to-server flow that fills `github_accounts`. Null until somebody configures
	 * an App, which is the state every instance starts in and the reason the feed's
	 * fallback is a documented behaviour rather than a failure.
	 */
	val githubClientId: String?,
	val githubClientSecret: String?,
	val githubAppManagedByEnvironment: Boolean,
) {
	/** A stray log line must not print a token. */
	override fun toString(): String =
		"ResolvedSettings(notion=${!notionToken.isNullOrBlank()}, " +
			"notionApp=${!notionClientSecret.isNullOrBlank()}, " +
			"google=${!googleClientSecret.isNullOrBlank()}, " +
			"github=${!githubWebhookSecret.isNullOrBlank()}, " +
			"githubApp=${!githubClientSecret.isNullOrBlank()})"
}

data class NotionSettingsState(
	val configured: Boolean,
	val managedByEnvironment: Boolean,
	val parentPageId: String?,
	/**
	 * Whether an integration exists for consent to be asked through — which is not the
	 * same question as [configured]. The two are deliberately separate: a client id and
	 * secret make the Connect button *possible*, a token makes the mirror *work*, and an
	 * instance can have either without the other. Conflating them would leave the wizard
	 * unable to tell "nothing is set up" from "set up, nobody has consented yet".
	 */
	val appConfigured: Boolean = false,
	/**
	 * Whether the integration comes from `NOTION_CLIENT_ID` / `NOTION_CLIENT_SECRET`.
	 *
	 * Separate from [managedByEnvironment], which is about the *token*, and the two lead
	 * to opposite screens: a pinned token means there is nothing to connect, a pinned
	 * integration means there is nothing to *type* — the button is exactly what stays.
	 */
	val appManagedByEnvironment: Boolean = false,
	/**
	 * The workspace a completed consent named. Null when the token was pasted rather than
	 * granted, because a pasted token names nothing a person recognises.
	 */
	val workspaceName: String? = null,
	/**
	 * Whether the four mirrored databases exist. Not a setting — the setup API
	 * fills it in from `notion_databases`, because the wizard shows both on one
	 * screen.
	 */
	val bootstrapped: Boolean = false,
)

data class GoogleSettingsState(
	val configured: Boolean,
	val managedByEnvironment: Boolean,
	val clientId: String?,
)

data class InstanceSettingsState(
	val setupCompletedAt: OffsetDateTime?,
	val notion: NotionSettingsState,
	val google: GoogleSettingsState,
)

/**
 * The instance's own configuration — the half of it that can be changed without a
 * restart.
 *
 * **The environment wins over the database.** If `NOTION_TOKEN` is set, that is the
 * token, and the wizard is told the value is managed elsewhere; otherwise operator
 * config and wizard config would disagree and only one of them would take effect,
 * silently.
 */
@Service
@Transactional
class InstanceSettingsService(
	private val repo: InstanceSettingsRepository,
	private val props: KansoProperties,
	private val secrets: SecretBox,
) {

	/**
	 * Every sync poll asks whether the mirror is on, so the resolved view is kept
	 * in memory rather than costing a query and an AES round per job batch.
	 */
	@Volatile
	private var cached: ResolvedSettings? = null

	@Transactional(readOnly = true)
	fun resolved(): ResolvedSettings = cached ?: load().also { cached = it }

	fun notionToken(): String? = resolved().notionToken

	fun notionParentPageId(): String? = resolved().notionParentPageId

	@Transactional(readOnly = true)
	fun state(): InstanceSettingsState = resolved().let {
		InstanceSettingsState(
			setupCompletedAt = it.setupCompletedAt,
			notion = NotionSettingsState(
				configured = !it.notionToken.isNullOrBlank(),
				managedByEnvironment = it.notionManagedByEnvironment,
				parentPageId = it.notionParentPageId,
				appConfigured = !it.notionClientId.isNullOrBlank() && !it.notionClientSecret.isNullOrBlank(),
				appManagedByEnvironment = it.notionAppManagedByEnvironment,
				workspaceName = it.notionWorkspaceName,
			),
			google = GoogleSettingsState(
				configured = !it.googleClientId.isNullOrBlank() && !it.googleClientSecret.isNullOrBlank(),
				managedByEnvironment = it.googleManagedByEnvironment,
				clientId = it.googleClientId,
			),
		)
	}

	/** A blank [token] keeps the stored one: the UI never receives it to send back. */
	fun saveNotion(token: String?, parentPageId: String) {
		val submitted = token?.trim()?.takeIf { it.isNotBlank() }
		if (submitted != null && props.notion.token.isNotBlank()) {
			throw BadRequestException(
				"NOTION_TOKEN is set in the environment and takes precedence; unset it to manage the token here."
			)
		}
		repo.updateNotion(submitted?.let(secrets::encrypt), parentPageId.trim().takeIf { it.isNotBlank() })
		invalidate()
	}

	/**
	 * The integration's own credentials.
	 *
	 * `NOTION_TOKEN` is deliberately *not* what refuses here — it says which token to use
	 * and nothing about whether a browser may ask for another one. `NOTION_CLIENT_ID` and
	 * `NOTION_CLIENT_SECRET` are, for the usual reason: two places writing the same
	 * setting means one of them silently loses.
	 */
	fun saveNotionApp(clientId: String, clientSecret: String?) {
		if (props.notion.app.configured) {
			throw BadRequestException(
				"NOTION_CLIENT_ID and NOTION_CLIENT_SECRET are set in the environment and take precedence; " +
					"unset them to manage the integration here."
			)
		}
		val submittedSecret = clientSecret?.trim()?.takeIf { it.isNotBlank() }
		repo.updateNotionApp(clientId.trim().takeIf { it.isNotBlank() }, submittedSecret?.let(secrets::encrypt))
		invalidate()
	}

	/** Called once per completed consent screen, with what the exchange handed back. */
	fun saveNotionGrant(token: String, workspaceId: String?, workspaceName: String?, botId: String?) {
		if (props.notion.token.isNotBlank()) {
			throw BadRequestException(
				"NOTION_TOKEN is set in the environment and takes precedence; unset it to connect from here."
			)
		}
		repo.updateNotionGrant(secrets.encrypt(token), workspaceId, workspaceName, botId)
		invalidate()
	}

	/** The credentials the authorize URL and the token exchange are built from. */
	@Transactional(readOnly = true)
	fun notionApp(): Pair<String, String>? {
		val resolved = resolved()
		val id = resolved.notionClientId?.takeIf { it.isNotBlank() } ?: return null
		val secret = resolved.notionClientSecret?.takeIf { it.isNotBlank() } ?: return null
		return id to secret
	}

	fun saveGoogle(clientId: String, clientSecret: String?) {
		if (props.auth.google.configured) {
			throw BadRequestException(
				"GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are set in the environment and take precedence; " +
					"unset them to manage Google sign-in here."
			)
		}
		val submittedSecret = clientSecret?.trim()?.takeIf { it.isNotBlank() }
		repo.updateGoogle(clientId.trim().takeIf { it.isNotBlank() }, submittedSecret?.let(secrets::encrypt))
		invalidate()
	}

	fun markSetupCompleted() {
		repo.markSetupCompleted()
		invalidate()
	}

	@Transactional(readOnly = true)
	fun hasOwner(): Boolean = repo.hasOwner()

	/**
	 * Read from the row rather than from the session's principal: whether someone
	 * may reconfigure the instance is a question about the current database state,
	 * not about what was true when they signed in.
	 */
	@Transactional(readOnly = true)
	fun instanceRoleOf(userId: UUID): InstanceRole? =
		repo.instanceRoleOf(userId)?.let(InstanceRole::from)

	/**
	 * Dropped on every write. Public because the row can also change underneath
	 * this process — a test that rolled back, a manual SQL fix — and there is no
	 * point in serving a value we know is stale.
	 */
	fun invalidate() {
		cached = null
	}

	private fun load(): ResolvedSettings {
		val stored = repo.read()

		val notionFromEnv = props.notion.token.isNotBlank()
		val notionAppFromEnv = props.notion.app.configured
		val googleFromEnv = props.auth.google.configured
		val githubFromEnv = props.github.webhookSecretConfigured
		val githubAppFromEnv = props.github.appConfigured

		return ResolvedSettings(
			setupCompletedAt = stored.setupCompletedAt,
			notionToken = if (notionFromEnv) props.notion.token else secrets.decrypt(stored.notionTokenEnc),
			// The page id is not a secret and can be set on its own, so it falls back
			// independently of the token.
			notionParentPageId = props.notion.parentPageId.takeIf { it.isNotBlank() } ?: stored.notionParentPageId,
			notionManagedByEnvironment = notionFromEnv,
			googleClientId = if (googleFromEnv) props.auth.google.clientId else stored.googleClientId,
			googleClientSecret = if (googleFromEnv) {
				props.auth.google.clientSecret
			} else {
				secrets.decrypt(stored.googleClientSecretEnc)
			},
			googleManagedByEnvironment = googleFromEnv,
			// Pinned as a pair or not at all — half an OAuth client cannot ask for consent,
			// and an id from the environment married to a secret from the database is the
			// kind of mix nobody can debug from either side.
			notionClientId = if (notionAppFromEnv) props.notion.app.clientId else stored.notionClientId,
			notionClientSecret = if (notionAppFromEnv) {
				props.notion.app.clientSecret
			} else {
				secrets.decrypt(stored.notionClientSecretEnc)
			},
			notionAppManagedByEnvironment = notionAppFromEnv,
			notionWorkspaceName = stored.notionWorkspaceName,
			// Environment first, like the rest: an instance shipped already wired must not
			// need a settings screen to receive its first event.
			//
			// The webhook secret falls back on its own, and the OAuth pair falls back as a
			// pair, because they are two credentials rather than halves of one. That is the
			// rule `c3d1a95` established, read for what it forbids: an HMAC key has no half
			// to be married to the wrong one, while a client id and a client secret do.
			githubWebhookSecret = if (githubFromEnv) {
				props.github.webhookSecret
			} else {
				secrets.decrypt(stored.githubWebhookSecretEnc)
			},
			githubManagedByEnvironment = githubFromEnv,
			githubClientId = if (githubAppFromEnv) props.github.clientId else stored.githubClientId,
			githubClientSecret = if (githubAppFromEnv) {
				props.github.clientSecret
			} else {
				secrets.decrypt(stored.githubClientSecretEnc)
			},
			githubAppManagedByEnvironment = githubAppFromEnv,
		)
	}

	/**
	 * The credentials a member's consent screen and its code exchange are built from.
	 *
	 * The same shape as [notionApp] and for the same reason: a caller that wants to ask for
	 * consent wants both halves or neither, and returning a pair makes "half configured" a
	 * state this method answers rather than one every caller has to check for. A blank is
	 * treated as absent — an empty string in a client id is what an unset environment
	 * variable looks like, not a client.
	 */
	@Transactional(readOnly = true)
	fun githubApp(): Pair<String, String>? {
		val resolved = resolved()
		val id = resolved.githubClientId?.takeIf { it.isNotBlank() } ?: return null
		val secret = resolved.githubClientSecret?.takeIf { it.isNotBlank() } ?: return null
		return id to secret
	}

	/**
	 * Saves the App's OAuth client.
	 *
	 * Refuses when the environment pins it, the rule [saveNotionApp] already states: a
	 * screen that appears to accept a value the next `load()` will ignore is worse than one
	 * that says it cannot.
	 */
	fun saveGithubApp(clientId: String, clientSecret: String?) {
		if (props.github.appConfigured) {
			throw BadRequestException(
				"KANSO_GITHUB_CLIENT_ID and KANSO_GITHUB_CLIENT_SECRET are set in the environment and take " +
					"precedence; unset them to manage the App here."
			)
		}
		val submittedSecret = clientSecret?.trim()?.takeIf { it.isNotBlank() }
		repo.updateGithubApp(clientId.trim().takeIf { it.isNotBlank() }, submittedSecret?.let(secrets::encrypt))
		invalidate()
	}
}

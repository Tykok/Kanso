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
) {
	/** A stray log line must not print a token. */
	override fun toString(): String =
		"ResolvedSettings(notion=${!notionToken.isNullOrBlank()}, google=${!googleClientSecret.isNullOrBlank()})"
}

data class NotionSettingsState(
	val configured: Boolean,
	val managedByEnvironment: Boolean,
	val parentPageId: String?,
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
		val googleFromEnv = props.auth.google.configured

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
		)
	}
}

package dev.kanso.settings

import dev.kanso.db.InstanceSettings
import dev.kanso.db.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The row exactly as stored — secrets still encrypted. Decrypting is the service's
 * job, so nothing that only needs to know *whether* a secret exists has to hold
 * the plaintext.
 */
class StoredInstanceSettings(
	val setupCompletedAt: OffsetDateTime?,
	val notionParentPageId: String?,
	val notionTokenEnc: ByteArray?,
	val googleClientId: String?,
	val googleClientSecretEnc: ByteArray?,
	/** The public integration consent is asked through. See `V14`. */
	val notionClientId: String?,
	val notionClientSecretEnc: ByteArray?,
	/** What a completed consent screen answered. Null until one completes. */
	val notionWorkspaceId: String?,
	val notionWorkspaceName: String?,
	val notionBotId: String?,
	/** `V36`. Still encrypted here, like every other secret on this row. */
	val githubWebhookSecretEnc: ByteArray?,
	/**
	 * The App's OAuth client, which is what asks a **member** for consent — not the same
	 * credential as `kanso.auth.github`, which signs people in. See `KansoProperties.Github`.
	 */
	val githubClientId: String?,
	val githubClientSecretEnc: ByteArray?,
)

@Repository
class InstanceSettingsRepository {

	/** The migration inserts the single row, so there is always one to read. */
	fun read(): StoredInstanceSettings = InstanceSettings.selectAll().single().let {
		StoredInstanceSettings(
			setupCompletedAt = it[InstanceSettings.setupCompletedAt],
			notionParentPageId = it[InstanceSettings.notionParentPageId],
			notionTokenEnc = it[InstanceSettings.notionTokenEnc],
			googleClientId = it[InstanceSettings.googleClientId],
			googleClientSecretEnc = it[InstanceSettings.googleClientSecretEnc],
			notionClientId = it[InstanceSettings.notionClientId],
			notionClientSecretEnc = it[InstanceSettings.notionClientSecretEnc],
			notionWorkspaceId = it[InstanceSettings.notionWorkspaceId],
			notionWorkspaceName = it[InstanceSettings.notionWorkspaceName],
			notionBotId = it[InstanceSettings.notionBotId],
			githubWebhookSecretEnc = it[InstanceSettings.githubWebhookSecretEnc],
			githubClientId = it[InstanceSettings.githubClientId],
			githubClientSecretEnc = it[InstanceSettings.githubClientSecretEnc],
		)
	}

	/**
	 * The App's OAuth client. Configuration, not a connection — the same distinction
	 * [updateNotionApp] draws: saving these connects nothing, and clearing them does not
	 * unlink the members whose past consent already filled `github_accounts`.
	 *
	 * Same null-means-leave-alone rule for the secret, and for the same reason: a screen
	 * that only ever learns a secret exists cannot re-send its value, so saving the id
	 * must not wipe it.
	 */
	fun updateGithubApp(clientId: String?, clientSecretEnc: ByteArray?) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			it[githubClientId] = clientId
			if (clientSecretEnc != null) it[githubClientSecretEnc] = clientSecretEnc
		}
	}

	/**
	 * A null [tokenEnc] leaves the stored token alone. The wizard only ever learns
	 * that a secret exists, never its value, so it cannot re-send one — saving the
	 * page id must not wipe the token.
	 */
	fun updateNotion(tokenEnc: ByteArray?, parentPageId: String?) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			if (tokenEnc != null) it[notionTokenEnc] = tokenEnc
			it[notionParentPageId] = parentPageId
		}
	}

	/**
	 * The credentials of the public integration, which are configuration rather than a
	 * connection: saving them connects nothing, and clearing them does not disconnect
	 * what a past consent already granted. Same null-means-leave-alone rule as the
	 * token above, and for the same reason — the wizard never learns a secret's value.
	 */
	fun updateNotionApp(clientId: String?, clientSecretEnc: ByteArray?) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			it[notionClientId] = clientId
			if (clientSecretEnc != null) it[notionClientSecretEnc] = clientSecretEnc
		}
	}

	/**
	 * What one completed consent screen granted, written together because they are one
	 * fact: this token, from this workspace, acting as this integration user. Writing
	 * the token without the workspace would leave the settings screen able to say a
	 * connection exists and unable to say to what.
	 */
	fun updateNotionGrant(
		tokenEnc: ByteArray,
		workspaceId: String?,
		workspaceName: String?,
		botId: String?,
	) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			it[notionTokenEnc] = tokenEnc
			it[notionWorkspaceId] = workspaceId
			it[notionWorkspaceName] = workspaceName
			it[notionBotId] = botId
		}
	}

	fun updateGoogle(clientId: String?, clientSecretEnc: ByteArray?) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			it[googleClientId] = clientId
			if (clientSecretEnc != null) it[googleClientSecretEnc] = clientSecretEnc
		}
	}

	/**
	 * A no-op once the column is set, rather than a second write pushing the timestamp
	 * forward: `claimOwner` calls this once, at the moment that is actually true, and the
	 * only other caller left is `/api/setup/complete`, kept for an operator's script
	 * against an instance claimed before that became true. Overwriting on a repeat call
	 * would let that script quietly replace a real record with whatever time it happened
	 * to run.
	 */
	fun markSetupCompleted(at: OffsetDateTime = OffsetDateTime.now()) {
		InstanceSettings.update({ (InstanceSettings.id eq true) and InstanceSettings.setupCompletedAt.isNull() }) {
			it[setupCompletedAt] = at
		}
	}

	// --- who may configure this instance -------------------------------------

	/** False on a fresh instance, which is what makes the wizard offer step 0. */
	fun hasOwner(): Boolean = !Users.selectAll().where { Users.instanceRole eq OWNER }.empty()

	fun instanceRoleOf(userId: UUID): String? =
		Users.select(Users.instanceRole).where { Users.id eq userId }.singleOrNull()?.get(Users.instanceRole)

	private companion object {
		const val OWNER = "owner"
	}
}

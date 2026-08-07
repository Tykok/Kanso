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
		)
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

	fun updateGoogle(clientId: String?, clientSecretEnc: ByteArray?) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
			it[googleClientId] = clientId
			if (clientSecretEnc != null) it[googleClientSecretEnc] = clientSecretEnc
		}
	}

	fun markSetupCompleted(at: OffsetDateTime = OffsetDateTime.now()) {
		InstanceSettings.update({ InstanceSettings.id eq true }) {
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

package dev.kanso.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the secrets the setup wizard collects — the Notion token, the Google
 * client secret — so a database dump does not hand them out.
 *
 * AES-GCM with a fresh random nonce per value, stored as `nonce || ciphertext`.
 * GCM authenticates as well as encrypts, so a tampered value fails to decrypt
 * rather than decrypting into garbage.
 *
 * **The key lives outside the database.** Keeping it next to the ciphertext would
 * protect nothing. In order of preference:
 *
 * 1. `KANSO_SECRET_KEY` — base64 of 32 bytes. What you want in production.
 * 2. A file on the data volume, generated on first boot, `rw-------`.
 *
 * Losing the key means the stored secrets can no longer be read; they have to be
 * entered again. That is the honest trade for a self-hosted app that must also
 * start with a single command.
 */
@Component
class SecretBox(
	@Value("\${kanso.security.secret-key:}") configuredKey: String,
	@Value("\${kanso.security.key-file:./data/secret.key}") keyFilePath: String,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()
	private val key: SecretKey = resolveKey(configuredKey, Path.of(keyFilePath))

	fun encrypt(plaintext: String): ByteArray {
		val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
		}
		return nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
	}

	/**
	 * Returns null when the value cannot be read — most often because the key
	 * changed. The caller treats that as "not configured" and asks for the secret
	 * again, which is recoverable; throwing here would take the whole app down over
	 * a setting.
	 */
	fun decrypt(payload: ByteArray?): String? {
		if (payload == null || payload.size <= NONCE_BYTES) return null
		return try {
			val nonce = payload.copyOfRange(0, NONCE_BYTES)
			val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
			val cipher = Cipher.getInstance(TRANSFORMATION).apply {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
			}
			String(cipher.doFinal(ciphertext), Charsets.UTF_8)
		} catch (e: Exception) {
			log.warn(
				"Could not decrypt a stored secret ({}). If KANSO_SECRET_KEY or the key file " +
					"changed, the affected secrets have to be entered again.",
				e.javaClass.simpleName,
			)
			null
		}
	}

	private fun resolveKey(configured: String, keyFile: Path): SecretKey {
		if (configured.isNotBlank()) {
			val bytes = Base64.getDecoder().decode(configured.trim())
			require(bytes.size == KEY_BYTES) {
				"KANSO_SECRET_KEY must decode to $KEY_BYTES bytes, got ${bytes.size}"
			}
			log.info("Encryption key loaded from configuration")
			return SecretKeySpec(bytes, "AES")
		}

		if (Files.exists(keyFile)) {
			val bytes = Base64.getDecoder().decode(Files.readString(keyFile).trim())
			require(bytes.size == KEY_BYTES) { "Key file $keyFile does not contain a $KEY_BYTES-byte key" }
			return SecretKeySpec(bytes, "AES")
		}

		val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
		writeKeyFile(keyFile, Base64.getEncoder().encodeToString(bytes))
		log.warn(
			"Generated a new encryption key at {}. Back it up: without it the stored Notion and " +
				"Google secrets cannot be read and will have to be entered again. Set KANSO_SECRET_KEY " +
				"to manage it yourself.",
			keyFile.toAbsolutePath(),
		)
		return SecretKeySpec(bytes, "AES")
	}

	private fun writeKeyFile(keyFile: Path, encoded: String) {
		keyFile.parent?.let { Files.createDirectories(it) }
		Files.writeString(keyFile, encoded)
		// Best effort: the file system may not support POSIX permissions.
		runCatching {
			Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
		}
	}

	private companion object {
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val KEY_BYTES = 32
		const val NONCE_BYTES = 12
		const val TAG_BITS = 128
	}
}

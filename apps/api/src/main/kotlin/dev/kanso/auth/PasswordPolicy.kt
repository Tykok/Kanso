package dev.kanso.auth

import dev.kanso.service.BadRequestException
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * Length, and nothing else.
 *
 * Composition rules — a digit, a symbol, mixed case — push people towards
 * `Passw0rd!` and its neighbours, which is a smaller search space than a long
 * phrase. Length is the property that actually costs an attacker something, so it
 * is the only one worth refusing on.
 */
object PasswordPolicy {

	const val MIN_LENGTH = 12

	fun validate(password: String) {
		if (password.length < MIN_LENGTH) {
			throw BadRequestException("The password must be at least $MIN_LENGTH characters long")
		}
	}
}

/**
 * Spring declares `encode` as though it might return nothing. BCrypt never does,
 * and storing a null hash would silently turn an account into one nobody can sign
 * in to — so this fails loudly instead of propagating the nullable type.
 */
fun PasswordEncoder.hash(password: String): String =
	requireNotNull(encode(password)) { "The password encoder returned no hash" }


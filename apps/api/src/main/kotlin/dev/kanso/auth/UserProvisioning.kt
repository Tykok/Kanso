package dev.kanso.auth

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Turns an external identity into a Kanso user row.
 *
 * A new account lands with no team membership on purpose: it can sign in and see
 * an empty workspace, which is a far better first contact than an opaque 403.
 * Someone with access adds them to a team.
 */
@Service
class UserProvisioning(private val users: UserRepository) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun provision(
		provider: String,
		subject: String,
		email: String,
		displayName: String,
		avatarUrl: String?,
	): User {
		val user = users.provisionFromOidc(
			provider = provider,
			subject = subject,
			email = email.lowercase(),
			displayName = displayName.ifBlank { email },
			avatarUrl = avatarUrl,
		)
		log.info("Signed in: {} via {} (kanso id {})", user.email, provider, user.id)
		return user
	}

	@Transactional
	fun findOrCreateByEmail(email: String): User =
		users.findByEmail(email) ?: users.insert(
			email = email.lowercase(),
			displayName = email.substringBefore('@'),
		)
}

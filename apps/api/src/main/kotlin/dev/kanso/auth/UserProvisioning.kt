package dev.kanso.auth

import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.settings.PreferencesPatch
import dev.kanso.settings.PreferencesService
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
class UserProvisioning(
	private val users: UserRepository,
	private val preferences: PreferencesService,
) {

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
		stampOnboarded(user)
		log.info("Signed in: {} via {} (kanso id {})", user.email, provider, user.id)
		return user
	}

	@Transactional
	fun findOrCreateByEmail(email: String): User {
		val user = users.findByEmail(email) ?: users.insert(
			email = email.lowercase(),
			displayName = email.substringBefore('@'),
		)
		stampOnboarded(user)
		return user
	}

	/**
	 * The routing guard in `app-shell.tsx` sends any signed-in account with no
	 * `onboarded_at` to `/setup` — and `/setup` is one screen now, the account-creation
	 * screen, which has nothing left to offer a row that already has an identity. OIDC
	 * sign-in and the dev-header filter both create that row through this class without
	 * ever touching the wizard's own endpoint, so without this stamp such an account
	 * would bounce between `/` and `/setup` forever.
	 *
	 * Stamped on every call, not only on the row's first one: `PreferencesPatch.applyTo`
	 * keeps whichever `onboardedAt` was written first, so re-stamping a returning sign-in
	 * is a no-op rather than a lie. Telling "just created" from "already existed" apart
	 * at this call site would mean widening [UserRepository]'s return shape for a
	 * distinction this method does not otherwise need.
	 */
	private fun stampOnboarded(user: User) {
		preferences.save(user.id, PreferencesPatch(onboarded = true))
	}
}

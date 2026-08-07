package dev.kanso.auth

import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import dev.kanso.service.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * What someone may change about their own account, and what an owner or admin may
 * change about someone else's.
 */
@Service
class AccountService(
	private val users: UserRepository,
	private val encoder: PasswordEncoder,
	private val sessions: UserSessions,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional
	fun rename(userId: UUID, displayName: String): User {
		val trimmed = displayName.trim()
		if (trimmed.isBlank()) throw BadRequestException("A display name cannot be empty")
		if (trimmed.length > 120) throw BadRequestException("A display name cannot exceed 120 characters")
		users.setDisplayName(userId, trimmed)
		return requireNotNull(users.findById(userId))
	}

	/**
	 * The current password is required even though the session already proves
	 * identity: an unattended screen should not be enough to take an account over.
	 *
	 * Every other session for this user is then expired. Without that, changing a
	 * password after someone else has used it accomplishes nothing — the intruder's
	 * session outlives the credential it came from.
	 */
	@Transactional
	fun changePassword(userId: UUID, currentPassword: String, newPassword: String) {
		val existing = users.passwordHashOf(userId)
			?: throw BadRequestException(
				"This account signs in through a provider and has no password yet. " +
					"Set one from the invitation link, or keep using the provider."
			)
		if (!encoder.matches(currentPassword, existing)) {
			throw BadRequestException("The current password is not correct")
		}
		PasswordPolicy.validate(newPassword)
		if (encoder.matches(newPassword, existing)) {
			throw BadRequestException("The new password is the same as the current one")
		}

		users.setPassword(userId, encoder.hash(newPassword))
		expireOtherSessions(userId)
	}

	/**
	 * Detaches a provider identity.
	 *
	 * Refused when it would leave the account with no way in at all — an account
	 * with neither a password nor a provider cannot be signed into, and nothing in
	 * the app can undo that from the outside.
	 */
	@Transactional
	fun unlinkProvider(userId: UUID, provider: String): User {
		val user = users.findById(userId) ?: throw NotFoundException("No user $userId")
		if (user.oidcProvider == null) {
			throw BadRequestException("No provider is linked to this account")
		}
		if (!provider.equals(user.oidcProvider, ignoreCase = true)) {
			throw BadRequestException("This account is linked to ${user.oidcProvider}, not $provider")
		}
		if (!user.hasPassword) {
			throw ConflictException(
				"Unlinking ${user.oidcProvider} would leave no way to sign in. Set a password first."
			)
		}
		users.clearOidcIdentity(userId)
		log.info("Unlinked {} from {}", provider, user.email)
		return requireNotNull(users.findById(userId))
	}

	/**
	 * The owner is not assignable from a dropdown: it is whoever set the instance up,
	 * and demoting them from a list is how an instance ends up with nobody able to
	 * configure it.
	 */
	@Transactional
	fun setInstanceRole(actor: User, targetId: UUID, role: InstanceRole): User {
		if (!actor.instanceRole.canConfigureInstance) {
			throw org.springframework.security.access.AccessDeniedException(
				"Only the owner or an admin can change roles"
			)
		}
		if (role == InstanceRole.OWNER) {
			throw BadRequestException("Ownership is not transferable from here")
		}
		val target = users.findById(targetId) ?: throw NotFoundException("No user $targetId")
		if (target.instanceRole == InstanceRole.OWNER) {
			throw ConflictException("The instance owner's role cannot be changed")
		}
		if (target.id == actor.id && actor.instanceRole == InstanceRole.ADMIN && role == InstanceRole.MEMBER) {
			throw ConflictException("Stepping down would leave you unable to undo it; ask the owner")
		}

		users.setInstanceRole(targetId, role)
		log.info("{} set {} to {}", actor.email, target.email, role.wire)
		return requireNotNull(users.findById(targetId))
	}

	/**
	 * Expires this user's sessions everywhere but here.
	 *
	 * `SessionRegistry` only knows about sessions held by this instance, so on a
	 * multi-instance deployment the ones held elsewhere survive until they expire on
	 * their own. Single-instance — what the compose file ships — is exact.
	 */
	private fun expireOtherSessions(userId: UUID) {
		// The session making the change keeps working: being signed out of the very
		// browser you just used to pick a new password reads as a failure.
		val current = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
			?.request?.getSession(false)?.id
		val ended = sessions.invalidateOthers(userId, current)
		if (ended > 0) log.info("Ended {} other session(s) after a password change", ended)
	}
}

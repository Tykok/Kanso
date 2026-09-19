package dev.kanso.auth

import dev.kanso.db.Invitations
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import dev.kanso.service.ConflictException
import dev.kanso.settings.PreferencesPatch
import dev.kanso.settings.PreferencesService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** An invitation still waiting to be accepted. Never carries the token. */
data class PendingInvitation(
	val id: UUID,
	val email: String?,
	val role: InstanceRole,
	val createdAt: OffsetDateTime,
	val expiresAt: OffsetDateTime,
	val expired: Boolean,
)

/**
 * A link to copy and send by whatever means you already have. No SMTP: putting a
 * mail server in the compose file to onboard a second person is a poor trade for
 * a self-hosted tracker.
 *
 * The token is stored as a SHA-256 digest, so a database dump hands out no working
 * links. It is not stretched like a password because it is 32 random bytes rather
 * than something a human chose — there is nothing to guess.
 *
 * The Exposed statements live here rather than in a repository because this is the
 * only code that reads or writes an invitation; a separate class would be one
 * indirection over four queries.
 */
@Service
@Transactional
class InvitationService(
	private val users: UserRepository,
	private val encoder: PasswordEncoder,
	private val preferences: PreferencesService,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	/**
	 * Returns the raw token exactly once — it is never readable again, here or in
	 * the database. [email] is optional: an open link is useful when you do not yet
	 * know the address, a bound one refuses to be forwarded.
	 */
	fun create(createdBy: UUID, email: String?, role: InstanceRole): Pair<String, OffsetDateTime> {
		if (role == InstanceRole.OWNER) {
			throw BadRequestException("An invitation cannot grant ownership; the owner is whoever set the instance up")
		}
		val token = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
		val expiresAt = OffsetDateTime.now().plus(LIFETIME)

		Invitations.insert {
			it[id] = UUID.randomUUID()
			it[tokenHash] = digest(token)
			it[Invitations.email] = email?.trim()?.lowercase()?.takeIf { address -> address.isNotEmpty() }
			it[instanceRole] = role.wire
			it[Invitations.createdBy] = createdBy
			it[createdAt] = OffsetDateTime.now()
			it[Invitations.expiresAt] = expiresAt
		}
		return token to expiresAt
	}

	/**
	 * The links still outstanding. The token is absent by design — it exists in
	 * readable form only in the response that created it, so this can list who was
	 * invited without handing anyone a way in.
	 */
	fun pending(): List<PendingInvitation> = Invitations.selectAll()
		.where { Invitations.acceptedAt.isNull() }
		.orderBy(Invitations.createdAt to SortOrder.DESC)
		.map {
			PendingInvitation(
				id = it[Invitations.id],
				email = it[Invitations.email],
				role = InstanceRole.from(it[Invitations.instanceRole]),
				createdAt = it[Invitations.createdAt],
				expiresAt = it[Invitations.expiresAt],
				expired = it[Invitations.expiresAt].isBefore(OffsetDateTime.now()),
			)
		}

	/** Deleting the row is what revokes the link; there is no other copy of the token. */
	fun revoke(id: UUID): Boolean =
		Invitations.deleteWhere { (Invitations.id eq id) and Invitations.acceptedAt.isNull() } > 0

	fun accept(rawToken: String, email: String, displayName: String, password: String): User {
		// Unknown, expired and already used give one answer on purpose: telling them
		// apart tells whoever is probing tokens which guesses were worth repeating.
		val invitation = Invitations.selectAll()
			.where { Invitations.tokenHash eq digest(rawToken) }
			.singleOrNull()
			?: throw BadRequestException(REJECTED)
		if (invitation[Invitations.acceptedAt] != null) throw BadRequestException(REJECTED)
		if (invitation[Invitations.expiresAt].isBefore(OffsetDateTime.now())) throw BadRequestException(REJECTED)

		val address = email.trim().lowercase()
		if (address.isEmpty()) throw BadRequestException("An email address is required")

		// Not folded into the generic answer above: whoever holds the link already
		// knows it is valid, so saying which address it is for leaks nothing and
		// saves them guessing.
		val boundTo = invitation[Invitations.email]
		if (boundTo != null && boundTo != address) {
			throw BadRequestException("This invitation was issued for a different email address")
		}
		PasswordPolicy.validate(password)

		// Refused rather than merged: an open invitation would otherwise be a way to
		// set a password on somebody else's existing account.
		if (users.findByEmail(address) != null) {
			throw ConflictException("An account already exists for $address; sign in instead")
		}

		val user = users.createLocalUser(
			email = address,
			displayName = displayName.ifBlank { address.substringBefore('@') },
			passwordHash = encoder.hash(password),
			role = InstanceRole.from(invitation[Invitations.instanceRole]),
		)

		// Single use decided by the database, not by the check above: two people
		// opening the same link at once both get past it, and only one of them
		// updates a row here. The loser's account rolls back with the exception.
		val claimed = Invitations.update({
			(Invitations.id eq invitation[Invitations.id]) and Invitations.acceptedAt.isNull()
		}) {
			it[acceptedAt] = OffsetDateTime.now()
			it[acceptedBy] = user.id
		}
		if (claimed != 1) throw BadRequestException(REJECTED)

		// The wizard an invited account used to be sent through asked for preferences and
		// nothing else, and settings asks for those better. See `LocalAuthService.claimOwner`.
		preferences.save(user.id, PreferencesPatch(onboarded = true))

		log.info("Invitation accepted by {} as {} (kanso id {})", user.email, user.instanceRole.wire, user.id)
		return user
	}

	private fun digest(token: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(token.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

	companion object {
		val LIFETIME: Duration = Duration.ofDays(7)

		private const val TOKEN_BYTES = 32
		private const val REJECTED = "This invitation link is no longer valid"
	}
}

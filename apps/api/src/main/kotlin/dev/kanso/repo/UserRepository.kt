package dev.kanso.repo

import dev.kanso.db.Users
import dev.kanso.db.toUser
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class UserRepository {

	fun findById(id: UUID): User? =
		Users.selectAll().where { Users.id eq id }.singleOrNull()?.toKansoUser()

	fun findByEmail(email: String): User? =
		Users.selectAll().where { Users.email.lowerCase() eq email.lowercase() }.singleOrNull()?.toKansoUser()

	fun findByOidc(provider: String, subject: String): User? =
		Users.selectAll()
			.where { (Users.oidcProvider eq provider) and (Users.oidcSubject eq subject) }
			.singleOrNull()?.toKansoUser()

	fun findAllById(ids: Collection<UUID>): List<User> =
		if (ids.isEmpty()) emptyList()
		else Users.selectAll().where { Users.id inList ids }.map { it.toKansoUser() }

	fun findAll(): List<User> =
		Users.selectAll().orderBy(Users.displayName to SortOrder.ASC).map { it.toKansoUser() }

	/**
	 * Candidates for the `@handles` written in a comment.
	 *
	 * `users` has no handle column, and the local part of the address is the only thing
	 * it holds that is unique, stable and typeable — a display name is neither of the
	 * first two. Two addresses can share a local part across domains, so this returns
	 * *candidates*: the caller decides what an ambiguous handle means.
	 *
	 * A handle reaches this method already stripped to word characters, so no `%` or `_`
	 * of the writer's can leak into the pattern.
	 */
	fun findByHandles(handles: Collection<String>): List<User> {
		if (handles.isEmpty()) return emptyList()
		val patterns = handles.map { handle -> Users.email.lowerCase() like "${handle.lowercase()}@%" }
		return Users.selectAll().where(patterns.compoundOr()).map { it.toKansoUser() }
	}

	fun insert(email: String, displayName: String, avatarUrl: String? = null): User {
		val id = UUID.randomUUID()
		Users.insert {
			it[Users.id] = id
			it[Users.email] = email
			it[Users.displayName] = displayName
			it[Users.avatarUrl] = avatarUrl
			it[active] = true
			it[createdAt] = OffsetDateTime.now()
		}
		return requireNotNull(findById(id))
	}

	/**
	 * First login binds the identity to an existing row when the address is
	 * already known — someone may have been seeded as an assignee long before
	 * they ever signed in — and creates one otherwise.
	 */
	fun provisionFromOidc(
		provider: String,
		subject: String,
		email: String,
		displayName: String,
		avatarUrl: String?,
	): User {
		val existing = findByOidc(provider, subject) ?: findByEmail(email)
		val id = existing?.id ?: UUID.randomUUID()

		if (existing == null) {
			Users.insert {
				it[Users.id] = id
				it[Users.email] = email
				it[Users.displayName] = displayName
				it[Users.avatarUrl] = avatarUrl
				it[oidcProvider] = provider
				it[oidcSubject] = subject
				it[active] = true
				it[lastLoginAt] = OffsetDateTime.now()
				it[createdAt] = OffsetDateTime.now()
			}
		} else {
			Users.update({ Users.id eq id }) {
				it[oidcProvider] = provider
				it[oidcSubject] = subject
				it[Users.displayName] = displayName
				if (avatarUrl != null) it[Users.avatarUrl] = avatarUrl
				it[lastLoginAt] = OffsetDateTime.now()
			}
		}
		return requireNotNull(findById(id))
	}

	fun setNotionPersonId(id: UUID, notionPersonId: String?) {
		Users.update({ Users.id eq id }) { it[Users.notionPersonId] = notionPersonId }
	}

	// --- local accounts ------------------------------------------------------

	/**
	 * Returns the hash alone rather than a [User], so nothing that only needs to
	 * check a password can accidentally hand the digest to a serialiser.
	 */
	fun findPasswordHash(email: String): String? =
		Users.select(Users.passwordHash)
			.where { Users.email.lowerCase() eq email.lowercase() }
			.singleOrNull()?.get(Users.passwordHash)

	/**
	 * No `oidc_provider`: this account exists on its own. Signing in with a provider
	 * later binds to the same row by email, which is what [provisionFromOidc] does.
	 */
	fun createLocalUser(email: String, displayName: String, passwordHash: String, role: InstanceRole): User {
		val id = UUID.randomUUID()
		Users.insert {
			it[Users.id] = id
			it[Users.email] = email
			it[Users.displayName] = displayName
			it[Users.passwordHash] = passwordHash
			it[instanceRole] = role.wire
			it[active] = true
			it[createdAt] = OffsetDateTime.now()
		}
		return requireNotNull(findById(id))
	}

	fun setPassword(userId: UUID, hash: String) {
		Users.update({ Users.id eq userId }) { it[passwordHash] = hash }
	}

	fun passwordHashOf(userId: UUID): String? =
		Users.select(Users.passwordHash).where { Users.id eq userId }
			.singleOrNull()?.get(Users.passwordHash)

	fun setDisplayName(userId: UUID, displayName: String) {
		Users.update({ Users.id eq userId }) { it[Users.displayName] = displayName }
	}

	fun setInstanceRole(userId: UUID, role: InstanceRole) {
		Users.update({ Users.id eq userId }) { it[instanceRole] = role.wire }
	}

	/**
	 * Detaches a provider identity from the account.
	 *
	 * The caller checks first that another way in remains — a row with neither a
	 * password nor a provider is an account nobody can ever sign into again, and the
	 * database cannot tell that apart from an invitation waiting to be accepted.
	 */
	fun clearOidcIdentity(userId: UUID) {
		Users.update({ Users.id eq userId }) {
			it[oidcProvider] = null
			it[oidcSubject] = null
		}
	}

	fun markLoggedIn(userId: UUID) {
		Users.update({ Users.id eq userId }) { it[lastLoginAt] = OffsetDateTime.now() }
	}

	/** Whether the first-run wizard still has an owner to claim. */
	fun ownerExists(): Boolean =
		!Users.select(Users.id).where { Users.instanceRole eq InstanceRole.OWNER.wire }.limit(1).empty()

	fun countUsers(): Long = Users.selectAll().count()

	/** Drives whether the sign-in screen offers a password form at all. */
	fun anyPasswordSet(): Boolean =
		!Users.select(Users.id).where { Users.passwordHash.isNotNull() }.limit(1).empty()

	/** Notion `people` needs a workspace member id; whoever lacks one can't be mirrored. */
	fun notionPersonIds(userIds: Collection<UUID>): Map<UUID, String> =
		if (userIds.isEmpty()) emptyMap()
		else Users.select(Users.id, Users.notionPersonId)
			.where { (Users.id inList userIds) and Users.notionPersonId.isNotNull() }
			.associate { it[Users.id] to it[Users.notionPersonId]!! }

	/**
	 * Every read of a user goes through here rather than through the shared mapper
	 * alone: an owner read back as a plain member would hand the setup endpoints to
	 * anyone, and a default is a silent way to get that wrong.
	 *
	 * `hasPassword` rather than the hash itself — the domain [User] travels as far
	 * as the API responses.
	 */
	private fun ResultRow.toKansoUser(): User = toUser().copy(
		instanceRole = InstanceRole.from(this[Users.instanceRole]),
		hasPassword = this[Users.passwordHash] != null,
	)
}

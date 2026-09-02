package dev.kanso.tokens

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Exposed's view of `api_tokens`, in the slice's own package for the reason
 * `favourites/Favourites.kt` gives — several branches are cut from one commit and
 * appending to `db/Tables.kt` is one conflict per branch for nothing.
 *
 * `scopes` is a real `TEXT[]` and not a comma-joined string. The library's own tables do
 * join theirs (`oauth2_registered_client.scopes` is a `varchar(1000)`) and that is not a
 * precedent to copy: it is `V18`'s contract with code Kanso does not own. A joined string
 * cannot be constrained, and `V27`'s CHECK — the closed vocabulary, in the database — is
 * the thing this column exists to be able to have.
 */
object ApiTokens : Table("api_tokens") {
	val id = javaUUID("id")
	val userId = javaUUID("user_id")
	val name = text("name")
	val prefix = text("prefix")
	val hash = text("hash")
	val scopes = array<String>("scopes", TextColumnType())
	val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	override val primaryKey = PrimaryKey(id)
}

/**
 * A token as the filter needs it: who it speaks for, what it may do, and when it was last
 * seen.
 *
 * Distinct from [ApiToken], which is the shape a *person* is shown. The two carry
 * different fields on purpose — this one has the `userId` a filter must resolve and none
 * of the `name`/`prefix` a screen draws, so neither type is a superset standing in for
 * both and neither leaks the other's business into a response.
 */
data class AuthenticatedToken(
	val tokenId: UUID,
	val userId: UUID,
	val scopes: Set<String>,
	val lastUsedAt: OffsetDateTime?,
)

@Repository
class ApiTokenRepository {

	/** One member's tokens, newest first — the order `api_tokens_owner_idx` is built in. */
	fun findByUser(userId: UUID): List<ApiToken> =
		ApiTokens.selectAll()
			.where { ApiTokens.userId eq userId }
			.orderBy(ApiTokens.createdAt to SortOrder.DESC, ApiTokens.id to SortOrder.DESC)
			.map { row ->
				ApiToken(
					id = row[ApiTokens.id],
					name = row[ApiTokens.name],
					prefix = row[ApiTokens.prefix],
					scopes = row[ApiTokens.scopes].toSet(),
					lastUsedAt = row[ApiTokens.lastUsedAt],
					createdAt = row[ApiTokens.createdAt],
				)
			}

	/**
	 * The hot path: one probe of `api_tokens_hash_uniq`, on every Bearer request.
	 *
	 * `select` and not `selectAll`, so the digest and the name never enter the JVM on a
	 * path that has no use for either. Small, but this is the one query in the slice that
	 * runs per request, and the columns it does not ask for are columns no future edit of
	 * this method can accidentally log.
	 *
	 * There is no `revoked_at` in the predicate because there is no such column — `V27`
	 * argues why revocation deletes, and this signature is the argument's payoff: a
	 * revoked token cannot be missed by a filter this query forgot to write.
	 */
	fun findByHash(hash: String): AuthenticatedToken? =
		ApiTokens
			.select(ApiTokens.id, ApiTokens.userId, ApiTokens.scopes, ApiTokens.lastUsedAt)
			.where { ApiTokens.hash eq hash }
			.singleOrNull()
			?.let { row ->
				AuthenticatedToken(
					tokenId = row[ApiTokens.id],
					userId = row[ApiTokens.userId],
					scopes = row[ApiTokens.scopes].toSet(),
					lastUsedAt = row[ApiTokens.lastUsedAt],
				)
			}

	fun insert(
		userId: UUID,
		name: String,
		prefix: String,
		hash: String,
		scopes: Set<String>,
	): ApiToken {
		val id = UUID.randomUUID()
		val now = OffsetDateTime.now()
		ApiTokens.insert {
			it[ApiTokens.id] = id
			it[ApiTokens.userId] = userId
			it[ApiTokens.name] = name
			it[ApiTokens.prefix] = prefix
			it[ApiTokens.hash] = hash
			// Sorted, so two tokens granted the same permissions hold the same array and a
			// reader comparing two rows by eye is comparing them by content.
			it[ApiTokens.scopes] = scopes.sorted()
			it[ApiTokens.createdAt] = now
		}
		return ApiToken(
			id = id,
			name = name,
			prefix = prefix,
			scopes = scopes,
			lastUsedAt = null,
			createdAt = now,
		)
	}

	/**
	 * Revocation, and the owner is part of the predicate rather than checked before it.
	 *
	 * One statement, so there is no window between "is this yours" and "delete it" and no
	 * call site that can perform the first check and skip it. A member naming somebody
	 * else's token id deletes nothing and is told nothing — the false return reaches them
	 * as the same 404 an id that never existed gets, which is deliberate: distinguishing
	 * the two would confirm that a guessed id belongs to a real token on this instance.
	 */
	fun delete(userId: UUID, id: UUID): Boolean =
		ApiTokens.deleteWhere { (ApiTokens.id eq id) and (ApiTokens.userId eq userId) } > 0

	fun stamp(id: UUID, at: OffsetDateTime) {
		ApiTokens.update({ ApiTokens.id eq id }) { it[lastUsedAt] = at }
	}
}

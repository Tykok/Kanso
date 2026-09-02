package dev.kanso.tokens

import dev.kanso.oauth.OAuthScopes
import dev.kanso.service.BadRequestException
import org.springframework.http.HttpMethod
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A token as anybody is ever allowed to see it again: everything about it except the one
 * thing it is.
 *
 * There is no `secret` field and no nullable one standing in for it. [NewApiToken] is a
 * separate type returned by exactly one method, so "the plaintext is shown once" is a fact
 * about the type system rather than a rule a caller has to remember — a listing endpoint
 * cannot accidentally serialise a secret it has no field for.
 */
data class ApiToken(
	val id: UUID,
	val name: String,
	val prefix: String,
	val scopes: Set<String>,
	val lastUsedAt: OffsetDateTime?,
	val createdAt: OffsetDateTime,
)

/**
 * The one and only time the secret exists outside the caller's own hands.
 *
 * Returned by `ApiTokenService.create` and by nothing else. There is no method that can
 * fetch it later, because there is no column it could be fetched from.
 */
data class NewApiToken(val token: ApiToken, val secret: String)

/**
 * What a token's scopes let a request do.
 *
 * **One rule, and it is deliberately `McpController`'s rule and not a second one.** There,
 * a tool that writes is refused unless the grant carries [OAuthScopes.WRITE]; reads are
 * not gated at all. Here, an unsafe HTTP method is refused unless the token carries
 * [OAuthScopes.WRITE]; safe ones are not gated at all. Same sentence, two surfaces.
 *
 * The temptation was to also demand [OAuthScopes.READ] for a read, and it is a trap: it
 * would make `{kanso:write}` alone mean "may write but may not read" on the REST surface
 * and "may do everything" on the MCP surface, from one string, with no line of code
 * anywhere saying they differ. `V27`'s CHECK guarantees at least one scope, which is what
 * that demand was really reaching for.
 *
 * Which methods are safe is the same list [dev.kanso.auth.ReadOnlySeat] uses, for the same
 * reason it gives: Kanso has no state-changing `GET`, and the day it grows one, both files
 * have to change.
 */
object ApiTokenScopes {

	private val safe = setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())

	fun refuses(method: String, scopes: Set<String>): Boolean =
		method.uppercase() !in safe && OAuthScopes.WRITE !in scopes

	/** The sentence a refused caller is given. It names the scope so a fix is possible. */
	const val WRITES_NEED_WRITE_SCOPE =
		"This API token was granted ${OAuthScopes.READ} only, and this request writes"

	/**
	 * What a member asked for, checked before a row is written.
	 *
	 * Rejects rather than filters: silently dropping a scope Kanso does not know would
	 * hand back a token that is quietly weaker than the one that was asked for, and the
	 * failure would surface much later as a 403 nobody can explain. `V27`'s CHECK would
	 * refuse the row anyway — this is the same refusal with a sentence attached.
	 */
	fun requested(raw: Collection<String>?): Set<String> {
		val asked = raw.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
		if (asked.isEmpty()) {
			throw BadRequestException("A token needs at least one scope: ${OAuthScopes.ALL.joinToString(", ")}")
		}
		val unknown = asked - OAuthScopes.ALL.toSet()
		if (unknown.isNotEmpty()) {
			throw BadRequestException(
				"Unknown scope ${unknown.sorted().joinToString(", ")} — " +
					"this instance grants ${OAuthScopes.ALL.joinToString(" and ")}",
			)
		}
		return asked
	}
}

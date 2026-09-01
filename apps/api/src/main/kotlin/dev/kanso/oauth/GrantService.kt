package dev.kanso.oauth

import dev.kanso.auth.CurrentUser
import dev.kanso.repo.text
import dev.kanso.service.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * One connected application, as the member who connected it sees it.
 *
 * `scopes` and `scopeProse` are the same list said twice on purpose. The scope names are
 * what the protocol calls them and what an agent's own configuration prints, so a member
 * matching this screen against what `claude mcp add` told them needs the literal string;
 * the prose is [OAuthScopes]'s, rendered here rather than in the browser because those
 * sentences are also the consent screen's, and a second copy of them in TypeScript is a
 * copy that goes stale the day the wording is argued over again.
 *
 * `grantedAt` is the earliest token this member ever took under this client, falling back
 * to the client's own registration instant. Neither is exactly "when you pressed
 * Authorise" — `oauth2_authorization_consent` has no timestamp, and that table is the
 * library's, not ours to widen — but the first token follows the consent screen by
 * seconds, so the date a member reads is the day they connected it.
 */
data class GrantSummary(
	val clientId: String,
	val clientName: String,
	val scopes: List<String>,
	val scopeProse: List<String>,
	val grantedAt: OffsetDateTime,
)

/**
 * What a member has let in, and how they get it back out.
 *
 * Read through `JdbcClient` because the library exposes no listing API at all — its
 * services answer "the consent for this client and this principal", never "everything
 * this principal consented to". Reading tables Kanso copied but does not own is a smaller
 * liberty than it looks: `V16` already binds this codebase to their shape, and
 * `GrantServiceTest` writes every row through the library's own services so the formats
 * this SQL assumes are asserted rather than believed — `authorities` in particular, whose
 * `SCOPE_` prefix is the library's and not a thing we chose.
 *
 * There is no principal parameter, on either method. The member is read from the security
 * context here, so there is no argument a caller could pass that would point this at
 * somebody else's connected applications — the isolation is a property of the signature
 * rather than of every call site remembering to scope.
 */
@Service
class GrantService(
	private val jdbc: JdbcClient,
	private val currentUser: CurrentUser,
) {

	@Transactional(readOnly = true)
	fun list(): List<GrantSummary> = jdbc.sql(LIST)
		.param("principal", currentUser.requireId().toString())
		.query { rs, _ ->
			val scopes = scopesOf(rs.getString("authorities"))
			GrantSummary(
				clientId = rs.text("client_id"),
				clientName = rs.text("client_name"),
				scopes = scopes,
				scopeProse = scopes.map(OAuthScopes::prose),
				grantedAt = rs.getObject("granted_at", OffsetDateTime::class.java),
			)
		}
		.list()

	/**
	 * Revoking deletes the consent *and* every authorisation under it, in one transaction.
	 *
	 * The consent alone would only stop the next authorisation code from skipping the
	 * screen; the tokens already issued would keep working for their full hour, and a
	 * member who has just pressed Revoke because something is wrong would be told it was
	 * done while it was not. `McpBearerFilter` resolves each bearer token against
	 * `oauth2_authorization`, so deleting those rows is what makes the refusal land on the
	 * agent's very next request, refresh token included.
	 *
	 * Nothing of yours attached to that client is a 404 — the same 404 an id nobody ever
	 * registered gets. A 403 would confirm the client exists and somebody else connected
	 * it, which is a fact about another member's setup and not one to leak through a
	 * status code.
	 */
	@Transactional
	fun revoke(clientId: String) {
		val principal = currentUser.requireId().toString()
		// The two tables key on the library's internal row id, not on the client id a
		// member sees, so this indirection is unavoidable rather than a lookup for its
		// own sake.
		val registeredClientId = jdbc.sql("SELECT id FROM oauth2_registered_client WHERE client_id = :clientId")
			.param("clientId", clientId)
			.query(String::class.java)
			.optional()
			.orElse(null)

		val removed = registeredClientId?.let { client ->
			delete(TOKENS, client, principal) + delete(CONSENT, client, principal)
		} ?: 0

		// The id is not echoed back. Two refusals that differ by one character are two
		// refusals, and this one has to be byte-identical whichever reason it has.
		if (removed == 0) throw NotFoundException(NOT_CONNECTED)
	}

	private fun delete(sql: String, registeredClientId: String, principal: String): Int = jdbc.sql(sql)
		.param("client", registeredClientId)
		.param("principal", principal)
		.update()

	/**
	 * The consent row's `authorities`, back to scope names.
	 *
	 * Rebuilt in [OAuthScopes.ALL]'s order rather than the column's, which does two
	 * things at once: the rows read the same way every time, and a scope this instance no
	 * longer grants — a consent that outlived a vocabulary change — is dropped instead of
	 * reaching `prose`, which refuses it and would take the whole listing down with it.
	 */
	private fun scopesOf(authorities: String?): List<String> {
		val granted = authorities.orEmpty()
			.split(",")
			.map { it.trim().removePrefix(SCOPE_PREFIX) }
			.toSet()
		return OAuthScopes.ALL.filter { it in granted }
	}

	private companion object {

		/** `OAuth2AuthorizationConsent.Builder#scope` writes this; nothing here chose it. */
		const val SCOPE_PREFIX = "SCOPE_"

		const val NOT_CONNECTED = "No application connected to your account with that id"

		val LIST = """
			SELECT c.client_id,
			       c.client_name,
			       s.authorities,
			       COALESCE(MIN(a.access_token_issued_at), c.client_id_issued_at) AS granted_at
			  FROM oauth2_authorization_consent s
			  JOIN oauth2_registered_client c ON c.id = s.registered_client_id
			  LEFT JOIN oauth2_authorization a
			         ON a.registered_client_id = s.registered_client_id
			        AND a.principal_name = s.principal_name
			 WHERE s.principal_name = :principal
			 GROUP BY c.client_id, c.client_name, s.authorities, c.client_id_issued_at
			 ORDER BY granted_at DESC
		""".trimIndent()

		const val TOKENS =
			"DELETE FROM oauth2_authorization WHERE registered_client_id = :client AND principal_name = :principal"

		const val CONSENT =
			"DELETE FROM oauth2_authorization_consent " +
				"WHERE registered_client_id = :client AND principal_name = :principal"
	}
}

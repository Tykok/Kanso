package dev.kanso.oauth

import dev.kanso.mcp.McpResource
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import java.util.function.Consumer

/**
 * RFC 8707, enforced where the library does not: a token is issued for *this* resource
 * or it is not issued.
 *
 * The spike drove one real authorisation with `resource` on it and found the parameter
 * **carried, readable, unenforced** — it survives into
 * `OAuth2AuthorizationRequest.additionalParameters` without being a supported feature,
 * and nothing compares it to anything. So a client may ask for a token bound to somebody
 * else's resource and be given one, which is the confused-deputy shape.
 *
 * There are two enforcement points and both are load-bearing. Without this one, a token
 * minted for another resource is accepted here. Without the check at `/api/mcp`, one
 * minted for Kanso is accepted anywhere. Neither is worth writing alone.
 *
 * This one is first for a reason beyond symmetry: the member is still looking at the
 * screen. They find out before consenting, rather than after their agent holds a
 * credential that never works.
 *
 * @param default the library's own validator, consulted first. Order is the rule: an
 *   error on a request whose `redirect_uri` is invalid must not be redirected anywhere,
 *   and that is the delegate's judgement to make.
 * @param expected where this server's canonical URI comes from. One derivation, shared
 *   with the document that publishes it — see [McpResource].
 */
class ResourceValidator(
	private val default: Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext>,
	private val expected: () -> String = McpResource::fromCurrentRequest,
) : Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

	override fun accept(context: OAuth2AuthorizationCodeRequestAuthenticationContext) {
		default.accept(context)

		val token: OAuth2AuthorizationCodeRequestAuthenticationToken = context.getAuthentication()
		val asked = values(token.additionalParameters["resource"])
		val ours = expected()

		// One value, and it is ours. Two bind a token to neither; none binds it to
		// everything, and treating absence as permission is how this check gets undone.
		if (asked.size != 1 || asked.single() != ours) refuse(token, asked, ours)
	}

	/** The spike found one value arrives as a `String` and repeated ones as an `Array`. */
	private fun values(raw: Any?): List<String> = when (raw) {
		null -> emptyList()
		is Array<*> -> raw.mapNotNull { it?.toString() }
		is Collection<*> -> raw.mapNotNull { it?.toString() }
		else -> listOf(raw.toString())
	}

	private fun refuse(
		token: OAuth2AuthorizationCodeRequestAuthenticationToken,
		asked: List<String>,
		ours: String,
	): Nothing {
		val what = when {
			asked.isEmpty() -> "no resource was named"
			asked.size > 1 -> "more than one resource was named: ${asked.joinToString(", ")}"
			else -> "the resource named was ${asked.single()}"
		}
		throw OAuth2AuthorizationCodeRequestAuthenticationException(
			OAuth2Error(
				// RFC 8707's own code. Not `invalid_request`: a client that reads this
				// needs to know its *target* was wrong, not its syntax.
				"invalid_target",
				"This server issues tokens for $ours only, and $what.",
				null,
			),
			token,
		)
	}
}

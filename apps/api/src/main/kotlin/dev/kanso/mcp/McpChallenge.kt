package dev.kanso.mcp

/**
 * The one header that turns a 401 into an invitation.
 *
 * A client with no token calls `/api/mcp`, and everything that follows — discovery,
 * registration, the consent screen, the token — starts from what this header names. A
 * 401 without it is a dead end the client cannot recover from, which is why this is its
 * own file with its own test rather than a string built at the point of failure.
 */
object McpChallenge {

	const val RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"

	fun header(scopes: List<String>, baseUrl: String = ""): String =
		"""Bearer resource_metadata="$baseUrl$RESOURCE_METADATA_PATH", scope="${scopes.joinToString(" ")}""""

	/**
	 * The other 401-shaped answer: the token is fine, the grant is too narrow.
	 *
	 * RFC 6750 §3.1 names this `insufficient_scope`, and the `scope` parameter is what
	 * makes it actionable — a client reads it and runs the authorisation flow again asking
	 * for that scope, instead of reporting a permanent failure to somebody who granted
	 * read-only six weeks ago and has forgotten. Alongside [header] rather than built at
	 * the point of failure, for the same reason that one is: a challenge is the only part
	 * of a refusal a client can act on, and both belong where they can be read together.
	 */
	fun insufficientScope(scope: String): String =
		"""Bearer error="insufficient_scope", scope="$scope""""
}

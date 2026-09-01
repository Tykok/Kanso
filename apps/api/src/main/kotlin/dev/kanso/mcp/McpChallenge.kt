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
}

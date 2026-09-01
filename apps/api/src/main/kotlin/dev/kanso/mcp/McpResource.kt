package dev.kanso.mcp

import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * What this server calls itself when a token is bound to it — derived once, here.
 *
 * RFC 8707 audience binding only works if two places agree: the document that tells a
 * client which `resource` to ask for, and the check that refuses a `resource` naming
 * anything else. Two derivations would eventually disagree, and the symptom would be a
 * token refused by the server that issued it — which reads as a client bug and is not
 * one. So there is one function, and both callers use it.
 *
 * Derived from the request rather than from a property, for the reason `NotionOAuth`
 * derives its own callback: an instance reached on a hostname the property file has
 * never heard of would otherwise publish a document sending clients somewhere that does
 * not answer.
 */
object McpResource {

	/** The endpoint itself is the resource. Not a prefix, not the origin. */
	const val PATH = "/api/mcp"

	/**
	 * No trailing slash: RFC 8707 says implementations should use the form without one,
	 * and a client that normalises differently gets a token whose audience does not match
	 * the string this server compares against.
	 */
	fun canonical(baseUrl: String): String = baseUrl.trimEnd('/') + PATH

	/**
	 * Throws outside a request, deliberately. There is no correct answer without one, and
	 * a fallback origin would be a plausible wrong answer written into every token.
	 */
	fun fromCurrentRequest(): String =
		canonical(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString())
}

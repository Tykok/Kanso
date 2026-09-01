package dev.kanso.oauth

import com.fasterxml.jackson.annotation.JsonProperty
import dev.kanso.mcp.McpChallenge
import dev.kanso.mcp.McpResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * RFC 9728's field names, which are snake_case and not ours to change: a client matches
 * on them literally. `@JsonProperty` rather than a global naming strategy, because the
 * rest of the API is camelCase and one document is not a reason to move all of it.
 *
 * The annotation is `com.fasterxml.jackson.annotation`, which is where Jackson 3 still
 * reads them from — `jackson-annotations:2.21` arrives transitively under
 * `tools.jackson.core:jackson-databind`. Nothing else in this codebase renames a field,
 * so the wire names are asserted by a serialisation round trip rather than trusted.
 */
data class ProtectedResourceMetadata(
	val resource: String,
	@JsonProperty("authorization_servers") val authorizationServers: List<String>,
	@JsonProperty("scopes_supported") val scopesSupported: List<String>,
	@JsonProperty("bearer_methods_supported") val bearerMethodsSupported: List<String>,
)

/**
 * Where a client with nothing begins.
 *
 * Open by specification — it is read before there is any credential to read it with —
 * and listed in [OAuthRoutes] rather than opened inline, so the whole added public
 * surface stays visible in one small file.
 */
@RestController
class ProtectedResourceController {

	@GetMapping(McpChallenge.RESOURCE_METADATA_PATH)
	fun metadata(): ProtectedResourceMetadata {
		val base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().trimEnd('/')
		return ProtectedResourceMetadata(
			resource = McpResource.fromCurrentRequest(),
			authorizationServers = listOf(base),
			scopesSupported = OAuthScopes.ALL,
			// Never `query`: a token in a URL is a token in an access log.
			bearerMethodsSupported = listOf("header"),
		)
	}
}

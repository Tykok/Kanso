package dev.kanso.mcp

import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * **A stub.** Its only job is to prove the door works.
 *
 * Everything this branch built — discovery, registration, the consent screen, the token,
 * the bearer filter — ends at a request mapping, and without one a member who authorises
 * an agent gets a 404 from the endpoint they just granted access to. So this exists to
 * make the deliverable demonstrable rather than asserted: `claude mcp add`, a browser, a
 * yes, and `tools/list` answering over an authenticated session.
 *
 * Plan two decides what replaces it — its own tool surface, or the Spring AI MCP server
 * starter absorbing this file entirely. Nothing here is a commitment: three methods, no
 * transport negotiation, no sessions, no streaming.
 *
 * **It deliberately declares no tools.** An empty list is an honest answer from a server
 * that has none; a stub that pretended to have tools would be a stub somebody trusted,
 * and the first thing they would trust it with is a write into their own backlog.
 *
 * Nothing here checks who is asking, and that is not an omission: this path is reachable
 * only through [McpBearerFilter], which refuses every request that is not carrying a live
 * access token bound to this resource, and refuses all of them in dev mode. A second
 * check written here would be a second answer to a question that already has one.
 */
@RestController
class McpStubController(private val build: BuildProperties) {

	/**
	 * The body arrives as a map rather than a data class, and for a stub that is the
	 * smaller thing: the only field read is `method`, `id` is echoed untouched whatever
	 * JSON type it arrived as (RFC allows a string or a number), and a schema declared
	 * here would be a schema plan two has to delete before it can declare a real one.
	 */
	@PostMapping(
		McpResource.PATH,
		consumes = [MediaType.APPLICATION_JSON_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE],
	)
	fun call(@RequestBody body: Map<String, Any?>): ResponseEntity<Any> {
		val method = body["method"] as? String

		// JSON-RPC 2.0 §4.1: a request with no `id` is a notification, and a server must
		// not answer one — not even to say the method is unknown. A real client sends
		// `notifications/initialized` immediately after `initialize`, so a stub that
		// replied with an error to it would be a stub whose first exchange with every
		// client is a protocol violation. 202 with no body is the answer for the HTTP
		// transport.
		if (!body.containsKey("id")) return ResponseEntity.accepted().build()
		val id = body["id"]

		return when (method) {
			"initialize" -> ResponseEntity.ok(
				JsonRpcResult(
					id = id,
					result = mapOf(
						// Pinned, not echoed back from the client's request. Returning
						// whatever version was asked for would claim support for a
						// revision this file has never seen; a client that cannot speak
						// this one is meant to say so and disconnect. Plan two revisits
						// this line, and it is the only line here that is a protocol
						// decision rather than a placeholder.
						"protocolVersion" to PROTOCOL_VERSION,
						// Declared because `tools/list` really is answered. The empty
						// object is the shape the specification asks for; the empty list
						// below is what is in it.
						"capabilities" to mapOf("tools" to emptyMap<String, Any>()),
						"serverInfo" to mapOf(
							"name" to "kanso",
							// `BuildProperties.getVersion()` is @Nullable for the reason
							// `/api/me` records — absent only if `buildInfo()` never ran.
							"version" to (build.version ?: "unknown"),
						),
					),
				),
			)

			"tools/list" -> ResponseEntity.ok(JsonRpcResult(id = id, result = mapOf("tools" to emptyList<Any>())))

			else -> ResponseEntity.ok(
				JsonRpcFailure(
					id = id,
					// -32601 rather than an HTTP 404: the transport delivered the request
					// fine, and a client that reads the status code instead of the
					// envelope would treat a typo'd method name as an unreachable server
					// and retry the whole connection.
					error = JsonRpcError(code = METHOD_NOT_FOUND, message = "Method not found"),
				),
			)
		}
	}

	private companion object {

		/**
		 * The MCP revision this stub answers as. One string, and it is here rather than
		 * inline so the day plan two supports another the change is in one place.
		 */
		const val PROTOCOL_VERSION = "2025-06-18"

		const val METHOD_NOT_FOUND = -32601
	}
}

/**
 * The two envelopes, kept apart rather than one class with two nullable halves.
 *
 * JSON-RPC 2.0 §5 says a response carries `result` **or** `error` and never both, and a
 * single class would have to be told not to serialise the null one — a Jackson annotation
 * standing in for a rule the type system can state outright.
 */
data class JsonRpcResult(val id: Any?, val result: Any, val jsonrpc: String = JSON_RPC_VERSION)

data class JsonRpcFailure(val id: Any?, val error: JsonRpcError, val jsonrpc: String = JSON_RPC_VERSION)

data class JsonRpcError(val code: Int, val message: String)

private const val JSON_RPC_VERSION = "2.0"

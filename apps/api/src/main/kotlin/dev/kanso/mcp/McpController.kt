package dev.kanso.mcp

import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoAgentUser
import dev.kanso.oauth.OAuthScopes
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * JSON-RPC 2.0 over one `POST /api/mcp`, and the tools behind it.
 *
 * **Hand-written, rather than the Spring AI MCP server starter absorbing this file** —
 * the open question the stub this replaces left for "plan two". The starter does run on
 * Boot 4.1 (`spring-ai-starter-mcp-server-webmvc:2.0.1` depends on
 * `spring-boot-starter-web:4.1.1`), so the spec's stated risk turned out not to be the
 * deciding one. Three others were:
 *
 * 1. **It would decide who is acting, and it would decide it somewhere else.** The MCP
 *    Java SDK runs a synchronous tool as `Mono.fromCallable(…).subscribeOn(boundedElastic())`
 *    unless `immediateExecution(true)` is set, and `SecurityContextHolder` is a
 *    non-inheritable `ThreadLocal` — the *only* place [McpBearerFilter] leaves the acting
 *    member. Spring AI's servlet auto-configuration does set that flag today, in an
 *    unnamed lambda, with no property to pin it and nothing in this suite that would
 *    notice a version bump changing it. The single most important property of this
 *    feature would then rest on an undocumented internal of a transitive dependency, and
 *    the fallback — reading identity out of the Reactor context instead — is a second
 *    answer to "who is acting", which is the failure the whole design opens by refusing.
 * 2. **Weight, for a surface this small.** The starter's transitive set includes `spring-webflux`
 *    (into an application with no reactive stack), `reactor-core`, `spring-messaging`,
 *    `micrometer-tracing`, ANTLR 4 with StringTemplate, three `jsonschema-generator`
 *    modules and `swagger-annotations` — to generate schemas this file writes by hand in
 *    forty lines, and to frame JSON-RPC the stub already framed.
 * 3. **"The library owns the dangerous half" does not transfer.** That argument bought the
 *    authorisation server, and it was right: PKCE, single-use codes and refresh rotation
 *    are security-critical code that is silently wrong for years. A JSON envelope is not.
 *    Getting MCP framing wrong produces a client that will not connect, which is visible
 *    in the first minute.
 *
 * What the starter would genuinely have bought is followed protocol revisions, sessions
 * and server-initiated streaming. None of these tools needs the last two, and the
 * first is a cost this file accepts explicitly: [PROTOCOL_VERSION] is pinned, and the day
 * that stops being true the decision above is worth re-reading rather than assuming.
 *
 * Nothing here checks *whether* the caller is authenticated, and that is not an omission:
 * this path is reachable only through [McpBearerFilter], which refuses every request not
 * carrying a live access token bound to this resource, and refuses all of them in dev
 * mode. What is checked here is what the filter cannot know — which tool was asked for,
 * and whether the grant's scopes cover it.
 */
@RestController
class McpController(
	private val build: BuildProperties,
	private val currentUser: CurrentUser,
	tools: List<McpTool>,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Sorted by name, so `tools/list` answers in a stable order. Not cosmetic: a client
	 * that caches the list keyed on its content would otherwise see it change every
	 * restart, and a reader comparing two servers' answers would see a diff that is not one.
	 */
	private val tools: Map<String, McpTool> = tools.sortedBy { it.name }.associateBy { it.name }

	/**
	 * The body arrives as a map rather than a data class, and that is still the smaller
	 * thing: `id` is echoed untouched whatever JSON type it arrived as (the RFC allows a
	 * string or a number), and the only typed shape underneath — a tool's arguments — is
	 * described by that tool's own schema and read by [McpArguments], which refuses what
	 * the schema does not declare. A `@Valid` data class here would be a third description
	 * of the same object.
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
		// `notifications/initialized` immediately after `initialize`, so a server that
		// replied with an error to it would open every conversation with a protocol
		// violation. 202 with no body is the answer for the HTTP transport.
		if (!body.containsKey("id")) return ResponseEntity.accepted().build()
		val id = body["id"]

		return when (method) {
			"initialize" -> ResponseEntity.ok(JsonRpcResult(id = id, result = initialize()))
			"tools/list" -> ResponseEntity.ok(JsonRpcResult(id = id, result = mapOf("tools" to declarations())))
			"tools/call" -> invoke(id, body["params"])

			else -> ResponseEntity.ok(
				JsonRpcFailure(
					id = id,
					// -32601 rather than an HTTP 404: the transport delivered the request
					// fine, and a client that reads the status code instead of the envelope
					// would treat a typo'd method name as an unreachable server and retry the
					// whole connection.
					error = JsonRpcError(code = METHOD_NOT_FOUND, message = "Method not found"),
				),
			)
		}
	}

	private fun initialize(): Map<String, Any?> = mapOf(
		// Pinned, not echoed back from the client's request. Returning whatever version was
		// asked for would claim support for a revision this file has never seen; a client
		// that cannot speak this one is meant to say so and disconnect.
		"protocolVersion" to PROTOCOL_VERSION,
		// `listChanged: false` is the honest answer, not a placeholder: the surface is a
		// fixed set of beans resolved at startup, so there is no notification this server
		// could ever send,
		// and a client told otherwise would keep a subscription open for one that never comes.
		"capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
		"serverInfo" to mapOf(
			"name" to "kanso",
			// `BuildProperties.getVersion()` is @Nullable for the reason `/api/me` records —
			// absent only if `buildInfo()` never ran.
			"version" to (build.version ?: "unknown"),
		),
	)

	private fun declarations(): List<Map<String, Any?>> = tools.values.map {
		mapOf(
			"name" to it.name,
			"title" to it.title,
			"description" to it.description,
			"inputSchema" to it.inputSchema,
		)
	}

	/**
	 * `tools/call`: the scope gate, the acting identity, the tool, and the two kinds of no.
	 *
	 * **The acting identity is established in exactly one line** — `currentUser.require()`
	 * — and it is the same line `TicketController` and every other controller in this
	 * application uses. [McpBearerFilter] resolved the bearer into a [KansoAgentUser] and
	 * put it on the security context; `CurrentUser` reads `kansoUserId` off whatever
	 * principal is standing there and loads the row. There is no MCP-specific notion of who
	 * is acting, which is what makes `TicketAccess` apply to an agent unchanged.
	 */
	private fun invoke(id: Any?, params: Any?): ResponseEntity<Any> {
		val asked = (params as? Map<*, *>)?.get("name") as? String
		val tool = tools[asked] ?: return ResponseEntity.ok(
			JsonRpcFailure(
				id = id,
				// -32602 rather than tool content: an unknown tool name is a mistake about the
				// protocol, not about the backlog, and a client that treated it as a result
				// would show the model a failure it is expected to reason its way out of.
				error = JsonRpcError(
					code = INVALID_PARAMS,
					message = "No tool named `$asked`. This server serves: ${tools.keys.joinToString()}",
				),
			),
		)

		// Before the tool, before the actor, before anything is read off the arguments: a
		// read-only grant reaching a writing tool must not have caused a lookup, let alone a
		// write. This is the one refusal that stays HTTP — a 403 naming the scope is what a
		// client runs a step-up flow from, where a tool result saying "no" only teaches the
		// agent to rephrase. `McpErrors` argues the other side, for the refusals a bigger
		// token cannot fix.
		if (tool.writes && OAuthScopes.WRITE !in grantedScopes()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.header(HttpHeaders.WWW_AUTHENTICATE, McpChallenge.insufficientScope(OAuthScopes.WRITE))
				.body(
					JsonRpcFailure(
						id = id,
						error = JsonRpcError(
							code = INVALID_REQUEST,
							message = "`${tool.name}` writes, and this connection was granted ${OAuthScopes.READ} only",
						),
					),
				)
		}

		val arguments = when (val given = (params as? Map<*, *>)?.get("arguments")) {
			null -> emptyMap()
			is Map<*, *> -> given.entries.associate { (key, value) -> key.toString() to value }
			else -> return ResponseEntity.ok(
				JsonRpcFailure(
					id = id,
					error = JsonRpcError(code = INVALID_PARAMS, message = "`arguments` must be an object"),
				),
			)
		}

		val text = try {
			tool.call(currentUser.require(), arguments)
		} catch (refused: RuntimeException) {
			// Only what `McpErrors` recognises is turned into a result. Anything else escapes
			// to `ApiExceptionHandler`, which logs it and answers 500 — a server fault
			// described as a tool result is one the agent would retry forever.
			val sentence = McpErrors.sentence(refused) ?: throw refused
			log.debug("Tool {} refused: {}", tool.name, sentence)
			return ResponseEntity.ok(JsonRpcResult(id = id, result = toolResult(sentence, isError = true)))
		}

		return ResponseEntity.ok(JsonRpcResult(id = id, result = toolResult(text, isError = false)))
	}

	/**
	 * The scopes on the grant that carried this request, and an empty set for anything else.
	 *
	 * Nothing but a [KansoAgentUser] can reach this method — the filter is the only way in —
	 * so the fallback is unreachable rather than lenient, and it is written as the empty set
	 * because that is the direction a guard should be wrong in.
	 */
	private fun grantedScopes(): Set<String> =
		(currentUser.principalOrNull() as? KansoAgentUser)?.scopes.orEmpty()

	/**
	 * Built as a map rather than a data class on purpose: MCP spells the flag `isError`,
	 * and a Kotlin `val isError: Boolean` is exactly the property name Jackson's bean
	 * conventions may serialise as `error`. A map cannot be renamed by a convention.
	 */
	private fun toolResult(text: String, isError: Boolean): Map<String, Any?> = mapOf(
		"content" to listOf(mapOf("type" to "text", "text" to text)),
		"isError" to isError,
	)

	private companion object {

		/**
		 * The MCP revision this server answers as. One string, and it is here rather than
		 * inline so the day another is supported the change is in one place.
		 */
		const val PROTOCOL_VERSION = "2025-06-18"

		const val INVALID_REQUEST = -32600
		const val METHOD_NOT_FOUND = -32601
		const val INVALID_PARAMS = -32602
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

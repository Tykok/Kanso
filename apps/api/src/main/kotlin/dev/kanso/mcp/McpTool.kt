package dev.kanso.mcp

import dev.kanso.domain.User

/**
 * One tool, as an agent reads it and as this server runs it.
 *
 * The interface is deliberately narrow — a name, prose, a schema, and one function — and
 * the narrowness is the rule the spec states as "no MCP tool contains business logic".
 * Every implementation validates its arguments, calls a service that already exists, and
 * formats the answer as text. A tool file that grows is a tool that stole work from a
 * service.
 *
 * [call] takes the [User] rather than reaching for the security context itself. There is
 * exactly one place the acting identity is established — `McpBearerFilter` puts a
 * `KansoAgentUser` on the context and [McpController] resolves it through `CurrentUser`,
 * the same two lines every REST controller in this application uses — and a tool that
 * asked again would be a second answer to a question that has one.
 */
interface McpTool {

	/** Namespaced, because it appears in somebody else's client alongside other servers'. */
	val name: String

	/** The short label a client may show instead of [name]. */
	val title: String

	/**
	 * What the tool is for **and when not to use it**. Load-bearing prose: four
	 * well-described tools beat forty only if the descriptions are written as carefully as
	 * the code, and an agent picks by reading this and nothing else.
	 */
	val description: String

	/** JSON Schema for the arguments, object-shaped — see [objectSchema]. */
	val inputSchema: Map<String, Any?>

	/**
	 * Whether this tool needs `kanso:write`.
	 *
	 * Declared rather than inferred, and checked by [McpController] before [call] runs, so
	 * a read-only grant is turned away before a service is reached. A tool that answered
	 * the scope question itself would be the fourth place to keep in step with the other
	 * three.
	 */
	val writes: Boolean

	/**
	 * @return the text an agent reads back. Refusals are raised, not returned: the three
	 *   exceptions in `service/Errors.kt` and `AccessDeniedException` are what every
	 *   service already throws, and [McpErrors] turns them into the same sentences a
	 *   person would have been shown.
	 */
	fun call(actor: User, arguments: Map<String, Any?>): String
}

/**
 * The schemas are written by hand, and that is a decision rather than an omission.
 *
 * Generating them from Kotlin types is what `spring-ai-model` brings three
 * `jsonschema-generator` modules to do, and the generated schema for a tool is the one
 * thing about it an agent reads before deciding whether to call it — prose in the
 * `description` fields included. Written here, the schema and the argument reader that
 * enforces it sit in the same file and cannot drift; generated, they would be a type and
 * a reader that agree until somebody adds a field to one.
 */
internal fun objectSchema(
	vararg properties: Pair<String, Map<String, Any?>>,
	required: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
	"type" to "object",
	"properties" to properties.toMap(),
	"required" to required,
	// The reader refuses an unknown argument anyway — see `McpArguments.refuseUnknown`.
	// This is the same refusal said in the schema, so a client that validates locally
	// never sends the request at all.
	"additionalProperties" to false,
)

internal fun stringField(description: String, enum: List<String>? = null): Map<String, Any?> = buildMap {
	put("type", "string")
	put("description", description)
	enum?.let { put("enum", it) }
}

internal fun stringsField(description: String): Map<String, Any?> =
	mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to description)

internal fun integerField(description: String): Map<String, Any?> =
	mapOf("type" to "integer", "description" to description)

internal fun booleanField(description: String): Map<String, Any?> =
	mapOf("type" to "boolean", "description" to description)

internal fun objectField(description: String): Map<String, Any?> =
	mapOf("type" to "object", "description" to description)

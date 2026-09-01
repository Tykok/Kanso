package dev.kanso.mcp

import dev.kanso.service.BadRequestException

/**
 * The arguments of one `tools/call`, read with a refusal for everything they are not.
 *
 * A JSON-RPC client sends an untyped object, so this is the same problem
 * `TicketFilterVocabulary` solves for a query string and solves the same way: a name
 * nobody serves is refused on the way in rather than dropped. An argument silently
 * ignored is the worst answer available here — the tool reports success, the agent tells
 * a person the work is assigned, and nobody was assigned anything.
 *
 * [refuseUnknown] is therefore not optional politeness; it is the gate, and every tool
 * calls it first.
 */
class McpArguments(private val tool: String, private val raw: Map<String, Any?>) {

	/**
	 * Called before anything is read, so a misspelling is answered before a service is.
	 *
	 * The sentence names what *is* served, because a refusal an agent cannot act on costs
	 * the same round trip as no answer at all — the discipline the Notion import's
	 * refusals already follow.
	 */
	fun refuseUnknown(vararg served: String) {
		val unknown = raw.keys - served.toSet()
		if (unknown.isEmpty()) return
		throw BadRequestException(
			"`$tool` does not take: ${unknown.sorted().joinToString()}." +
				" It takes: ${served.sorted().joinToString()}",
		)
	}

	fun string(name: String): String? = when (val value = raw[name]) {
		null -> null
		is String -> value.trim().takeIf { it.isNotEmpty() }
		else -> throw BadRequestException("`$name` takes text, and `$value` is not text")
	}

	fun requiredString(name: String): String =
		string(name) ?: throw BadRequestException("`$tool` needs `$name`")

	/**
	 * Absent and empty are different answers, so this returns `null` for the first and an
	 * empty list for the second. `assignees: []` is "take everyone off", and a reader that
	 * flattened it into "leave alone" would make handing work back impossible.
	 */
	fun strings(name: String): List<String>? = when (val value = raw[name]) {
		null -> null
		is List<*> -> value.map {
			(it as? String)?.trim()?.takeIf(String::isNotEmpty)
				?: throw BadRequestException("`$name` takes a list of text, and `$it` is not text")
		}
		else -> throw BadRequestException("`$name` takes a list, and `$value` is not one")
	}

	fun integer(name: String): Int? = when (val value = raw[name]) {
		null -> null
		is Number -> value.toInt()
		else -> throw BadRequestException("`$name` takes a whole number, and `$value` is not one")
	}

	fun boolean(name: String, default: Boolean): Boolean = when (val value = raw[name]) {
		null -> default
		is Boolean -> value
		else -> throw BadRequestException("`$name` takes true or false, and `$value` is neither")
	}

	/**
	 * A nested object, handed on untyped.
	 *
	 * Used for `filters`, whose vocabulary belongs to `TicketFilterVocabulary` and not
	 * here: re-declaring the twelve facets in this file is exactly the third dialect the
	 * one-vocabulary rule exists to prevent, so the object is passed through whole and the
	 * gate that owns it does the refusing.
	 */
	@Suppress("UNCHECKED_CAST")
	fun map(name: String): Map<String, Any?> = when (val value = raw[name]) {
		null -> emptyMap()
		is Map<*, *> -> value.entries.associate { (key, entry) -> key.toString() to entry } as Map<String, Any?>
		else -> throw BadRequestException("`$name` takes an object, and `$value` is not one")
	}
}

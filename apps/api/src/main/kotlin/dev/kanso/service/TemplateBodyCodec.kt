package dev.kanso.service

import dev.kanso.domain.EffortPoints
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketPriority
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * `jsonb` in, [TemplateBody] out, and the shape rules on the way through.
 *
 * The rules live here rather than in a CHECK for the reason `user_preferences.shortcuts`
 * gives: no constraint can reach inside a document to ask whether `priority` is one of five
 * words. The column is still `NOT NULL` and still jsonb, so the database refuses a row that
 * is not a document at all; this refuses one that is not a *template*.
 *
 * Refusals name the vocabulary, following `EffortPoints.from` and `parse` in `Model.kt`: a
 * settings screen that says "invalid" has told somebody nothing they can act on.
 */
@Component
class TemplateBodyCodec(private val json: ObjectMapper) {

	/**
	 * Twenty thousand characters of description, which is roughly eight pages.
	 *
	 * Bounded at all because this document is read into memory on every listing of a team's
	 * templates, and because a template is a *skeleton* — the day somebody pastes a
	 * specification into one, the picker is loading it for every person pressing `c`. Not
	 * bounded tightly, because a genuinely long checklist is a legitimate template and a limit
	 * that refuses one is a limit people work around by putting it somewhere worse.
	 */
	private val maxText = 20_000

	@Suppress("UNCHECKED_CAST")
	fun decode(raw: String): TemplateBody {
		val map = json.readValue(raw, Map::class.java) as Map<String, Any?>
		return TemplateBody(
			title = text(map, "title"),
			description = text(map, "description"),
			priority = priority(map["priority"]),
			estimate = estimate(map["estimate"]),
			labels = names(map["labels"]),
			fields = fields(map["fields"]),
		)
	}

	fun encode(body: TemplateBody): String = json.writeValueAsString(toMap(body))

	/** A request's already-parsed map back to the document [decode] reads. */
	fun encodeRaw(map: Map<String, Any?>): String = json.writeValueAsString(map)

	/**
	 * The document a response carries, so a client can send back exactly what it read.
	 *
	 * Keys are *absent* rather than null-valued, which is what makes `decode(encode(b)) == b`:
	 * the codec reads an absent key and an explicit null as the same thing, but a stored
	 * document full of nulls is one a person editing the column by hand would misread as "this
	 * template clears the title".
	 */
	fun toMap(body: TemplateBody): Map<String, Any?> = buildMap {
		body.title?.let { put("title", it) }
		body.description?.let { put("description", it) }
		body.priority?.let { put("priority", it.wire) }
		body.estimate?.let { put("estimate", it) }
		if (body.labels.isNotEmpty()) put("labels", body.labels)
		if (body.fields.isNotEmpty()) put("fields", body.fields)
	}

	/** Parsed through the same vocabulary the wire uses, so a template and a ticket agree. */
	private fun priority(raw: Any?): TicketPriority? {
		val wire = raw as? String ?: return null
		return runCatching { TicketPriority.from(wire) }.getOrElse {
			throw BadRequestException(
				"Unknown priority '$wire' (expected one of " +
					TicketPriority.entries.joinToString { it.wire } + ")",
			)
		}
	}

	private fun estimate(raw: Any?): Int? {
		val points = (raw as? Number)?.toInt() ?: return null
		return runCatching { EffortPoints.from(points) }.getOrElse { failure ->
			throw BadRequestException(failure.message ?: "Unknown estimate '$points'")
		}
	}

	private fun text(map: Map<String, Any?>, key: String): String? {
		val value = map[key] ?: return null
		if (value !is String) throw BadRequestException("Template $key must be text")
		if (value.length > maxText) {
			throw BadRequestException("Template $key is longer than $maxText characters")
		}
		return value
	}

	/**
	 * Trimmed, lower-cased, de-duplicated, order preserved.
	 *
	 * Lower-cased *here* rather than at resolution time so that the stored document and the
	 * comparison agree by construction: a template holding both `Bug` and `bug` would otherwise
	 * resolve to the same label twice and put it on the ticket once, which reads as a bug in
	 * the resolver rather than in the template somebody typed.
	 */
	private fun names(value: Any?): List<String> {
		if (value == null) return emptyList()
		if (value !is List<*>) throw BadRequestException("Template labels must be a list of names")
		if (value.size > 20) throw BadRequestException("A template may name at most 20 labels")
		return value
			.map { it as? String ?: throw BadRequestException("Template labels must be a list of names") }
			.map { it.trim().lowercase() }
			.filter { it.isNotEmpty() }
			.distinct()
	}

	private fun fields(value: Any?): Map<String, String> {
		if (value == null) return emptyMap()
		if (value !is Map<*, *>) throw BadRequestException("Template fields must be a name-to-value map")
		if (value.size > 20) throw BadRequestException("A template may name at most 20 fields")
		return value.entries.associate { (key, raw) ->
			val name = (key as? String)?.trim()
				?: throw BadRequestException("Template field names must be text")
			// Stringified rather than typed: the *definition* owns the type, and this side has no
			// team to ask which definition applies. `TemplateResolver` is where the value meets
			// `FieldValueCodec` and is accepted or reported as unresolved.
			name to (raw?.toString() ?: "")
		}
	}
}

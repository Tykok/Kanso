package dev.kanso.sync.importer

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionProps
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reading somebody else's Notion database.
 *
 * The poller reads databases Kanso created, so it knows every property by name. An
 * imported base was built by someone who never heard of Kanso: the title property may be
 * called anything, and most of the columns will have no counterpart here. So two rules,
 * and they are the whole of it.
 *
 * **The title is found by type, not by name.** A `title` property is the only one Notion
 * requires of every database, and it is the only thing a ticket cannot be created
 * without. Matching on `"Name"` would have imported a French workspace as pages called
 * "Untitled".
 *
 * **A column is adopted only when its name is one Kanso already uses**, compared
 * case-insensitively — `Status`, `Priority`, `Description`, `Start`, `Due`. Guessing wider
 * than that ("Due date" is probably the due date, "Etat" is probably the status) is how an
 * import silently drops work into the wrong column, and everything not adopted is kept
 * verbatim in the "imported from Notion" section anyway. Nothing is lost by being strict;
 * something is lost by being clever.
 */
object NotionPageReader {

	/**
	 * Names that land in a column of their own.
	 *
	 * The mirror's own bookkeeping properties are here too: a base that once *was* a Kanso
	 * mirror, or a copy of one, must not offer `Kanso ID` as content to preserve.
	 */
	private val ADOPTED = setOf(
		NotionProps.NAME,
		NotionProps.STATUS,
		NotionProps.PRIORITY,
		NotionProps.DESCRIPTION,
		NotionProps.START,
		NotionProps.DUE,
		NotionProps.KANSO_ID,
		NotionProps.IDENTIFIER,
	).map { it.lowercase() }.toSet()

	/** Whatever the title property is called, joined. Null when there is nothing to name a row. */
	fun title(page: NotionPage): String? = fields(page)
		.firstOrNull { (_, value) -> value.has("title") }
		?.let { (_, value) -> plainText(value.path("title")) }
		?.takeIf { it.isNotBlank() }

	/**
	 * Why this page cannot become a row here, or null.
	 *
	 * `architecture.md` says a Notion-authored page has no team and no per-team number; the
	 * import supplies both from the request, so what is left is a page with nothing to
	 * call it. Reported rather than filled in with "Untitled", which would bury it among
	 * however many other Untitleds the workspace holds.
	 */
	fun refusal(page: NotionPage): String? = when {
		page.archived -> "the page is in Notion's trash"
		title(page) == null -> "the page has no title, and a ticket cannot be named from nothing"
		else -> null
	}

	fun status(page: NotionPage): TicketStatus? =
		select(page, NotionProps.STATUS)?.let(TicketStatus::fromLabel)

	fun priority(page: NotionPage): TicketPriority? =
		select(page, NotionProps.PRIORITY)?.let(TicketPriority::fromLabel)

	fun description(page: NotionPage): String? =
		property(page, NotionProps.DESCRIPTION)?.let { plainText(it.path("rich_text")) }?.takeIf { it.isNotBlank() }

	fun start(page: NotionPage): KansoInstant? = date(page, NotionProps.START)

	fun due(page: NotionPage): KansoInstant? = date(page, NotionProps.DUE)

	/** Every page this one points at, from every relation property it has. */
	fun relations(page: NotionPage): List<String> = fields(page)
		.filter { (_, value) -> value.has("relation") }
		.flatMap { (_, value) -> value.path("relation").mapNotNull { it.path("id").asText(null) } }
		.distinct()

	/**
	 * The properties Kanso has no column for, in the order Notion listed them, with the
	 * value rendered as text.
	 *
	 * Only properties that actually hold something: a column left empty on this page has
	 * nothing to preserve, and listing it would fill the "imported from Notion" section
	 * with the shape of the database rather than its contents. Relations are absent because
	 * they are not lost — they become dependencies, or they are counted as dropped.
	 */
	fun unmapped(page: NotionPage): List<Pair<String, String>> = fields(page)
		.filterNot { (name, value) ->
			name.lowercase() in ADOPTED || value.has("title") || value.has("relation")
		}
		.mapNotNull { (name, value) -> propertyText(value)?.let { name to it } }

	// --- the json ------------------------------------------------------------

	private fun fields(page: NotionPage): List<Pair<String, JsonNode>> =
		page.properties?.properties()?.map { it.key to it.value }.orEmpty()

	private fun property(page: NotionPage, name: String): JsonNode? =
		fields(page).firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.second

	private fun select(page: NotionPage, name: String): String? = property(page, name)?.let {
		// `status` is Notion's own workflow type and reads the same way a `select` does.
		(it.path("select").path("name").asText(null) ?: it.path("status").path("name").asText(null))
			?.takeIf { value -> value.isNotBlank() }
	}

	private fun date(page: NotionPage, name: String): KansoInstant? =
		property(page, name)?.path("date")?.path("start")?.asText(null)?.let(::instant)

	/**
	 * Notion writes `2026-09-01` for a day and a full ISO instant for a moment; the length
	 * of the string is what tells them apart, exactly as the poller reads it. Anything
	 * unparseable is dropped rather than thrown — one odd page must not fail an import of
	 * a thousand.
	 */
	private fun instant(raw: String): KansoInstant? = runCatching {
		if (raw.length <= 10) KansoInstant(LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC), false)
		else KansoInstant(OffsetDateTime.parse(raw), true)
	}.getOrNull()

	private fun plainText(fragments: JsonNode): String =
		fragments.joinToString("") { it.path("plain_text").asText("") }

	/**
	 * One property value as a line somebody can read.
	 *
	 * Every Notion type Kanso might meet, and null for the ones with nothing to say.
	 * Deliberately lossy: this is the text of a preserved note, not a second schema, and a
	 * rollup or a formula that renders as its displayed value is worth more here than a
	 * faithful copy of Notion's internals.
	 */
	private fun propertyText(value: JsonNode): String? {
		val text = when {
			value.has("rich_text") -> plainText(value.path("rich_text"))
			value.has("select") -> value.path("select").path("name").asText("")
			value.has("status") -> value.path("status").path("name").asText("")
			value.has("multi_select") -> value.path("multi_select").joinToString(", ") { it.path("name").asText("") }
			value.has("number") -> value.path("number").takeIf { !it.isNull }?.asText().orEmpty()
			value.has("checkbox") -> if (value.path("checkbox").asBoolean(false)) "yes" else "no"
			value.has("date") -> dateText(value.path("date"))
			value.has("people") -> value.path("people").joinToString(", ") { it.path("name").asText("") }
			value.has("files") -> value.path("files").joinToString(", ") { it.path("name").asText("") }
			value.has("url") -> value.path("url").asText("")
			value.has("email") -> value.path("email").asText("")
			value.has("phone_number") -> value.path("phone_number").asText("")
			value.has("created_time") -> value.path("created_time").asText("")
			value.has("last_edited_time") -> value.path("last_edited_time").asText("")
			value.has("unique_id") -> value.path("unique_id").let {
				listOfNotNull(it.path("prefix").asText(null), it.path("number").asText(null)).joinToString("-")
			}
			value.has("formula") -> formulaText(value.path("formula"))
			value.has("rollup") -> rollupText(value.path("rollup"))
			else -> null
		}
		return text?.trim()?.takeIf { it.isNotEmpty() }
	}

	private fun dateText(date: JsonNode): String {
		val start = date.path("start").asText(null) ?: return ""
		val end = date.path("end").asText(null)
		return if (end == null) start else "$start → $end"
	}

	private fun formulaText(formula: JsonNode): String = when {
		formula.has("string") -> formula.path("string").asText("")
		formula.has("number") -> formula.path("number").takeIf { !it.isNull }?.asText().orEmpty()
		formula.has("boolean") -> if (formula.path("boolean").asBoolean(false)) "yes" else "no"
		formula.has("date") -> dateText(formula.path("date"))
		else -> ""
	}

	private fun rollupText(rollup: JsonNode): String = when {
		rollup.has("number") -> rollup.path("number").takeIf { !it.isNull }?.asText().orEmpty()
		rollup.has("date") -> dateText(rollup.path("date"))
		// An array rollup is a view of other pages; the pages themselves are what the
		// import brings over, so there is nothing here worth a line of its own.
		else -> ""
	}
}

package dev.kanso.sync.importer

import dev.kanso.domain.KansoInstant
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.sync.notion.NotionPage
import dev.kanso.sync.notion.NotionProps
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reading one page of somebody else's Notion database, through the mapping its base was given.
 *
 * The poller reads databases Kanso created, so it knows every property by name. An imported
 * base was built by someone who never heard of Kanso, and the reader that came before this
 * one answered "which property is the status" from a constant — `Status`, `Priority`,
 * `Description`, `Start`, `Due`, matched case-insensitively. A workspace whose column is
 * called `Etat` therefore imported four hundred tickets in `Todo`. So one rule, and one
 * mapping.
 *
 * **The title is found by type, not by name.** A `title` property is the only one Notion
 * requires of every database, and the only thing a row cannot be created without. There is
 * nothing to ask a human about it, and matching on `"Name"` is precisely what broke.
 *
 * **Every other field comes from [ColumnMapping]** — which column answers it, and what each
 * of that column's options means. A field the mapping does not name reads as null rather
 * than as a guess: the point of asking is that the answer is somebody's rather than this
 * file's, and guessing wider ("Due date" is probably the due date) is how an import
 * silently drops work into the wrong column. An option outside Kanso's closed vocabulary
 * also reads as null, and the *writer* is what applies a default — a reader that
 * substituted one would make `Bloqué` indistinguishable from an empty column.
 *
 * Nothing the mapping left unclaimed is lost: [unmapped] hands it to the "Imported from
 * Notion" section, deliberately lossy — a rollup or a formula rendered as its displayed
 * value is worth more in a preserved note than a faithful copy of Notion's internals.
 */
class MappedPageReader(private val mapping: ColumnMapping) {

	/**
	 * Names [unmapped] never offers, whatever else the page holds.
	 *
	 * Every column the mapping claimed, because it already has a field of its own here, and
	 * the mirror's own two bookkeeping properties: a base that once *was* a Kanso mirror, or
	 * a copy of one, must not offer `Kanso ID` as content to preserve. Everything else is
	 * the mapping's business now.
	 */
	private val claimed: Set<String> = (mapping.columns.values + NotionProps.KANSO_ID + NotionProps.IDENTIFIER)
		.mapTo(mutableSetOf()) { it.lowercase() }

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
	 *
	 * Neither sentence names a kind of row. One reader now serves all four targets, and the
	 * string reaches the reader verbatim through [SkippedPage.reason] — so a teams base used
	 * to report a problem with a ticket.
	 */
	fun refusal(page: NotionPage): String? = when {
		page.archived -> "the page is in Notion's trash"
		title(page) == null -> "the page has no title, and nothing here can be named from nothing"
		else -> null
	}

	/**
	 * A status key, or null for a column Kanso was told nothing about.
	 *
	 * A `String` since `KAN-90`, and unvalidated on purpose: what the reader mapped is a
	 * *key*, and whether the destination team has it is a question this reader has no team
	 * to ask. `TicketService.create` refuses an unknown one by name, which is where every
	 * other door's refusal lives too — so an import naming `in_review` for a team that
	 * removed it fails on that row with a sentence, rather than here with a parse error
	 * that cannot say which team it meant.
	 */
	fun status(page: NotionPage): String? = option(page, ImportField.STATUS)?.let { chosen ->
		// A mapped answer beats a matching label: the reader was shown both and picked.
		mapping.values[ImportField.STATUS]?.get(chosen)
			?: DefaultStatus.fromLabel(chosen)?.wire
	}

	fun priority(page: NotionPage): TicketPriority? = option(page, ImportField.PRIORITY)?.let { chosen ->
		mapping.values[ImportField.PRIORITY]?.get(chosen)?.let(TicketPriority::from)
			?: TicketPriority.fromLabel(chosen)
	}

	/**
	 * A base of projects reads its status from the same [ImportField.STATUS] column, against
	 * the other vocabulary — `Planned`, `Completed` rather than `Todo`, `Done`.
	 *
	 * One field for both because one base is one target: a base whose pages are projects has
	 * no ticket statuses in it, so there is nothing for a second field to disambiguate.
	 */
	fun projectStatus(page: NotionPage): ProjectStatus? = option(page, ImportField.STATUS)?.let { chosen ->
		mapping.values[ImportField.STATUS]?.get(chosen)?.let(ProjectStatus::from)
			?: ProjectStatus.fromLabel(chosen)
	}

	fun description(page: NotionPage): String? =
		property(page, ImportField.DESCRIPTION)?.let { plainText(it.path("rich_text")) }?.takeIf { it.isNotBlank() }

	fun start(page: NotionPage): KansoInstant? = date(page, ImportField.START)

	fun due(page: NotionPage): KansoInstant? = date(page, ImportField.DUE)

	fun end(page: NotionPage): KansoInstant? = date(page, ImportField.END)

	/**
	 * The workspace members one mapped `people` column names.
	 *
	 * Both halves, because they are for different readers: the id is what
	 * `users.notion_person_id` is keyed on, so it is the half that can become a Kanso
	 * account, and the name is for the human being asked which account that is. A person
	 * with no id is not a person Notion can be asked about again, so it is dropped.
	 */
	fun people(page: NotionPage, field: ImportField): List<NotionPerson> =
		property(page, field)?.path("people")?.mapNotNull { person ->
			person.path("id").asText(null)?.let { NotionPerson(it, person.path("name").asText(null)) }
		}.orEmpty()

	/** Every page one mapped relation column points at, in Notion's order. Empty when nothing mapped it. */
	fun relations(page: NotionPage, field: ImportField): List<String> =
		property(page, field)?.path("relation")?.mapNotNull { it.path("id").asText(null) }.orEmpty()

	/**
	 * The properties nothing claimed, in the order Notion listed them, with the value
	 * rendered as text.
	 *
	 * Only properties that actually hold something: a column left empty on this page has
	 * nothing to preserve, and listing it would fill the "imported from Notion" section with
	 * the shape of the database rather than its contents. Relations are absent whether the
	 * mapping claimed them or not — a claimed one becomes a link, and an unclaimed one holds
	 * nothing but page ids, which as a line of prose is a row of uuids nobody can read. The
	 * mapping screen is where a relation column is seen, and where leaving it alone is a
	 * choice somebody made rather than something this file did quietly.
	 */
	fun unmapped(page: NotionPage): List<Pair<String, String>> = fields(page)
		.filterNot { (name, value) -> name.lowercase() in claimed || value.has("title") || value.has("relation") }
		.mapNotNull { (name, value) -> propertyText(value)?.let { name to it } }

	// --- the json ------------------------------------------------------------

	private fun fields(page: NotionPage): List<Pair<String, JsonNode>> =
		page.properties?.properties()?.map { it.key to it.value }.orEmpty()

	/** The column one field was mapped to. Null when the mapping named none — the whole point. */
	private fun property(page: NotionPage, field: ImportField): JsonNode? = mapping.property(field)?.let { name ->
		fields(page).firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.second
	}

	private fun option(page: NotionPage, field: ImportField): String? = property(page, field)?.let {
		// `status` is Notion's own workflow type and reads the same way a `select` does.
		(it.path("select").path("name").asText(null) ?: it.path("status").path("name").asText(null))
			?.takeIf { value -> value.isNotBlank() }
	}

	private fun date(page: NotionPage, field: ImportField): KansoInstant? =
		property(page, field)?.path("date")?.path("start")?.asText(null)?.let(::instant)

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

/**
 * A Notion workspace member as a page names one.
 *
 * [name] is nullable because Notion's own answer is: a `people` value read by an
 * integration without user-information access carries the id and nothing else.
 */
data class NotionPerson(val id: String, val name: String?)

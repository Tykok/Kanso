package dev.kanso.sync.importer

import dev.kanso.sync.notion.NotionPage

/*
 * The prose an imported page keeps, shared by the two writers that keep any.
 *
 * A ticket puts it at the end of its description and a document puts it in a callout at
 * the top, but it is the same section built the same way, so it lives in neither of them:
 * a second copy would be a second answer to "what does the section look like", and the two
 * would drift the first time somebody added a line to one.
 */

/** The heading screen 07 draws above the properties Kanso has no column for. */
private const val SECTION = "Imported from Notion"

/**
 * The description, with the "imported from Notion" section the drawing promises.
 *
 * It goes in the description rather than a column of its own because a column of its own
 * would be a migration, and this is not worth one: the properties are already text by the
 * time anybody reads them, the description is where a ticket's prose lives, and the
 * section is what makes them readable rather than merely stored.
 */
internal fun describe(reader: MappedPageReader, page: NotionPage): String? {
	val body = reader.description(page)
	val section = provenance(reader, page)
	return listOfNotNull(body, section).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/** The section itself: the heading, one line per unmapped property, then where it came from. */
internal fun provenance(reader: MappedPageReader, page: NotionPage): String? {
	val lines = reader.unmapped(page).map { (name, value) -> "$name: $value" }
	if (lines.isEmpty() && page.url == null) return null
	return (listOf(SECTION) + lines + listOfNotNull(page.url)).joinToString("\n")
}

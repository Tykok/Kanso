package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading a page through the mapping its base was given.
 *
 * Every test here is about the one thing that broke: a column's *name* deciding what it
 * means. The title is the single exception — Notion requires exactly one `title` property
 * of every database, so its type is enough — and it has a test of its own for that reason.
 */
class MappedPageReaderTest {

	@Test
	fun `a mapped option outside the vocabulary takes the default rather than becoming a status`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.STATUS to "Etat")))
		val page = fakePage("Ship it", mapOf("Etat" to notionSelect("Bloqué")))

		assertNull(reader.status(page), "the writer is what applies the default")
	}

	@Test
	fun `a mapped value is what decides, not the label`() {
		val reader = MappedPageReader(
			ColumnMapping(
				columns = mapOf(ImportField.STATUS to "Etat"),
				values = mapOf(ImportField.STATUS to mapOf("Bloqué" to "todo")),
			)
		)
		val page = fakePage("Ship it", mapOf("Etat" to notionSelect("Bloqué")))

		assertEquals("todo", reader.status(page))
	}

	@Test
	fun `an unmapped column is preserved verbatim, and a mapped one is not preserved twice`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.STATUS to "Etat")))
		val page = fakePage(
			"Ship it",
			mapOf("Etat" to notionSelect("En cours"), "Sprint" to notionText("S12")),
		)

		assertEquals(listOf("Sprint" to "S12"), reader.unmapped(page))
	}

	@Test
	fun `the title is still found by type, so a French base is not four hundred Untitleds`() {
		val reader = MappedPageReader(ColumnMapping())
		assertEquals("Livrer", reader.title(fakePage("Livrer", titleProperty = "Nom")))
	}

	@Test
	fun `people are read as ids and names, which is what the person screen needs`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.ASSIGNEES to "Qui")))
		val page = fakePage("Ship it", mapOf("Qui" to notionPeople("user-1" to "M. Rey")))

		assertEquals(listOf(NotionPerson("user-1", "M. Rey")), reader.people(page, ImportField.ASSIGNEES))
	}

	@Test
	fun `a column nobody mapped fills no field, however familiar its name looks`() {
		// The whole reason this class replaced a constant: `Status` is only the status
		// because somebody said so, and a workspace where it means something else is
		// somebody else's workspace, not a bug in theirs.
		val reader = MappedPageReader(ColumnMapping())
		val page = fakePage(
			"Ship it",
			mapOf(
				"Status" to notionSelect("Done"),
				"Priority" to notionSelect("High"),
				"Due" to notionDate("2026-09-01"),
			),
		)

		assertNull(reader.status(page))
		assertNull(reader.priority(page))
		assertNull(reader.due(page))
	}

	@Test
	fun `a mapped label still reads, so a workspace in English needs no option table`() {
		val reader = MappedPageReader(
			ColumnMapping(columns = mapOf(ImportField.STATUS to "State", ImportField.PRIORITY to "Urgency"))
		)
		val page = fakePage("Ship it", mapOf("State" to notionSelect("In Progress"), "Urgency" to notionSelect("High")))

		assertEquals("in_progress", reader.status(page))
		assertEquals(TicketPriority.HIGH, reader.priority(page))
	}

	@Test
	fun `a projects base reads the same column against the other vocabulary`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.STATUS to "Etat")))
		val page = fakePage("Refonte", mapOf("Etat" to notionSelect("Completed")))

		assertEquals(ProjectStatus.COMPLETED, reader.projectStatus(page))
		assertNull(reader.status(page), "`Completed` is no ticket status, and a base is one target")
	}

	@Test
	fun `the three dates are three fields, and each reads its own column`() {
		val reader = MappedPageReader(
			ColumnMapping(
				columns = mapOf(
					ImportField.START to "Début",
					ImportField.DUE to "Echéance",
					ImportField.END to "Fin",
				)
			)
		)
		val page = fakePage(
			"Livrer",
			mapOf(
				"Début" to notionDate("2026-09-01"),
				"Echéance" to notionDate("2026-09-10"),
				"Fin" to notionDate("2026-09-30"),
			),
		)

		assertEquals("2026-09-01", reader.start(page)?.at?.toLocalDate()?.toString())
		assertEquals("2026-09-10", reader.due(page)?.at?.toLocalDate()?.toString())
		assertEquals("2026-09-30", reader.end(page)?.at?.toLocalDate()?.toString())
	}

	@Test
	fun `a relation is read from the column the mapping named, and from no other`() {
		val reader = MappedPageReader(ColumnMapping(columns = mapOf(ImportField.BLOCKED_BY to "Attend")))
		val page = fakePage(
			"Ship it",
			mapOf("Attend" to notionRelation("page-1"), "Voir aussi" to notionRelation("page-2")),
		)

		assertEquals(listOf("page-1"), reader.relations(page, ImportField.BLOCKED_BY))
		assertEquals(
			emptyList<String>(),
			reader.relations(page, ImportField.PROJECT),
			"an unmapped field points at nothing",
		)
	}

	@Test
	fun `the mirror's own bookkeeping is never offered as content to preserve`() {
		// A base that once *was* a Kanso mirror, or a copy of one: `Kanso ID` is this
		// instance's own note to itself, not a line somebody wants back in a description.
		val reader = MappedPageReader(ColumnMapping())
		val page = fakePage(
			"Ship it",
			mapOf(
				"Kanso ID" to notionText("f0e1"),
				"Identifier" to notionText("ENG-12"),
				"Sprint" to notionText("S12"),
			),
		)

		assertEquals(listOf("Sprint" to "S12"), reader.unmapped(page))
	}

	@Test
	fun `a formula and a rollup are preserved as the value they display`() {
		// Deliberately lossy: the section is prose somebody reads, not a second schema, and
		// Notion's internals are worth less there than the number on the screen.
		val reader = MappedPageReader(ColumnMapping())
		val page = fakePage(
			"Ship it",
			mapOf(
				"Health" to mapOf("type" to "formula", "formula" to mapOf("type" to "string", "string" to "At risk")),
				"Open tasks" to mapOf("type" to "rollup", "rollup" to mapOf("type" to "number", "number" to 3)),
			),
		)

		assertEquals(listOf("Health" to "At risk", "Open tasks" to "3"), reader.unmapped(page))
	}

	@Test
	fun `a page with nothing to name it, and a page in Notion's trash, are refused`() {
		val reader = MappedPageReader(ColumnMapping())

		assertNull(reader.refusal(fakePage("Ship it")))
		assertEquals(
			"the page is in Notion's trash",
			reader.refusal(fakePage("Ship it", archived = true)),
		)
		assertEquals(
			// No kind of row named: one reader serves four targets, and this string reaches
			// the reader verbatim, so a teams base must not report a problem with a ticket.
			"the page has no title, and nothing here can be named from nothing",
			reader.refusal(untitledPage()),
		)
	}
}

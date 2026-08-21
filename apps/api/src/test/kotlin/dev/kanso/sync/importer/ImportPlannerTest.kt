package dev.kanso.sync.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The arithmetic step 3 prints, over bases already read.
 *
 * Pure, and given its mappings by hand: what a base's columns mean arrives with the
 * request, so a preview of a workspace nobody has mapped is a preview of nothing linked —
 * which is what [NotionImportPreviewTest] asserts through the service. The numbers that
 * need a mapping to exist at all are asserted here.
 */
class ImportPlannerTest {

	@Test
	fun `counts the bases a mapped link crosses between, not the links themselves`() {
		// The drawing's own sentence: "trois bases liées entre elles". Engineering and
		// Meeting notes both wait on Product specs, so all three take part; a hundred
		// arrows between the same two bases would still be two bases.
		val spec = fakePage("Search spec", id = "page-spec-1")
		val specs = FakeDatabase("Product specs", listOf(spec))
		val engineering = FakeDatabase(
			"Engineering tasks",
			listOf(fakePage("Index the archive", mapOf("Attend" to notionRelation(spec.id)), id = "page-eng-1")),
		)
		val meetings = FakeDatabase(
			"Meeting notes",
			listOf(fakePage("Kickoff", mapOf("Attend" to notionRelation(spec.id)), id = "page-meeting-1")),
		)
		val waits = ColumnMapping(mapOf(ImportField.BLOCKED_BY to "Attend"))

		val preview = ImportPlanner.preview(
			listOf(
				planned(engineering, ImportTarget.TICKETS, waits),
				planned(meetings, ImportTarget.TICKETS, waits),
				planned(specs, ImportTarget.TICKETS),
			)
		)

		assertEquals(3, preview.linkedSources)
	}

	@Test
	fun `does not count a link that leaves what is being imported`() {
		// The relation is mapped and read; the base it names is simply not in the plan, so
		// there is no second end for anything to cross into.
		val engineering = FakeDatabase(
			"Engineering tasks",
			listOf(fakePage("Index the archive", mapOf("Attend" to notionRelation("page-spec-1")))),
		)

		val preview = ImportPlanner.preview(
			listOf(planned(engineering, ImportTarget.TICKETS, ColumnMapping(mapOf(ImportField.BLOCKED_BY to "Attend"))))
		)

		assertEquals(0, preview.linkedSources)
	}

	@Test
	fun `counts the rows a relation placed, which a dependency is not one of`() {
		val roadmap = fakePage("Roadmap", id = "page-roadmap")
		val projects = FakeDatabase("Roadmap", listOf(roadmap))
		val first = fakePage("First", mapOf("Projet" to notionRelation(roadmap.id)), id = "page-1")
		val second = fakePage(
			"Second",
			mapOf("Projet" to notionRelation(roadmap.id), "Attend" to notionRelation(first.id)),
			id = "page-2",
		)
		val third = fakePage("Third", id = "page-3")
		val tasks = FakeDatabase("Tasks", listOf(first, second, third))

		val preview = ImportPlanner.preview(
			listOf(
				planned(
					tasks,
					ImportTarget.TICKETS,
					ColumnMapping(mapOf(ImportField.PROJECT to "Projet", ImportField.BLOCKED_BY to "Attend")),
				),
				planned(projects, ImportTarget.PROJECTS),
			)
		)

		assertEquals(2, preview.linkedByRelation, "two of the three tickets were placed by their own column")
		assertEquals(0, preview.fellBack, "where a row lands when nothing placed it is the writer's knowledge")
	}

	@Test
	fun `names the properties the mapping did not claim, and only those`() {
		val tasks = FakeDatabase(
			"Tasks",
			listOf(
				fakePage(
					"Ship it",
					mapOf(
						"Etat" to notionSelect("En cours"),
						"Sprint" to notionNumber(12),
						"Attend" to notionRelation("page-elsewhere"),
					),
				)
			),
		)

		val preview = ImportPlanner.preview(
			listOf(
				planned(
					tasks,
					ImportTarget.TICKETS,
					ColumnMapping(mapOf(ImportField.STATUS to "Etat", ImportField.BLOCKED_BY to "Attend")),
				)
			)
		)

		assertEquals(listOf("Sprint"), preview.unmappedProperties)
	}
}

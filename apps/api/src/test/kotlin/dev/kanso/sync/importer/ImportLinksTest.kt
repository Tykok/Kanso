package dev.kanso.sync.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportLinksTest {

	private fun planned(base: FakeDatabase, target: ImportTarget, mapping: ColumnMapping) =
		PlannedBase(
			base = WorkspaceBase(base.dataSourceId, base.databaseId, base.name),
			target = target,
			pages = base.pages,
			mapping = mapping,
		)

	@Test
	fun `a link declared only on the parent's side is read backwards`() {
		val ship = fakePage("Ship it")
		val tasks = FakeDatabase("Tasks", listOf(ship))
		val roadmap = fakePage("Roadmap", mapOf("Tasks" to notionRelation(ship.id)))
		val projects = FakeDatabase("Projects", listOf(roadmap))

		val resolved = ImportLinks.resolve(
			listOf(
				planned(tasks, ImportTarget.TICKETS, ColumnMapping()),
				planned(projects, ImportTarget.PROJECTS, ColumnMapping(mapOf(ImportField.TICKETS to "Tasks"))),
			)
		)

		assertEquals(mapOf(ship.id to roadmap.id), resolved.projectOfTicket)
	}

	@Test
	fun `when both sides speak and disagree, the child wins and the disagreement is counted`() {
		val ship = fakePage("Ship it", mapOf("Project" to notionRelation("page-b")))
		val tasks = FakeDatabase("Tasks", listOf(ship))
		val projectA = fakePage("A", mapOf("Tasks" to notionRelation(ship.id)), id = "page-a")
		val projectB = fakePage("B", id = "page-b")
		val projects = FakeDatabase("Projects", listOf(projectA, projectB))

		val resolved = ImportLinks.resolve(
			listOf(
				planned(tasks, ImportTarget.TICKETS, ColumnMapping(mapOf(ImportField.PROJECT to "Project"))),
				planned(projects, ImportTarget.PROJECTS, ColumnMapping(mapOf(ImportField.TICKETS to "Tasks"))),
			)
		)

		assertEquals("page-b", resolved.projectOfTicket[ship.id], "the row that names its own parent wins")
		assertEquals(1, resolved.conflicts)
	}

	@Test
	fun `only the mapped dependency column becomes a dependency`() {
		val first = fakePage("First", id = "page-1")
		val second = fakePage("Second", id = "page-2", properties = mapOf(
			"Blocked by" to notionRelation("page-1"),
			"Related" to notionRelation("page-1"),
		))
		val tasks = FakeDatabase("Tasks", listOf(first, second))

		val resolved = ImportLinks.resolve(
			listOf(planned(tasks, ImportTarget.TICKETS, ColumnMapping(mapOf(ImportField.BLOCKED_BY to "Blocked by"))))
		)

		assertEquals(listOf(PageDependency("page-1", "page-2")), resolved.dependencies)
	}
}

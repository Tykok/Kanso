package dev.kanso.sync.importer

import dev.kanso.domain.ProjectStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

/**
 * The order the four passes run in, and what that order buys.
 *
 * A relation resolves only once the row it names exists, so teams are written before the
 * projects that name them and projects before the tickets that name them — whatever order
 * the plan happens to list the bases in. Every test here goes through
 * [NotionImportService.perform], because the sequencing is the server's own and a test
 * that called the four writers by hand would be asserting its own order rather than the
 * one that ships.
 *
 * `@Transactional` for the reason every sibling import test carries it: `admin` and `team`
 * are lazy repository calls, and Exposed needs a transaction in context the first time
 * either is touched. The suite rolls back, so nothing here reaches `pg_notify`.
 */
@Transactional
class ImportOrderTest : ImportTestBase() {

	@Test
	fun `teams, projects and tickets are written in that order whatever order the plan names them`() {
		val platform = fakePage("Platform")
		val web = fakePage("Web", mapOf("Parent team" to notionRelation(platform.id)))
		// The child first, so the pass that sets parents cannot rely on Notion's own order.
		val teams = FakeDatabase("Teams", listOf(web, platform))

		val roadmap = fakePage("Roadmap", mapOf("Team" to notionRelation(web.id)))
		val projects = FakeDatabase("Projects", listOf(roadmap))

		val ship = fakePage("Ship it", mapOf("Project" to notionRelation(roadmap.id)))
		val tasks = FakeDatabase("Tasks", listOf(ship))

		// Named tickets-first on purpose: the order is the server's, not the request's.
		val outcome = importerFor(teams, projects, tasks).perform(
			admin, team.id,
			listOf(
				mapped(tasks, ImportTarget.TICKETS, ImportField.PROJECT to "Project"),
				mapped(projects, ImportTarget.PROJECTS, ImportField.TEAM to "Team"),
				mapped(teams, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent team"),
			),
		)

		assertEquals(2, outcome.teams)
		assertEquals(1, outcome.projects, "the ticket resolved, so no container was needed")
		assertEquals(1, outcome.tickets)

		val webTeam = teamService.list(includeArchived = false).first { it.name == "Web" }
		val platformTeam = teamService.list(includeArchived = false).first { it.name == "Platform" }
		assertEquals(platformTeam.id, webTeam.parentTeamId, "the parent relation was read")

		val project = projectRows.search(teamIds = null, includeArchived = false).first { it.name == "Roadmap" }
		assertEquals(webTeam.id, project.teamId, "the project landed in the team its relation named")

		val ticket = ticketRows.search(includeArchived = false, limit = 50).first { it.title == "Ship it" }
		assertEquals(project.id, ticket.projectId, "and the ticket in the project its relation named")
		assertEquals(webTeam.id, ticket.teamId, "with the project's team, which is where its number comes from")
		assertNotNull(ticket.number)
	}

	@Test
	fun `a ticket whose project relation resolves to nothing lands in the project named after its base`() {
		val ship = fakePage("Ship it", mapOf("Project" to notionRelation("page-nowhere")))
		val tasks = FakeDatabase("Tasks", listOf(ship))

		importerFor(tasks).perform(
			admin, team.id,
			listOf(mapped(tasks, ImportTarget.TICKETS, ImportField.PROJECT to "Project")),
		)

		val project = projectRows.search(teamIds = null, includeArchived = false).first()
		assertEquals("Tasks", project.name, "the base's own name, which is today's behaviour")
	}

	@Test
	fun `a base whose every ticket resolves gets no container project at all`() {
		val roadmap = fakePage("Roadmap")
		val projects = FakeDatabase("Projects", listOf(roadmap))
		val ship = fakePage("Ship it", mapOf("Project" to notionRelation(roadmap.id)))
		val tasks = FakeDatabase("Tasks", listOf(ship))

		importerFor(projects, tasks).perform(
			admin, team.id,
			listOf(
				mapped(tasks, ImportTarget.TICKETS, ImportField.PROJECT to "Project"),
				mapped(projects, ImportTarget.PROJECTS),
			),
		)

		assertEquals(
			listOf("Roadmap"),
			projectRows.search(teamIds = null, includeArchived = false).map { it.name },
			"no project named after the tasks base: nothing needed one",
		)
	}

	@Test
	fun `a project reads the end date and the status its mapping names`() {
		val roadmap = fakePage(
			"Roadmap",
			mapOf("Etat" to notionSelect("Completed"), "Livraison" to notionDate("2026-12-01")),
		)
		val projects = FakeDatabase("Projects", listOf(roadmap))

		importerFor(projects).perform(
			admin, team.id,
			listOf(mapped(projects, ImportTarget.PROJECTS, ImportField.STATUS to "Etat", ImportField.END to "Livraison")),
		)

		val project = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals("2026-12-01", project.end?.at?.toLocalDate()?.toString(), "the mapped end column was read")
		assertEquals(ProjectStatus.COMPLETED, project.status)
	}

	@Test
	fun `a project whose status nobody mapped is in progress, not planned`() {
		val projects = FakeDatabase("Projects", listOf(fakePage("Roadmap")))

		importerFor(projects).perform(admin, team.id, plan(projects to ImportTarget.PROJECTS))

		val project = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals(ProjectStatus.IN_PROGRESS, project.status, "imported work is work already started elsewhere")
	}

	@Test
	fun `a second run of the same base adds its new page to the container the first run made`() {
		val dataSourceId = "ds-tasks-partial"
		val first = FakeDatabase("Tasks", listOf(fakePage("Ship it", id = "page-one")), dataSourceId = dataSourceId)
		importerFor(first).perform(admin, team.id, plan(first to ImportTarget.TICKETS))

		val second = FakeDatabase(
			"Tasks",
			listOf(fakePage("Ship it", id = "page-one"), fakePage("Then rest", id = "page-two")),
			dataSourceId = dataSourceId,
		)
		val outcome = importerFor(second).perform(admin, team.id, plan(second to ImportTarget.TICKETS))

		assertEquals(1, outcome.tickets, "only the new page")
		assertEquals(0, outcome.projects, "and no second container named after the same base")
		val container = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals("Tasks", container.name)
		assertEquals(
			listOf(container.id, container.id),
			ticketRows.search(includeArchived = false, limit = 50).map { it.projectId },
			"both runs' tickets are in the one container",
		)
	}

	@Test
	fun `a relation naming a team imported by an earlier run still resolves`() {
		val platform = fakePage("Platform", id = "page-platform")
		val teams = FakeDatabase("Teams", listOf(platform), dataSourceId = "ds-teams-earlier")
		importerFor(teams).perform(admin, team.id, plan(teams to ImportTarget.TEAMS))

		// A second run: every page of the teams base is already imported, so this run
		// creates no team at all and the page the relation names can only be resolved
		// through `notion_import_origin`.
		val projects = FakeDatabase("Projects", listOf(fakePage("Roadmap", mapOf("Team" to notionRelation(platform.id)))))
		val outcome = importerFor(teams, projects).perform(
			admin, team.id,
			listOf(
				mapped(teams, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent team"),
				mapped(projects, ImportTarget.PROJECTS, ImportField.TEAM to "Team"),
			),
		)
		assertEquals(0, outcome.teams, "the team was written by the earlier run")
		assertEquals(0, outcome.droppedRelations, "and its page still resolves, so nothing was dropped")

		val platformTeam = teamService.list(includeArchived = false).first { it.name == "Platform" }
		val project = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals(platformTeam.id, project.teamId, "the row a page became last month is still the row it became")
	}

	@Test
	fun `two projects claiming the same ticket is reported as a disagreement`() {
		val ship = fakePage("Ship it", id = "page-ship")
		val tasks = FakeDatabase("Tasks", listOf(ship))
		val projects = FakeDatabase(
			"Projects",
			listOf(
				fakePage("Roadmap", mapOf("Tasks" to notionRelation(ship.id)), id = "page-roadmap"),
				fakePage("Backlog", mapOf("Tasks" to notionRelation(ship.id)), id = "page-backlog"),
			),
		)

		val outcome = importerFor(projects, tasks).perform(
			admin, team.id,
			listOf(
				mapped(tasks, ImportTarget.TICKETS),
				mapped(projects, ImportTarget.PROJECTS, ImportField.TICKETS to "Tasks"),
			),
		)

		assertEquals(1, outcome.linkConflicts, "neither claim is more correct, so the count is how anybody finds out")
		val roadmap = projectRows.search(teamIds = null, includeArchived = false).first { it.name == "Roadmap" }
		assertEquals(
			roadmap.id,
			ticketRows.search(includeArchived = false, limit = 50).single().projectId,
			"and the first claim is kept, which is stable across two runs where the last is not",
		)
	}

	@Test
	fun `a team parent that would close a loop drops that one arrow and imports the rest`() {
		// Each names the other as its parent: whichever is moved first, the second move is
		// a cycle. `TeamService.update` refuses it, and one refused arrow is not a reason
		// to fail an import.
		val north = fakePage("North", mapOf("Parent team" to notionRelation("page-south")), id = "page-north")
		val south = fakePage("South", mapOf("Parent team" to notionRelation("page-north")), id = "page-south")
		val teams = FakeDatabase("Teams", listOf(north, south))

		val outcome = importerFor(teams).perform(
			admin, team.id,
			listOf(mapped(teams, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent team")),
		)

		assertEquals(2, outcome.teams, "both teams exist")
		val imported = teamService.list(includeArchived = false).filter { it.name in setOf("North", "South") }
		assertEquals(1, imported.count { it.parentTeamId != null }, "one arrow was placed, the other dropped")
	}
}

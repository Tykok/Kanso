package dev.kanso.sync.importer

import dev.kanso.domain.DispositionPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

/**
 * A page imported once, whose Kanso row somebody has since deleted.
 *
 * `notion_import_origin` carries no foreign key — the reference is polymorphic, and `V15`
 * says so — and neither `TeamService.delete` nor `ProjectService.delete` touches the table.
 * So a row that outlives the thing it names is ordinary rather than exotic, and the seed the
 * writer reads out of that table is full of ids that may no longer exist. Every one of these
 * tests failed with an exception before the seed was filtered to live rows: an id that is
 * *present but dead* is the one thing a `?: fallback` chain cannot fall through, so the
 * import did not fall back, it aborted — and aborted again on every later run, because the
 * rollback left the origin row exactly where it was.
 *
 * Deleted through the services rather than by deleting rows underneath them, so what is
 * exercised is a state the application can actually reach.
 *
 * `@Transactional` for the same reason every sibling import test carries it: `admin` and
 * `team` are lazy repository calls, and Exposed needs a transaction in context the first
 * time either is touched. The suite rolls back, so nothing here reaches `pg_notify`.
 */
@Transactional
class ImportDeletedRowTest : ImportTestBase() {

	@Test
	fun `a tickets base whose container project was deleted gets a fresh one`() {
		val dataSourceId = "ds-tasks-deleted-container"
		val first = FakeDatabase("Tasks", listOf(fakePage("Ship it", id = "page-one")), dataSourceId = dataSourceId)
		importerFor(first).perform(admin, team.id, plan(first to ImportTarget.TICKETS))

		val container = projectRows.search(teamIds = null, includeArchived = false).single()
		// KEEP, the default: the ticket the first run made loses its project and stays,
		// which is the state a reader who deletes a project from the list actually lands in.
		projectService.delete(admin, container.id, DispositionPlan(counts = projectService.contents(container.id).direct))

		val second = FakeDatabase(
			"Tasks",
			listOf(fakePage("Ship it", id = "page-one"), fakePage("Then rest", id = "page-two")),
			dataSourceId = dataSourceId,
		)
		val outcome = importerFor(second).perform(admin, team.id, plan(second to ImportTarget.TICKETS))

		assertEquals(1, outcome.tickets, "only the new page")
		assertEquals(1, outcome.projects, "the container this base had is gone, so this run makes one")
		val fresh = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals("Tasks", fresh.name)
		assertNotEquals(container.id, fresh.id, "a new project, not the deleted one")
		assertEquals(
			fresh.id,
			ticketRows.search(includeArchived = false, limit = 50).first { it.title == "Then rest" }.projectId,
			"and the new ticket is in it",
		)
	}

	@Test
	fun `a project whose team row was deleted lands in the fallback team`() {
		val platform = fakePage("Platform", id = "page-platform")
		val teamsBase = FakeDatabase("Teams", listOf(platform), dataSourceId = "ds-teams-deleted")
		importerFor(teamsBase).perform(admin, team.id, plan(teamsBase to ImportTarget.TEAMS))

		val platformTeam = teamService.list(includeArchived = false).first { it.name == "Platform" }
		teamService.delete(admin, platformTeam.id, DispositionPlan(counts = teamService.contents(platformTeam.id).direct))

		// The teams base is in the plan again — a relation only resolves to a page the plan
		// carries — and every page of it is already imported, so the only thing that could
		// answer "which team is this" is `notion_import_origin`.
		val projectsBase = FakeDatabase(
			"Projects",
			listOf(fakePage("Roadmap", mapOf("Team" to notionRelation(platform.id)))),
		)
		importerFor(teamsBase, projectsBase).perform(
			admin, team.id,
			listOf(
				mapped(teamsBase, ImportTarget.TEAMS),
				mapped(projectsBase, ImportTarget.PROJECTS, ImportField.TEAM to "Team"),
			),
		)

		val project = projectRows.search(teamIds = null, includeArchived = false).single()
		assertEquals(team.id, project.teamId, "the request's own team, the last answer in the chain")
	}

	@Test
	fun `a team whose parent row was deleted keeps no parent`() {
		val dataSourceId = "ds-teams-parent-deleted"
		val platform = fakePage("Platform", id = "page-platform")
		val first = FakeDatabase("Teams", listOf(platform), dataSourceId = dataSourceId)
		importerFor(first).perform(
			admin, team.id,
			listOf(mapped(first, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent team")),
		)

		val platformTeam = teamService.list(includeArchived = false).first { it.name == "Platform" }
		teamService.delete(admin, platformTeam.id, DispositionPlan(counts = teamService.contents(platformTeam.id).direct))

		// A new page naming the deleted team's page as its parent. The parent pass only
		// walks adoptable pages, so this is the shape that reaches it: a page written now,
		// pointing at a page written last month.
		val second = FakeDatabase(
			"Teams",
			listOf(platform, fakePage("Web", mapOf("Parent team" to notionRelation(platform.id)), id = "page-web")),
			dataSourceId = dataSourceId,
		)
		val outcome = importerFor(second).perform(
			admin, team.id,
			listOf(mapped(second, ImportTarget.TEAMS, ImportField.PARENT_TEAM to "Parent team")),
		)

		assertEquals(1, outcome.teams, "only the new page")
		val web = teamService.list(includeArchived = false).first { it.name == "Web" }
		assertNull(web.parentTeamId, "the parent it named is gone, so it has none — not a failed import")
	}
}

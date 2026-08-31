package dev.kanso.sync.importer

import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.repo.TeamRepository
import dev.kanso.service.BadRequestException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Where a row lands when no relation answers.
 *
 * `@Transactional` for the reason every sibling import test carries it: `admin` and `team`
 * are lazy repository calls, and Exposed needs a transaction in context the first time
 * either is touched. The suite rolls back, so nothing here reaches `pg_notify`.
 */
@Transactional
class ImportFallbackTest : ImportTestBase() {

	@Autowired lateinit var teamRows: TeamRepository

	@Test
	fun `a teams-only plan needs no destination team`() {
		val teams = FakeDatabase("Teams", listOf(fakePage("Platform")))
		val outcome = importerFor(teams).perform(admin, null, plan(teams to ImportTarget.TEAMS))
		assertEquals(1, outcome.teams)
	}

	@Test
	fun `anything else without a destination is refused before a page is read`() {
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))
		val importer = importerFor(tasks)

		val failure = assertThrows<BadRequestException> {
			importer.perform(admin, null, plan(tasks to ImportTarget.TICKETS))
		}
		assertTrue(failure.message!!.contains("team"))
		assertEquals(RowCounts(0, 0, 0, 0), rowCounts())
	}

	@Test
	fun `a base's own fallback beats the request's destination`() {
		val other = teamService.create(admin, "Other", "OTH", null)
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))

		importerFor(tasks).perform(
			admin, team.id,
			listOf(
				ImportPlanEntry(
					sourceId = tasks.dataSourceId,
					target = ImportTarget.TICKETS,
					fallback = Fallback(teamId = other.id),
				),
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).first()
		assertEquals(other.id, ticket.teamId)
	}

	@Test
	fun `a fallback project is used instead of creating one named after the base`() {
		val existing = projectService.create(
			name = "Existing", status = ProjectStatus.IN_PROGRESS, start = null, end = null,
			leadUserId = null, teamId = team.id, docIds = emptyList(),
		).project
		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))

		importerFor(tasks).perform(
			admin, team.id,
			listOf(
				ImportPlanEntry(
					sourceId = tasks.dataSourceId,
					target = ImportTarget.TICKETS,
					fallback = Fallback(projectId = existing.id),
				),
			),
		)

		assertEquals(1, projectRows.search(teamIds = null, includeArchived = false).size)
		assertEquals(existing.id, ticketRows.search(includeArchived = false, limit = 50).first().projectId)
	}

	@Test
	fun `a fallback team the actor may not write to is refused, and nothing is written`() {
		// `team` gets a member so the "empty team" open door does not grant `outsider`
		// access to it by accident; `outsider` is then added to it directly, so the
		// destination itself is fine and only the fallback is the reason for the refusal.
		teamRows.addMember(team.id, admin.id, MemberRole.MEMBER)
		teamRows.addMember(team.id, outsider.id, MemberRole.MEMBER)
		// `other` gets a member too, but not `outsider` — closed to them specifically.
		val other = teamService.create(admin, "Other", "OTH", null)
		teamRows.addMember(other.id, admin.id, MemberRole.MEMBER)

		val tasks = FakeDatabase("Tasks", listOf(fakePage("Ship it")))
		val before = rowCounts()

		assertThrows<AccessDeniedException> {
			importerFor(tasks).perform(
				outsider, team.id,
				listOf(
					ImportPlanEntry(
						sourceId = tasks.dataSourceId,
						target = ImportTarget.TICKETS,
						fallback = Fallback(teamId = other.id),
					),
				),
			)
		}
		assertEquals(before, rowCounts(), "and nothing was written on the way to the refusal")
	}

	private val outsider by lazy {
		users.createLocalUser(
			email = "outsider-${UUID.randomUUID()}@kanso.test",
			displayName = "Outsider",
			passwordHash = "x",
			role = InstanceRole.MEMBER,
		)
	}
}

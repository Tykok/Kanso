package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which buckets a grouped page has, and in what order — `KAN-28`.
 *
 * Two answers, and the scope chooses. A team reading its own list gets its own words in
 * its own order, because `team_statuses.position` is what that order now is. A scope
 * spanning teams gets the five categories, because the header of a list holding two
 * vocabularies has to be the fact both of them agree on: `Todo` renamed to `Qualifié` in
 * one team and left alone in another is one bucket, and the bucket is `unstarted`.
 */
@Transactional
class GroupedStatusOrderTest : PostgresTest() {

	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var statuses: TeamStatusService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun owner(): User = users.createLocalUser(
		email = "grouped-${UUID.randomUUID()}@kanso.test",
		displayName = "Owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.OWNER,
	)

	private fun ticket(actor: User, teamId: UUID, title: String, status: String) =
		tickets.create(
			actor = actor,
			teamId = teamId,
			title = title,
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			estimate = null,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

	private fun buckets(teamId: UUID?) = tickets.grouped(
		teamId = teamId,
		includeDescendants = false,
		includeArchived = false,
		filters = TicketFilters(),
		groupBy = ViewGroupBy.STATUS,
		sortBy = ViewSortBy.UPDATED,
		limit = 50,
		offset = 0,
	)

	@Test
	fun `one team's page is stacked in that team's own order`() {
		val actor = owner()
		val team = teams.create(actor, "Ordered ${UUID.randomUUID()}", null, null)
		ticket(actor, team.id, "A", "backlog")
		ticket(actor, team.id, "B", "todo")

		statuses.reorder(
			actor,
			team.id,
			listOf("todo", "backlog", "in_progress", "in_review", "done", "canceled"),
		)

		// `StatusOrder.WORKFLOW` would have said backlog first. The team said otherwise,
		// and the team is what a grouped page now ranks by.
		assertEquals(listOf("todo", "backlog"), buckets(team.id).map { it.key })
	}

	@Test
	fun `a scope spanning teams is stacked by category, and shares its buckets`() {
		val actor = owner()
		val one = teams.create(actor, "One ${UUID.randomUUID()}", null, null)
		val other = teams.create(actor, "Other ${UUID.randomUUID()}", null, null)
		statuses.rename(actor, other.id, "todo", "Qualifié")
		ticket(actor, one.id, "A", "todo")
		ticket(actor, other.id, "B", "todo")
		ticket(actor, other.id, "C", "done")

		val all = buckets(null)

		// One bucket for the two `todo` rows even though one team calls it `Qualifié`, and
		// it is named by the fact rather than by either team's word for it.
		assertEquals(2, all.single { it.key == "unstarted" }.count)
		assertEquals(listOf("unstarted", "completed"), all.map { it.key })
	}

	@Test
	fun `a team that has not reordered anything reads as it always did`() {
		val actor = owner()
		val team = teams.create(actor, "Untouched ${UUID.randomUUID()}", null, null)
		ticket(actor, team.id, "A", "done")
		ticket(actor, team.id, "B", "backlog")

		// The seed is `StatusOrder.WORKFLOW`, so nothing about this answer changed for the
		// teams that existed before `V41`.
		assertEquals(listOf("backlog", "done"), buckets(team.id).map { it.key })
	}
}

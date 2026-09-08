package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.StatusCategory
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Has anything moved along" — screen 08's fourth step, and the one fact the sidebar's
 * checklist could not answer without fetching a ticket list (KAN-65).
 *
 * Both directions, because a predicate that answers `true` unconditionally passes any
 * test that only ever starts a ticket. The negative case is the one that matters: filing
 * work is not moving it, and a checklist that ticked "Move it along" the moment somebody
 * typed a title would be congratulating them for the previous step.
 */
@Transactional
class MovedAlongTest : PostgresTest() {

	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var service: TicketService
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var users: UserRepository

	private val owner: User by lazy {
		users.createLocalUser(
			email = "moved-${UUID.randomUUID()}@kanso.test",
			displayName = "Moved owner",
			passwordHash = "not-a-real-hash",
			role = InstanceRole.OWNER,
		)
	}

	private fun ticketIn(status: String): UUID {
		val team = teams.insert(
			name = "Moved ${UUID.randomUUID()}",
			key = "M${UUID.randomUUID().toString().take(4).uppercase()}",
			parentTeamId = null,
		)
		return service.create(
			actor = owner,
			teamId = team.id,
			title = "Something to move",
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
	}

	/**
	 * Asserted as "did not change the answer" rather than as `false`, and the difference
	 * is not pedantry: the suite shares one database and this predicate is deliberately
	 * instance-wide, so another class's committed row could make a bare `assertFalse`
	 * fail for a reason that has nothing to do with the code under test. Reading the
	 * baseline first is what makes the negative case survive that — if `backlog` or
	 * `todo` were matched, filing two of them would flip a false baseline to true.
	 */
	@Test
	fun `filing work is not moving it`() {
		val baseline = tickets.anyMovedAlong()

		ticketIn("backlog")
		ticketIn("todo")

		assertEquals(
			baseline,
			tickets.anyMovedAlong(),
			"a ticket nobody has picked up must not tick `Move it along`",
		)
	}

	@Test
	fun `starting one moves it along`() {
		val id = ticketIn("backlog")
		service.patch(owner, id, TicketPatch(status = "in_progress"))

		assertTrue(tickets.anyMovedAlong(), "a started ticket is work that has moved")
	}

	/**
	 * Every category the checklist counts as movement, one ticket each — `in_review` and
	 * `canceled` included, which are the two a hand-written list of statuses forgets.
	 * `canceled` reads odd and is right: somebody decided about that ticket, and deciding
	 * not to do it is the step this asks about having happened.
	 */
	@Test
	fun `review, done and canceled all count as moved`() {
		// The *categories* the probe asks about since `KAN-90`, and the seeded statuses
		// that fall in them — which is what this test was always really pinning. A list of
		// statuses could only have named Kanso's own; the pair below still says exactly
		// which four seeded words count, and now says it for a team's inventions too.
		val moved = DefaultStatus.entries.filter {
			it.category in TicketRepository.MOVED_ALONG_CATEGORIES
		}.map { it.wire }
		assertEquals(
			listOf("in_progress", "in_review", "done", "canceled"),
			moved,
			"the statuses this asks about are the four the category mapping calls moved",
		)

		for (status in moved) {
			val id = ticketIn("backlog")
			service.patch(owner, id, TicketPatch(status = status))
			assertTrue(tickets.anyMovedAlong(), "$status is work that has moved")
		}
	}
}

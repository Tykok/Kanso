package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.SyncState
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `V38`: a write that only moves the mirror's bookkeeping is not an edit, so it must not move
 * `updated_at` and must not move a row's place in "récemment mis à jour".
 *
 * **Not `@Transactional`, and that is the whole reason this file is shaped the way it is.**
 * The trigger stamps `now()`, which is the *transaction's* timestamp — `V9` wrote that down
 * about `doc_pages` and it applies here — so a seed and an edit sharing one transaction get
 * the same stamp, and every assertion below would pass against a trigger that does nothing
 * at all. Each step commits, through [tx], so the timestamps are genuinely ordered.
 *
 * The price is that rows survive the test, which is why every ticket is filed into a team of
 * its own and read back through [TicketScope] on that team: the suite shares one database,
 * and "these rows in this order" has to hold without assuming this class is alone in it.
 */
class UpdatedAtIsAnEditTest : PostgresTest() {

	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var query: TicketQueryRepository
	@Autowired lateinit var service: TicketService
	@Autowired lateinit var tx: TransactionTemplate

	private fun <T> committed(block: () -> T): T = tx.execute { block() }!!

	/**
	 * `ADMIN` and not `OWNER`, which is not a detail: `users_single_owner` is a unique index
	 * and this class commits, so an owner here would outlive the test and collide with the
	 * next class that wants one. An admin edits any team ([TicketAccess.mayEditTeam] returns
	 * on `canConfigureInstance`), which is all this needs from a seat.
	 */
	private val admin: User by lazy {
		committed {
			users.createLocalUser(
				email = "edit-${UUID.randomUUID()}@kanso.test",
				displayName = "Edit admin",
				passwordHash = "not-a-real-hash",
				role = InstanceRole.ADMIN,
			)
		}
	}

	private fun freshTeam(): UUID = committed {
		teams.insert(
			name = "Edit ${UUID.randomUUID()}",
			key = "E${UUID.randomUUID().toString().take(4).uppercase()}",
			parentTeamId = null,
		).id
	}

	private fun ticket(teamId: UUID, title: String): UUID = committed {
		service.create(
			actor = admin,
			teamId = teamId,
			title = title,
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id
	}

	private fun stampOf(id: UUID): OffsetDateTime = committed { tickets.findById(id)!!.updatedAt }

	/** The screen's own query, on one team, titles in the order the list would draw them. */
	private fun listedTitles(teamId: UUID): List<String> = committed {
		query.matching(
			scope = TicketScope(teamIds = listOf(teamId)),
			filters = TicketFilters(),
			limit = 50,
		).map { it.title }
	}

	@Test
	fun `marking the sync state does not move updated_at`() {
		val id = ticket(freshTeam(), "Untouched")
		val before = stampOf(id)

		committed { tickets.markSyncState(id, SyncState.DISABLED) }

		assertEquals(before, stampOf(id), "a bookkeeping write claimed to be an edit")
	}

	/**
	 * `markSynced` writes all four bookkeeping columns at once, `notion_page_id` included,
	 * and is the one the mirror actually runs when it is switched on. Asserted separately
	 * from the `sync_state` case because a trigger that ignored only `sync_state` would pass
	 * that one and fail this.
	 */
	@Test
	fun `recording a successful push does not move updated_at`() {
		val id = ticket(freshTeam(), "Pushed")
		val before = stampOf(id)

		committed { tickets.markSynced(id, "notion-page-${UUID.randomUUID()}", OffsetDateTime.now()) }
		committed { tickets.recordNotionEdit(id, OffsetDateTime.now()) }

		assertEquals(before, stampOf(id), "the mirror's own push claimed to be an edit")
	}

	/**
	 * The other direction, and the test that stops all of this from being satisfied by a
	 * trigger that simply never stamps. Without it, emptying `V38`'s function body would
	 * leave the rest of the class green.
	 */
	@Test
	fun `a real edit still moves updated_at`() {
		val id = ticket(freshTeam(), "Renamed")
		val before = stampOf(id)

		committed { service.patch(admin, id, TicketPatch(title = "Renamed by a person")) }

		assertTrue(stampOf(id).isAfter(before), "a rename did not move updated_at")
	}

	/**
	 * A description is the edit `activity` cannot see — `recordScalarChanges` logs seven
	 * scalars and this is not one of them — and it is the reason `V38` is a trigger rather
	 * than a `MAX(activity.created_at)` on read. If the list ever does sort on `activity`,
	 * this is the test that says why it may not.
	 */
	@Test
	fun `rewriting the description moves updated_at although nothing logs it`() {
		val id = ticket(freshTeam(), "Described")
		val before = stampOf(id)

		committed { service.patch(admin, id, TicketPatch(description = "Rewritten by a person")) }

		assertTrue(stampOf(id).isAfter(before), "a description rewrite did not move updated_at")
	}

	/**
	 * **The guard KAN-81 is actually about.** The assertions above are about a column; this
	 * one is about the screen, and it is the one that goes red when the trigger is put back
	 * the way `V2` had it.
	 *
	 * Three tickets filed in three transactions, so their stamps are genuinely ordered, then
	 * a bookkeeping sweep over all three — which is what `POST /api/admin/notion/reconcile`
	 * does, and what an outbound drain does 500 ms after anybody files anything. Under `V2`'s
	 * trigger all three stamps land in the same millisecond and the list comes back in the
	 * order the *queue* happened to hand the jobs over, which is measured in `V38`'s header.
	 */
	@Test
	fun `a bookkeeping sweep does not reorder the list`() {
		val team = freshTeam()
		val first = ticket(team, "Filed first")
		val second = ticket(team, "Filed second")
		val third = ticket(team, "Filed third")

		val before = listedTitles(team)
		assertEquals(listOf("Filed third", "Filed second", "Filed first"), before, "seed order")

		// Deliberately not the order they were created in: the queue makes no such promise.
		committed { tickets.markSyncState(second, SyncState.DISABLED) }
		committed { tickets.markSyncState(third, SyncState.DISABLED) }
		committed { tickets.markSyncState(first, SyncState.DISABLED) }

		assertEquals(before, listedTitles(team), "a bookkeeping sweep moved rows in the list")
	}

	/**
	 * `teams.ticket_counter` is the same bug one table over: it is incremented every time
	 * somebody files a ticket, so under `V2`'s trigger creating a ticket counted as editing
	 * its team. `NotionPoller.kansoWins` reads `teams.updated_at` too, so this is not
	 * cosmetic — a busy team's Notion page could not be edited by hand at all, because the
	 * next ticket filed anywhere in the team would win the conflict and push the edit away.
	 */
	@Test
	fun `filing a ticket does not count as editing its team`() {
		val team = freshTeam()
		val before = committed { teams.findById(team)!!.updatedAt }

		ticket(team, "Files against the counter")

		assertEquals(before, committed { teams.findById(team)!!.updatedAt }, "filing edited the team")
	}
}

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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `V38`: a write that only moves the mirror's bookkeeping is not an edit, so it must not move
 * `updated_at` and must not move a row's place in "récemment mis à jour".
 *
 * **The seeded rows are backdated by raw `INSERT`, and that is the whole shape of this file.**
 * Two constraints meet here and only one arrangement satisfies both.
 *
 * `now()` is the *transaction's* timestamp, not the statement's — `V9` wrote that down about
 * `doc_pages` — so a row inserted and then edited inside one transaction gets the same stamp
 * twice, and an assertion that "the stamp moved" cannot tell `V38` from a trigger that does
 * nothing at all. The obvious fix is to commit between the steps, and it is wrong: the
 * importer suite asserts *instance-wide* row counts of zero (`ImportFallbackTest`: "not a row
 * of any kind, teams included"), so a class that leaves rows behind fails five other classes
 * that have nothing to do with it.
 *
 * So the stamps are chosen instead of raced. The trigger is `BEFORE UPDATE` only, so an
 * `INSERT` may write `updated_at` freely; every seed lands at a known point in the past, and
 * `now()` is then unambiguously "moved". Nothing commits, everything rolls back, and the
 * assertions are exact equality rather than a tolerance.
 *
 * Note that a row cannot be backdated by `UPDATE` — the stamp is excluded from the trigger's
 * comparison, so such a write is bookkeeping by definition and `V38` pins it back. That is
 * deliberate, and it is why the seed has to be an insert.
 */
@Transactional
class UpdatedAtIsAnEditTest : PostgresTest() {

	@Autowired lateinit var tickets: TicketRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var query: TicketQueryRepository
	@Autowired lateinit var service: TicketService
	@Autowired lateinit var jdbc: JdbcClient

	/**
	 * `ADMIN` rather than `OWNER` even though this rolls back: `users_single_owner` is a
	 * unique index, and a second owner inside a transaction that a sibling class is also
	 * running would collide before either rolled back. An admin edits any team
	 * (`TicketAccess.mayEditTeam` returns on `canConfigureInstance`), which is all this
	 * needs from a seat.
	 */
	private val admin: User by lazy {
		users.createLocalUser(
			email = "edit-${UUID.randomUUID()}@kanso.test",
			displayName = "Edit admin",
			passwordHash = "not-a-real-hash",
			role = InstanceRole.ADMIN,
		)
	}

	/** A team whose own stamp sits [daysAgo] in the past. */
	private fun team(daysAgo: Long): UUID {
		val id = UUID.randomUUID()
		jdbc.sql(
			"""
			INSERT INTO teams (id, name, key, created_at, updated_at)
			VALUES (:id, :name, :key, now() - make_interval(days => :days), now() - make_interval(days => :days))
			""".trimIndent()
		)
			.param("id", id)
			.param("name", "Edit ${UUID.randomUUID()}")
			.param("key", "E${UUID.randomUUID().toString().take(4).uppercase()}")
			.param("days", daysAgo.toInt())
			.update()
		return id
	}

	/** A ticket whose stamp sits [daysAgo] in the past. [number] is chosen, never allocated. */
	private fun ticket(teamId: UUID, title: String, number: Int, daysAgo: Long): UUID {
		val id = UUID.randomUUID()
		jdbc.sql(
			"""
			INSERT INTO tickets (id, team_id, number, title, status, created_at, updated_at)
			VALUES (:id, :team, :number, :title, 'todo',
			        now() - make_interval(days => :days), now() - make_interval(days => :days))
			""".trimIndent()
		)
			.param("id", id)
			.param("team", teamId)
			.param("number", number)
			.param("title", title)
			.param("days", daysAgo.toInt())
			.update()
		return id
	}

	private fun stampOf(id: UUID): OffsetDateTime = tickets.findById(id)!!.updatedAt

	/** The screen's own query, on one team, titles in the order the list would draw them. */
	private fun listedTitles(teamId: UUID): List<String> = query.matching(
		scope = TicketScope(teamIds = listOf(teamId)),
		filters = TicketFilters(),
		limit = 50,
	).map { it.title }

	@Test
	fun `marking the sync state does not move updated_at`() {
		val id = ticket(team(30), "Untouched", number = 1, daysAgo = 7)
		val before = stampOf(id)

		tickets.markSyncState(id, SyncState.DISABLED)

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
		val id = ticket(team(30), "Pushed", number = 1, daysAgo = 7)
		val before = stampOf(id)

		tickets.markSynced(id, "notion-page-${UUID.randomUUID()}", OffsetDateTime.now())
		tickets.recordNotionEdit(id, OffsetDateTime.now())

		assertEquals(before, stampOf(id), "the mirror's own push claimed to be an edit")
	}

	/**
	 * The other direction, and the test that stops all of this from being satisfied by a
	 * trigger that simply never stamps. Without it, emptying `V38`'s function body would
	 * leave the rest of the class green.
	 */
	@Test
	fun `a real edit still moves updated_at`() {
		val id = ticket(team(30), "Renamed", number = 1, daysAgo = 7)
		val before = stampOf(id)

		service.patch(admin, id, TicketPatch(title = "Renamed by a person"))

		assertTrue(stampOf(id).isAfter(before), "a rename did not move updated_at")
	}

	/**
	 * A description is the edit `activity` cannot see — `TicketService.recordScalarChanges`
	 * logs seven scalars and this is not one of them — and it is the reason `V38` is a
	 * trigger rather than a `MAX(activity.created_at)` read. If the list is ever tempted onto
	 * `activity`, this is the test that says why it may not go.
	 */
	@Test
	fun `rewriting the description moves updated_at although nothing logs it`() {
		val id = ticket(team(30), "Described", number = 1, daysAgo = 7)
		val before = stampOf(id)

		service.patch(admin, id, TicketPatch(description = "Rewritten by a person"))

		assertTrue(stampOf(id).isAfter(before), "a description rewrite did not move updated_at")
	}

	/**
	 * **The guard KAN-81 is actually about.** The assertions above are about a column; this
	 * one is about the screen, and it is the one that goes red when the trigger is put back
	 * the way `V2` had it.
	 *
	 * The numbers ascend while the stamps descend, which is what makes the failure legible
	 * rather than lucky. Under `V2`'s trigger the sweep gives all three rows the *same* stamp
	 * — one transaction, one `now()` — so `ORDER BY updated_at DESC` ties and the list falls
	 * through to `number DESC`, returning the three in exactly reverse order. On the running
	 * stack, where each drain is its own transaction, the same collapse instead leaves the
	 * stamps a millisecond apart and the list comes back in the order the *queue* handed the
	 * jobs over; `V38`'s header measures that. Either way the row nobody touched has moved.
	 */
	@Test
	fun `a bookkeeping sweep does not reorder the list`() {
		val team = team(30)
		val newest = ticket(team, "Edited yesterday", number = 101, daysAgo = 1)
		val middle = ticket(team, "Edited last week", number = 102, daysAgo = 7)
		val oldest = ticket(team, "Edited last month", number = 103, daysAgo = 28)

		val before = listedTitles(team)
		assertEquals(
			listOf("Edited yesterday", "Edited last week", "Edited last month"),
			before,
			"the seed is not in the order the list would draw it",
		)

		// Deliberately not the order they were filed in: the queue makes no such promise.
		tickets.markSyncState(middle, SyncState.DISABLED)
		tickets.markSyncState(oldest, SyncState.DISABLED)
		tickets.markSyncState(newest, SyncState.DISABLED)

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
		val team = team(30)
		val before = stampOfTeam(team)

		service.create(
			actor = admin,
			teamId = team,
			title = "Files against the counter",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		)

		assertEquals(before, stampOfTeam(team), "filing a ticket edited its team")
	}

	private fun stampOfTeam(id: UUID): OffsetDateTime =
		jdbc.sql("SELECT updated_at FROM teams WHERE id = :id")
			.param("id", id)
			.query(OffsetDateTime::class.java)
			.single()
}

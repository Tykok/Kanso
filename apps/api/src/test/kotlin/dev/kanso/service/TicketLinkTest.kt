package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.db.TicketLinks
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.KansoInstant
import dev.kanso.domain.TicketLinkType
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.DependencyRepository
import dev.kanso.repo.TicketLinkRepository
import dev.kanso.repo.TicketRepository
import dev.kanso.repo.UserRepository
import dev.kanso.schedule.Edge
import org.springframework.beans.factory.annotation.Autowired
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ticket_links` carries three kinds of edge and the scheduler is entitled to exactly
 * one of them.
 *
 * This is the failure mode worth a test file of its own, because it is silent. A
 * `relates` edge read as a dependency does not throw and does not draw anything obviously
 * wrong: it joins two chains that have nothing to do with each other, and every date the
 * critical path derives from the merged component shifts by a few days. Nobody notices a
 * plan that is wrong by three days — they act on it.
 *
 * The guard is not a `filter` in the scheduler. It is the boundary: `DependencyRepository`
 * is the only thing in Kanso that returns a `schedule.Edge`, and it reads
 * `type = 'blocks'` in all four of its reads. These tests hold that boundary from the
 * outside — they draw non-blocking edges and then ask the scheduler's own questions.
 */
@Transactional
class TicketLinkTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var deps: DependencyRepository
	@Autowired lateinit var links: TicketLinkRepository
	@Autowired lateinit var schedule: ScheduleService
	@Autowired lateinit var repo: TicketRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "links-${UUID.randomUUID()}@kanso.test",
			displayName = "Links admin",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private val team by lazy {
		teams.create(admin, "Links", "L${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun day(d: Int) = KansoInstant(
		OffsetDateTime.of(2026, 8, d, 0, 0, 0, 0, ZoneOffset.UTC),
		false,
	)

	private fun ticket(title: String, start: Int? = null, due: Int? = null): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = title,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = start?.let(::day),
		due = due?.let(::day),
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).ticket.id

	// --- the scheduler reads only `blocks` ----------------------------------

	@Test
	fun `a relates edge is not an edge of the schedule`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 5, due = 8)
		links.link(a, b, TicketLinkType.RELATES)

		assertEquals(
			emptyList<Edge>(),
			deps.edgesTouching(listOf(a, b)),
			"a relates row reaching the graph would order two tickets nobody ordered",
		)
	}

	@Test
	fun `a duplicates edge is not an edge of the schedule`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 5, due = 8)
		links.link(a, b, TicketLinkType.DUPLICATES)

		assertEquals(emptyList<Edge>(), deps.edgesTouching(listOf(a, b)))
	}

	@Test
	fun `only the blocking edge survives between a pair that carries two kinds`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 12, due = 15)
		deps.insert(a, b)
		links.link(a, b, TicketLinkType.RELATES)
		links.link(a, b, TicketLinkType.DUPLICATES)

		// Three rows in the table, one edge in the graph. If the type were part of the
		// primary key's absence rather than its content, the two later links would have
		// overwritten the dependency instead of joining it.
		assertEquals(listOf(Edge(a, b)), deps.edgesTouching(listOf(a, b)))
		assertEquals(3, links.of(a).size, "the ticket page still sees all three")
	}

	@Test
	fun `a relates edge does not merge two unrelated chains into one component`() {
		val a = ticket("A", start = 1, due = 3)
		val b = ticket("B", start = 4, due = 6)
		val c = ticket("C", start = 1, due = 3)
		val d = ticket("D", start = 4, due = 6)
		deps.insert(a, b)
		deps.insert(c, d)
		links.link(b, c, TicketLinkType.RELATES)

		// The component is the unit the critical path is computed over. Walking a relates
		// edge here would anchor A..B's slack on the far end of C..D — a chain it has no
		// dependency on — and every date in both would move.
		assertEquals(
			setOf(a, b),
			deps.componentIds(listOf(a)).toSet(),
			"the walk crosses arrow direction, never edge type",
		)
		assertEquals(setOf(c, d), deps.componentIds(listOf(c)).toSet())
	}

	@Test
	fun `a non-blocking edge moves no dates`() {
		val a = ticket("A", start = 1, due = 10)
		val b = ticket("B", start = 5, due = 8)
		links.link(a, b, TicketLinkType.RELATES)
		links.link(a, b, TicketLinkType.DUPLICATES)

		// B overlaps A. Were either link a dependency, the cascade would shove B past
		// A's end, exactly as `linking applies the cascade immediately` proves it does
		// for a real one.
		assertEquals(emptyList<UUID>(), schedule.cascadeFrom(a), "nothing here constrains anything")
		assertEquals(day(5).at, repo.findById(b)!!.start!!.at, "B has not moved")
	}

	@Test
	fun `the cycle refusal does not see a loop that is made of relates edges`() {
		val a = ticket("A")
		val b = ticket("B")
		links.link(b, a, TicketLinkType.RELATES)
		links.link(a, b, TicketLinkType.DUPLICATES)

		// `linkRefusal` asks "is the would-be predecessor already reachable forward from
		// the would-be successor". If that walk followed every type, these two tickets
		// being *related* would be enough to refuse a legitimate dependency between
		// them — a 409 on a chain the person cannot see and did not draw.
		assertNull(deps.pathBetween(b, a))
		assertEquals(emptyList<UUID>(), schedule.link(admin, a, b), "the dependency is allowed")
		assertTrue(deps.exists(a, b))
	}

	// --- symmetric storage ---------------------------------------------------

	@Test
	fun `a relates edge is found from both ends`() {
		val a = ticket("A")
		val b = ticket("B")
		links.link(a, b, TicketLinkType.RELATES)

		assertEquals(listOf(b), links.of(a).map { it.otherId }, "found from the end that drew it")
		assertEquals(listOf(a), links.of(b).map { it.otherId }, "and from the end that did not")
	}

	@Test
	fun `relating two tickets the other way round is the same one fact`() {
		val a = ticket("A")
		val b = ticket("B")

		assertTrue(links.link(a, b, TicketLinkType.RELATES))
		assertFalse(links.link(b, a, TicketLinkType.RELATES), "B relates A is already stored")
		assertEquals(1, links.of(a).size, "one row, not two rows saying the same thing")
		assertTrue(links.unlink(b, a, TicketLinkType.RELATES), "and it comes undone from either end")
		assertEquals(emptyList<dev.kanso.repo.TicketLink>(), links.of(a))
	}

	@Test
	fun `a directed edge keeps the direction it was drawn in`() {
		val a = ticket("A")
		val b = ticket("B")
		links.link(a, b, TicketLinkType.DUPLICATES)

		assertEquals(listOf(true), links.of(a).map { it.outgoing }, "A duplicates B")
		assertEquals(listOf(false), links.of(b).map { it.outgoing }, "B is duplicated by A")
		assertFalse(
			links.exists(b, a, TicketLinkType.DUPLICATES),
			"which of the two is the survivor is the whole content of the link",
		)
	}

	@Test
	fun `the database refuses a relates row stored the wrong way round`() {
		val a = ticket("A")
		val b = ticket("B")
		// Ordered as *Postgres* orders uuids, which is not how `UUID.compareTo` does —
		// see `TicketLinkRepository.canonical`. Getting this wrong here makes the test
		// insert a perfectly legal row about half the time and pass for the wrong reason.
		val (smaller, larger) = if (a.toString() < b.toString()) a to b else b to a

		// Bypassing `link`, which is the whole point of the CHECK: the canonical order is
		// an invariant of the table and not a courtesy of one Kotlin method.
		assertFailsWith<ExposedSQLException> {
			TicketLinks.insert {
				it[fromTicketId] = larger
				it[toTicketId] = smaller
				it[type] = TicketLinkType.RELATES.wire
				it[createdAt] = OffsetDateTime.now()
			}
		}
	}

	// V7's unnamed self-link CHECK survived the rename as
	// `ticket_links_not_self_chk` and is type-agnostic, so it now says something about
	// all three kinds — including the one `TriageService` had been refusing by hand
	// ("A ticket cannot duplicate itself"). One test per value rather than a loop: the
	// first violation aborts the transaction, so a second statement in it would fail
	// for the wrong reason and prove nothing.

	@Test
	fun `a ticket cannot block itself`() {
		val a = ticket("A")
		assertFailsWith<ExposedSQLException> { links.link(a, a, TicketLinkType.BLOCKS) }
	}

	@Test
	fun `a ticket cannot relate to itself`() {
		val a = ticket("A")
		assertFailsWith<ExposedSQLException> { links.link(a, a, TicketLinkType.RELATES) }
	}

	@Test
	fun `a ticket cannot duplicate itself`() {
		val a = ticket("A")
		assertFailsWith<ExposedSQLException> { links.link(a, a, TicketLinkType.DUPLICATES) }
	}
}

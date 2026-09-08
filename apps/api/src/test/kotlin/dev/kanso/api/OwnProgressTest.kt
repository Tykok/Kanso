package dev.kanso.api

import dev.kanso.MockMvcTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.CycleService
import dev.kanso.service.CycleState
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule screen 40 has about who may look: **its owner, whatever their seat.**
 *
 * This has to arrive through the filter chain to mean anything. `ProgressTest` calls the
 * service directly and so has already walked past `SecurityConfig`, `ReadOnlySeat` and
 * every interceptor between them — and a read-only seat is exactly the kind of caller that
 * gets refused by something nobody remembers is in the way. `ReadOnlySeatLeakTest` is the
 * house precedent for driving a viewer through the whole stack; this is the other
 * direction of the same question, which that sweep cannot ask: it enumerates the writes a
 * viewer must *not* reach, and says nothing about the one read they must.
 *
 * The assertion is deliberately not just the status code. A 200 carrying an empty body
 * would satisfy a test of the code alone while being the failure that matters: a person
 * demoted to a viewer this morning opening their own page and finding it blank.
 *
 * The other-person and team views are a separate ticket and are not tested here, because
 * they do not exist — there is no route to reach a subject other than the caller, which is
 * the narrowest thing this endpoint could be and the reason it needs no rule of its own.
 */
@Transactional
class OwnProgressTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "own-progress-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	/**
	 * The seat under test, and the seat that has to build the fixture.
	 *
	 * A viewer cannot create a team, a cycle or a ticket — that is the whole of the seat —
	 * so the work they delivered is created by an admin and assigned to them. Which is also
	 * what the real case looks like: somebody who was a member for two quarters and is a
	 * viewer today still has a history and still has a plate.
	 */
	private val viewer: User by lazy { person("A demoted member", InstanceRole.VIEWER) }
	private val admin: User by lazy { person("Own progress admin", InstanceRole.ADMIN) }

	private val team by lazy {
		teams.create(admin, "Reading", "O${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun ticket(estimate: Int, status: String): UUID = tickets.create(
		actor = admin,
		teamId = team.id,
		title = "the viewer's $estimate",
		description = null,
		status = status,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = listOf(viewer.id),
		docIds = emptyList(),
		estimate = estimate,
	).ticket.id

	@Test
	fun `a read-only seat reads its own progress, and gets numbers rather than an empty page`() {
		// Two closed working weeks at five points each: one point a working day, measured,
		// which is the shape that makes every field below a real value rather than a null.
		listOf(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)).forEachIndexed { at, monday ->
			val cycle = cycles.create(admin, team.id, 60 + at, monday, monday.plusDays(4), CycleState.CLOSED)
			val done = ticket(5, "done")
			Tickets.update({ Tickets.id eq done }) {
				it[completedAt] = monday.plusDays(1).atTime(10, 0).atOffset(ZoneOffset.UTC)
			}
			cycles.addTickets(admin, cycle.id, listOf(done))
		}
		ticket(8, "in_progress")

		mvc.get("/api/me/progress?teamId=${team.id}") {
			header(DevAuthenticationFilter.HEADER, viewer.email)
		}.andExpect {
			status { isOk() }
			// Their own page is about them, and says so: a heading that could not name its
			// subject without a second request is the shape the other-person view would break.
			jsonPath("$.person.id") { value(viewer.id.toString()) }
			// The velocity in force, and — the half of the feature that is not a number —
			// which of the two it is.
			jsonPath("$.velocity.source") { value("measured") }
			jsonPath("$.velocity.perWorkingDay") { value(1.0) }
			jsonPath("$.velocity.measuredCycles") { value(2) }
			jsonPath("$.delivered.length()") { value(2) }
			jsonPath("$.delivered[0].points") { value(5.0) }
			jsonPath("$.delivered[0].countedTowardsVelocity") { value(true) }
			// The plate, and the sentence the page exists to print: eight points at a point a
			// day is eight days of work being carried.
			jsonPath("$.load.load.points") { value(8) }
			jsonPath("$.load.workingDays") { value(8.0) }
			jsonPath("$.load.byStatus.in_progress.points") { value(8) }
		}
	}

	/**
	 * The empty instance, through the chain.
	 *
	 * A team with no closed cycle and a person with nothing assigned is what every Kanso
	 * looks like on its first afternoon, and it is the shape most likely to answer with a
	 * `NaN`, an `Infinity` or a 500 — none of which a status assertion alone would catch,
	 * because `Infinity` is not valid JSON and Jackson will happily write it anyway.
	 */
	@Test
	fun `a person with no history and an empty plate gets absences, not nulls-as-zeroes`() {
		mvc.get("/api/me/progress?teamId=${team.id}") {
			header(DevAuthenticationFilter.HEADER, viewer.email)
		}.andExpect {
			status { isOk() }
			jsonPath("$.velocity.source") { value("none") }
			// Absent from the JSON entirely rather than sent as 0: the API omits nulls, and 0
			// would be a claim that this person delivers nothing.
			jsonPath("$.velocity.perWorkingDay") { doesNotExist() }
			jsonPath("$.load.workingDays") { doesNotExist() }
			jsonPath("$.delivered.length()") { value(0) }
			jsonPath("$.load.load.points") { value(0) }
			jsonPath("$.load.byProject.length()") { value(0) }
			// Empty, and the page still has a shape to draw — `KAN-90` moved the legend to
			// the client. It used to be the full list of open statuses with their zeros,
			// which the server can no longer name: "every open status" has a per-team
			// answer now. The client draws it from the vocabulary it asked for, which for
			// one team is that team's own ordered list, and fills the zeros itself.
			jsonPath("$.load.byStatus.length()") { value(0) }
		}
	}

	@Test
	fun `the route answers for the caller and has no way to be pointed at anybody else`() {
		val other = person("Somebody else", InstanceRole.MEMBER)

		// `?teamId=` is the only parameter, and a subject id passed alongside it is ignored
		// rather than honoured. Asserted because the shape underneath *can* answer for
		// another subject — that is the seam the admin view will use — and the only thing
		// keeping this route narrow is that it never passes one.
		mvc.get("/api/me/progress?teamId=${team.id}&userId=${other.id}&personId=${other.id}") {
			header(DevAuthenticationFilter.HEADER, viewer.email)
		}.andExpect {
			status { isOk() }
			jsonPath("$.person.id") { value(viewer.id.toString()) }
		}
	}

	/**
	 * KAN-66, and it is one test for two routes on purpose.
	 *
	 * Both answered 200 with a wholly empty body for a `teamId` naming no team. Split into
	 * two tests they could each be fixed alone, which the ticket judged worse than the bug —
	 * two neighbouring routes refusing one mistake in two ways, with nothing saying why. One
	 * test is the cheapest way to make the next person to change either of them break this.
	 *
	 * The second half is what stops the cure being worse: a team that *does* exist and has
	 * nothing to report must still answer 200. The empty body was indistinguishable between
	 * those two cases, and turning both into errors would have deleted a real answer.
	 */
	@Test
	fun `a teamId naming no team is refused, on both routes, while an empty team still answers`() {
		val nobodys = UUID.randomUUID()

		for (route in listOf("/api/me/progress", "/api/me/velocity")) {
			val response = mvc.get("$route?teamId=$nobodys") {
				header(DevAuthenticationFilter.HEADER, viewer.email)
			}.andReturn().response

			assertEquals(
				404,
				response.status,
				"$route answered ${response.status} for a team that does not exist, which is an" +
					" answer about a calendar nobody has",
			)
		}

		// The real team, with no closed cycle and nothing assigned. Still an answer.
		mvc.get("/api/me/velocity?teamId=${team.id}") {
			header(DevAuthenticationFilter.HEADER, viewer.email)
		}.andExpect {
			status { isOk() }
			jsonPath("$.source") { value("none") }
		}
	}

	@Test
	fun `the team is required, because a pace measured against another team's fortnights is another number`() {
		val response = mvc.get("/api/me/progress") {
			header(DevAuthenticationFilter.HEADER, viewer.email)
		}.andReturn().response

		assertEquals(
			400,
			response.status,
			"defaulting to some team would draw a chart against the wrong calendar and say nothing" +
				" about having chosen it",
		)
	}
}

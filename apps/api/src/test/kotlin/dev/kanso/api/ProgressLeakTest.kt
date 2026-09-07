package dev.kanso.api

import dev.kanso.MockMvcTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.db.Tickets
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The permission rule of screen 41, driven through the whole stack.
 *
 * `PublicLeakTest` is the house precedent and this is its private-surface twin. That file
 * exists because the public roadmap answers strangers, which makes a bad projection a
 * disclosure; this one exists because these two routes answer *authenticated* readers about
 * somebody who is not them, which is the only other place in Kanso where the wrong reader
 * getting a right-looking answer is the failure.
 *
 * Two things are asserted, and the second is the one a lazier version of this file would
 * skip.
 *
 * **The numbers, never merely the status code.** A 200 carrying an empty body would pass
 * every assertion a status check can make while being the exact failure that matters: the
 * page renders, the reader believes it, and the figures are somebody else's or nobody's.
 * `OwnProgressTest` makes the same argument for the personal route, and KAN-66 is a ticket
 * about that failure mode having shipped once already.
 *
 * **The refusals, through the filter chain.** Calling `ProgressAccess` directly would walk
 * past `SecurityConfig`, `ReadOnlySeat` and every interceptor between them — and the whole
 * point of the ticket is that a rule the front-end applies is not a rule. So every branch
 * below arrives as an HTTP request carrying an identity, which is the only version of this
 * question that a hidden menu item cannot answer.
 *
 * The fixture is a two-level tree because the ancestry clause is the part of the rule most
 * likely to be got wrong: `lead` administers Product and reads a subject whose work is in
 * Product / Mobile, and `unrelated` is a second root that the same title must not reach.
 */
@Transactional
class ProgressLeakTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var cycles: CycleService

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "progress-leak-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	/** The instance axis, and the seat that builds the fixture. */
	private val owner: User by lazy { person("Leak owner", InstanceRole.OWNER) }

	/** The team axis: titled `admin` of Product, and of nothing else. */
	private val lead: User by lazy { person("Product lead", InstanceRole.MEMBER) }

	/** Neither axis. A member of the very sub-team the figures are about. */
	private val plain: User by lazy { person("A teammate", InstanceRole.MEMBER) }

	private val subject: User by lazy { person("The measured one", InstanceRole.MEMBER) }

	/** Guard-rail one: a demoted seat still reads itself, through the wide route too. */
	private val viewer: User by lazy { person("A demoted member", InstanceRole.VIEWER) }

	private fun key() = "P${UUID.randomUUID().toString().take(4).uppercase()}"

	private val product by lazy { teams.create(owner, "Product", key(), null) }

	private val mobile by lazy {
		val team = teams.create(owner, "Mobile", key(), product.id)
		teams.addMember(owner, product.id, lead.id, MemberRole.ADMIN)
		teams.addMember(owner, team.id, plain.id, MemberRole.MEMBER)
		teams.addMember(owner, team.id, subject.id, MemberRole.MEMBER)
		team
	}

	/** A second root, so "administers Product" can be shown not to mean "administers everything". */
	private val unrelated by lazy { teams.create(owner, "Platform", key(), null) }

	private fun ticket(teamId: UUID, estimate: Int, status: DefaultStatus, assignees: List<UUID>): UUID =
		tickets.create(
			actor = owner,
			teamId = teamId,
			title = "a $estimate",
			description = null,
			status = status,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = assignees,
			docIds = emptyList(),
			estimate = estimate,
		).ticket.id

	/**
	 * Two closed working weeks in Mobile, and a deliberate gap between the two answers.
	 *
	 * The subject delivers five points in each: five working days, one point a day,
	 * measured — the shape that makes every field a real value rather than a null. The
	 * second cycle also delivers a three nobody was assigned, which is invisible to the
	 * personal read (there is no person to credit) and counted by the team's. So the
	 * person's bars are 5 and 5 while the team's are 5 and 8, and a team view that had
	 * quietly been implemented as "the sum of its members" would fail on the second bar
	 * rather than passing by coincidence.
	 */
	private fun history() {
		listOf(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)).forEachIndexed { at, monday ->
			val cycle = cycles.create(owner, mobile.id, 70 + at, monday, monday.plusDays(4), CycleState.CLOSED)
			val delivered = mutableListOf(ticket(mobile.id, 5, DefaultStatus.DONE, listOf(subject.id)))
			if (at == 1) delivered += ticket(mobile.id, 3, DefaultStatus.DONE, emptyList())
			delivered.forEach { id ->
				Tickets.update({ Tickets.id eq id }) {
					it[completedAt] = monday.plusDays(1).atTime(10, 0).atOffset(ZoneOffset.UTC)
				}
			}
			cycles.addTickets(owner, cycle.id, delivered)
		}
		// The open plate, so `load` is a number and not a zero.
		ticket(mobile.id, 8, DefaultStatus.IN_PROGRESS, listOf(subject.id))
	}

	private fun progressOf(actor: User, of: User, teamId: UUID) =
		mvc.get("/api/people/${of.id}/progress?teamId=$teamId") {
			header(DevAuthenticationFilter.HEADER, actor.email)
		}

	/**
	 * The subject's figures, asserted wherever they are read from.
	 *
	 * One helper rather than the same eight `jsonPath`s written out three times, because the
	 * claim being made is that the *same* answer reaches three different readers — and three
	 * copies of it would be free to drift into three different claims.
	 */
	private fun ResultActionsDsl.assertSubjectsNumbers() = andExpect {
		status { isOk() }
		jsonPath("$.person.id") { value(subject.id.toString()) }
		jsonPath("$.velocity.source") { value("measured") }
		jsonPath("$.velocity.perWorkingDay") { value(1.0) }
		jsonPath("$.velocity.measuredCycles") { value(2) }
		jsonPath("$.delivered.length()") { value(2) }
		jsonPath("$.delivered[0].points") { value(5.0) }
		jsonPath("$.delivered[1].points") { value(5.0) }
		jsonPath("$.delivered[0].countedTowardsVelocity") { value(true) }
		jsonPath("$.load.load.points") { value(8) }
		jsonPath("$.load.workingDays") { value(8.0) }
		jsonPath("$.load.byStatus.in_progress.points") { value(8) }
	}

	@Test
	fun `an instance owner reads another person's figures, and gets the figures`() {
		history()
		progressOf(owner, subject, mobile.id).assertSubjectsNumbers()
	}

	@Test
	fun `a team administrator reads a member of a sub-team, and only of a sub-team`() {
		history()
		// Administration runs downwards: titled on Product, reading work in Product / Mobile.
		progressOf(lead, subject, mobile.id).assertSubjectsNumbers()

		// And it stops at the chain. A second root is not below Product, so the same title
		// says nothing about it — this is the assertion that fails if the ancestor walk is
		// ever replaced by "any team I am titled on".
		assertEquals(
			403,
			progressOf(lead, subject, unrelated.id).andReturn().response.status,
			"a title on Product must not reach a team Product is not above",
		)
	}

	@Test
	fun `a plain member is refused everybody else, and still reads themselves`() {
		history()
		assertEquals(
			403,
			progressOf(plain, subject, mobile.id).andReturn().response.status,
			"being in the same sub-team is not a right to read a teammate's productivity",
		)

		// Guard-rail one, through the wide route: the branch that answers a subject reading
		// themselves is the first one asked, so no rule below it can take it away.
		progressOf(plain, plain, mobile.id).andExpect {
			status { isOk() }
			jsonPath("$.person.id") { value(plain.id.toString()) }
		}
	}

	@Test
	fun `a read-only seat reads its own figures through the wide route too`() {
		history()
		progressOf(viewer, viewer, mobile.id).andExpect {
			status { isOk() }
			jsonPath("$.person.id") { value(viewer.id.toString()) }
			// A viewer with no history is the shape most likely to answer a `NaN` or an
			// `Infinity`, neither of which a status assertion would catch.
			jsonPath("$.velocity.source") { value("none") }
			jsonPath("$.load.workingDays") { doesNotExist() }
			jsonPath("$.load.byStatus.todo.tickets") { value(0) }
		}
	}

	@Test
	fun `the team view answers an administrator in aggregates and refuses a member`() {
		history()
		mvc.get("/api/teams/${mobile.id}/progress") {
			header(DevAuthenticationFilter.HEADER, lead.email)
		}.andExpect {
			status { isOk() }
			jsonPath("$.team.id") { value(mobile.id.toString()) }
			// 5 points over five working days, then 8 over five: the mean of the two rates,
			// not the pooled total, which is the rule `VelocityService` states for a person
			// and this number obeys one height up.
			jsonPath("$.pace.perWorkingDay") { value(1.3) }
			jsonPath("$.pace.measuredCycles") { value(2) }
			jsonPath("$.delivered.length()") { value(2) }
			jsonPath("$.delivered[0].points") { value(5.0) }
			// The unassigned three, which the person's own bars cannot see.
			jsonPath("$.delivered[1].points") { value(8.0) }
			jsonPath("$.load.load.points") { value(8) }
		}

		assertEquals(
			403,
			mvc.get("/api/teams/${mobile.id}/progress") {
				header(DevAuthenticationFilter.HEADER, plain.email)
			}.andReturn().response.status,
			"a team's velocity is nobody's own figure, so being in the team grants nothing",
		)
	}

	/**
	 * The ranking, refused at the wire.
	 *
	 * Asserted against the serialised body rather than the DTO fields, for `PublicLeakTest`'s
	 * reason: the shape that matters is the one that reaches a client, and a per-person row
	 * added to a nested response three refactors from now is caught here without anybody
	 * having to remember this file exists. Every name in the fixture is swept for, including
	 * the unassigned work's absence of one.
	 */
	@Test
	fun `a team's figures name no person, so nothing can be sorted into a league table`() {
		history()
		val body = mvc.get("/api/teams/${mobile.id}/progress") {
			header(DevAuthenticationFilter.HEADER, owner.email)
			// The status is asserted before the sweep, and that is not belt-and-braces: a
			// refusal's `ProblemDetail` carries no display name either, so a sweep on its own
			// would go green the moment this reader lost the right to make the request at all.
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString

		for (name in listOf(subject.displayName, plain.displayName, lead.displayName, owner.displayName)) {
			assertFalse(body.contains(name), "the team response names $name: $body")
		}
		for (field in listOf("person", "assignee", "rows", "displayName")) {
			assertFalse(body.contains("\"$field\""), "the team response carries a `$field` field: $body")
		}
	}

	/**
	 * Guard-rail two: the page names its other readers.
	 *
	 * The sharp end of this is that it names Product and *not* Mobile. Both are in the
	 * chain, both are teams the figures are scoped by, and only one of them has somebody
	 * titled administrator on it — so a version of this computed from the tree rather than
	 * from the rule that grants the access would name two teams and be wrong about one.
	 */
	@Test
	fun `the page says who else can read it, and names only teams that have an administrator`() {
		history()
		val body = progressOf(subject, subject, mobile.id).andExpect {
			status { isOk() }
			jsonPath("$.readers.teams.length()") { value(1) }
			jsonPath("$.readers.teams[0].name") { value("Product") }
		}.andReturn().response.contentAsString

		// Swept out of the body rather than indexed by position: `findAll` orders by display
		// name, so an assertion on `instanceAdmins[0]` would be an assertion about the
		// alphabet and would break the day somebody seeds a second admin.
		assertTrue(
			body.contains(owner.displayName),
			"the subject is not told that the instance owner reads this page: $body",
		)
	}

	/** The same sentence on the personal route, which is the only place the subject will look. */
	@Test
	fun `the caller's own page carries its readers too`() {
		history()
		mvc.get("/api/me/progress?teamId=${mobile.id}") {
			header(DevAuthenticationFilter.HEADER, subject.email)
		}.andExpect {
			status { isOk() }
			jsonPath("$.readers.teams[0].name") { value("Product") }
		}
	}
}

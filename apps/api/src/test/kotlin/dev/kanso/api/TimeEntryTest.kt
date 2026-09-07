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
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KAN-26's four rules, driven through the filter chain.
 *
 * `ProgressLeakTest`'s argument, applied to hours: a rule the front-end enforces is not a
 * rule, so every refusal below arrives as an HTTP request carrying an identity rather than as
 * a direct call on `TimeEntryService`. Two of these would pass a status-code-only check while
 * being exactly wrong, so the numbers are asserted too.
 *
 * The rule that is *not* tested here is the one the database owns:
 * `time_entries_one_running_per_person_idx` is what makes two clocks impossible, and a
 * unique index cannot be driven from MockMvc — the service's 409 fires first, which is the
 * whole point of it being belt to the index's braces. That half was proven by hand against a
 * live stack (drop the index, insert the second running row, watch `CREATE UNIQUE INDEX`
 * refuse to come back) and it is written up in the ticket rather than asserted here.
 */
@Transactional
class TimeEntryTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "time-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private val owner: User by lazy { person("An owner", InstanceRole.OWNER) }

	/** Titled `admin` of the team the work is in — `teamsLedBy`'s second branch. */
	private val lead: User by lazy { person("A team lead", InstanceRole.MEMBER) }

	/** Neither the author nor an administrator. A member of the very same team. */
	private val plain: User by lazy { person("A teammate", InstanceRole.MEMBER) }

	/** The person whose hours these are. */
	private val worker: User by lazy { person("The one who worked", InstanceRole.MEMBER) }

	private val viewer: User by lazy { person("A demoted member", InstanceRole.VIEWER) }

	private val team by lazy {
		val created = teams.create(owner, "Agency", "A${UUID.randomUUID().toString().take(4).uppercase()}", null)
		teams.addMember(owner, created.id, lead.id, MemberRole.ADMIN)
		teams.addMember(owner, created.id, plain.id, MemberRole.MEMBER)
		teams.addMember(owner, created.id, worker.id, MemberRole.MEMBER)
		teams.addMember(owner, created.id, viewer.id, MemberRole.MEMBER)
		created
	}

	private val ticket: UUID by lazy {
		tickets.create(
			actor = owner,
			teamId = team.id,
			title = "Client onboarding call",
			description = null,
			status = DefaultStatus.IN_PROGRESS,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
			estimate = null,
		).ticket.id
	}

	// --- the calls -----------------------------------------------------------

	private fun log(actor: User, body: String) = mvc.post("/api/tickets/$ticket/time") {
		header(DevAuthenticationFilter.HEADER, actor.email)
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun startTimer(actor: User, on: UUID = ticket) = mvc.post("/api/tickets/$on/time/start") {
		header(DevAuthenticationFilter.HEADER, actor.email)
		contentType = MediaType.APPLICATION_JSON
		content = "{}"
	}

	private fun readTime(actor: User) = mvc.get("/api/tickets/$ticket/time") {
		header(DevAuthenticationFilter.HEADER, actor.email)
	}

	private fun status(result: org.springframework.test.web.servlet.ResultActionsDsl) =
		result.andReturn().response.status

	private fun idOf(result: org.springframework.test.web.servlet.ResultActionsDsl): String =
		Regex("\"id\":\"([^\"]+)\"").find(result.andReturn().response.contentAsString)!!.groupValues[1]

	// --- rule one: the total is a SUM, and a running clock is not in it ------

	/**
	 * The arithmetic decision of the whole feature, asserted as a number.
	 *
	 * A status-code check would pass on a response that folded `now() - started_at` into the
	 * total, which is the failure that matters: a billable figure that grows every time the
	 * page is refreshed. So the total is read before and after a clock is started, and it is
	 * the *same* number.
	 */
	@Test
	fun `the total sums settled entries and excludes a running clock`() {
		log(worker, """{"minutes":45,"note":"Kick-off","spentOn":"2026-09-03"}""").andExpect {
			status { isCreated() }
			// Absent, not null: a typed entry was never clocked.
			jsonPath("$.startedAt") { doesNotExist() }
			jsonPath("$.minutes") { value(45) }
		}
		log(worker, """{"minutes":30,"spentOn":"2026-09-01"}""").andExpect { status { isCreated() } }

		readTime(worker).andExpect {
			status { isOk() }
			jsonPath("$.totalMinutes") { value(75) }
			jsonPath("$.entries.length()") { value(2) }
			jsonPath("$.runningId") { doesNotExist() }
		}

		startTimer(worker).andExpect {
			status { isCreated() }
			// The running shape: a start instant and no duration at all.
			jsonPath("$.minutes") { doesNotExist() }
			jsonPath("$.startedAt") { exists() }
		}

		readTime(worker).andExpect {
			// Three rows now, and the total has not moved by a minute.
			jsonPath("$.totalMinutes") { value(75) }
			jsonPath("$.entries.length()") { value(3) }
			jsonPath("$.runningId") { exists() }
		}
	}

	/** `runningId` is the caller's own clock, so a colleague's does not fill it. */
	@Test
	fun `another person's clock is visible as a row but is not the caller's runningId`() {
		startTimer(worker).andExpect { status { isCreated() } }
		readTime(plain).andExpect {
			status { isOk() }
			jsonPath("$.entries.length()") { value(1) }
			jsonPath("$.runningId") { doesNotExist() }
		}
	}

	// --- rule two: one clock per person -------------------------------------

	@Test
	fun `a second clock is refused, and the refusal names the ticket holding the first`() {
		startTimer(worker).andExpect { status { isCreated() } }
		val second = startTimer(worker)
		assertEquals(409, status(second), "a person has one pair of hands")
		second.andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("Client onboarding call")) }
		}
	}

	// --- rule three: whose hours are they -----------------------------------

	/**
	 * The branch decided the same way KAN-41 decided who reads whose figures, and reached
	 * through the same primitive — `TicketAccess.teamsLedBy`.
	 */
	@Test
	fun `an entry is its author's to correct, and its team administrator's, and nobody else's`() {
		val entry = idOf(log(worker, """{"minutes":45,"spentOn":"2026-09-03"}"""))

		fun correct(actor: User, minutes: Int) = mvc.patch("/api/time/$entry") {
			header(DevAuthenticationFilter.HEADER, actor.email)
			contentType = MediaType.APPLICATION_JSON
			content = """{"minutes":$minutes}"""
		}

		assertEquals(200, status(correct(worker, 40)), "your own hours are yours to correct")
		assertEquals(200, status(correct(lead, 35)), "a titled administrator of the team may fix it")
		assertEquals(200, status(correct(owner, 30)), "an instance owner reads and writes everything")
		assertEquals(
			403,
			status(correct(plain, 5)),
			"being in the team is not being its administrator — the rule ProgressAccess states",
		)

		// And the refusal actually refused: the last accepted correction is what stands.
		readTime(worker).andExpect { jsonPath("$.totalMinutes") { value(30) } }
	}

	// --- rule four: the seat ------------------------------------------------

	/**
	 * Inherited from `TicketAccess.require` with no code in this feature, which is the point:
	 * the sentence is the house's own.
	 */
	@Test
	fun `a read-only seat logs no hours but still reads them`() {
		log(worker, """{"minutes":45,"spentOn":"2026-09-03"}""").andExpect { status { isCreated() } }

		val refused = log(viewer, """{"minutes":15,"spentOn":"2026-09-03"}""")
		assertEquals(403, status(refused), "a viewer writes nothing, hours included")
		refused.andExpect { jsonPath("$.detail") { value(dev.kanso.service.TicketAccess.READS_NOT_WRITES) } }

		readTime(viewer).andExpect {
			status { isOk() }
			jsonPath("$.totalMinutes") { value(45) }
		}
	}

	// --- rule five: what a person may assert --------------------------------

	@Test
	fun `a person may not assert nothing, nor longer than a month`() {
		assertEquals(400, status(log(worker, """{"minutes":0}""")), "an entry of no time is not an entry")
		assertEquals(400, status(log(worker, """{"minutes":44641}""")), "longer than a month is a typo")
		assertEquals(201, status(log(worker, """{"minutes":44640,"spentOn":"2026-09-03"}""")), "a month exactly is allowed")
	}

	// --- the V38 question ---------------------------------------------------

	/**
	 * **Logging hours is not editing the ticket**, asserted on the column itself.
	 *
	 * This is the guard this feature would most want back if somebody added a
	 * `tickets.logged_minutes` running total in six months. `V38` made `updated_at` mean *a
	 * person edited this*, and `NotionPoller.kansoWins` reads it to decide who wins a
	 * conflict — so a bookkeeping write here would queue a corrective push that reverts a
	 * real edit made in Notion. The answer is structural rather than a list of ignored
	 * columns: nothing in this feature writes a column on `tickets` at all, so the trigger
	 * never fires.
	 */
	@Test
	fun `logging time never moves the ticket's updated_at`() {
		fun stamp(): OffsetDateTime =
			Tickets.selectAll().where { Tickets.id eq ticket }.single()[Tickets.updatedAt]

		val before = stamp()
		log(worker, """{"minutes":45,"spentOn":"2026-09-03"}""").andExpect { status { isCreated() } }
		val entry = idOf(log(worker, """{"minutes":30,"spentOn":"2026-09-01"}"""))
		startTimer(worker).andExpect { status { isCreated() } }
		mvc.patch("/api/time/$entry") {
			header(DevAuthenticationFilter.HEADER, worker.email)
			contentType = MediaType.APPLICATION_JSON
			content = """{"minutes":20}"""
		}.andExpect { status { isOk() } }

		assertEquals(
			before,
			stamp(),
			"two entries, a started clock and a correction are bookkeeping, not an edit to the ticket",
		)
	}
}

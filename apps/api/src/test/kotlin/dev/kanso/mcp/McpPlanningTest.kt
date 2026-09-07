package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketService
import dev.kanso.service.ViewSortBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three planning tools, driven the whole way: a grant, a bearer token,
 * [McpBearerFilter], and a JSON-RPC `tools/call` on the other side.
 *
 * Never a tool object called directly, for [McpToolsTest]'s reason — the question is
 * whether a *token* lands on its owner's rights, and an in-process call with a hand-built
 * `User` answers it by assumption.
 *
 * Its own suite rather than more of [McpToolsTest], which is already long. The split is by
 * subject and not by convenience: that file is about one ticket at a time, and everything
 * here is about a ticket's relationship to *other* tickets — which is the whole of what
 * KAN-20 added and the whole of what can be got wrong in a new way.
 */
@Transactional
class McpPlanningTest : PostgresTest() {

	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService
	@Autowired lateinit var build: BuildProperties
	@Autowired lateinit var transactionManager: PlatformTransactionManager
	@Autowired lateinit var currentUser: CurrentUser

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var tickets: TicketService

	@Autowired lateinit var tools: List<McpTool>

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	private val mvc: MockMvc by lazy {
		mcpMvc(build, currentUser, tools, authorizations, clients, users, transactionManager)
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- fixtures ------------------------------------------------------------

	private fun user(role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "plan-${UUID.randomUUID()}@kanso.test",
		displayName = "Plan ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "P${UUID.randomUUID().toString().take(4).uppercase()}"

	/**
	 * A team somebody has claimed — [McpToolsTest] takes the same precaution and for the
	 * same reason: `TicketAccess`'s open-chain clause leaves a team nobody has joined
	 * editable by everyone, so a refusal asserted against an empty team would pass for the
	 * wrong reason.
	 */
	private fun teamOf(owner: User, name: String): Team {
		val admin = user(InstanceRole.ADMIN)
		val team = teams.create(asSessionOf(admin), name, key(), null)
		teamRepo.addMember(team.id, owner.id, MemberRole.MEMBER)
		SecurityContextHolder.clearContext()
		return team
	}

	/** Only ever used to build fixtures — never to assert anything about an agent. */
	private fun asSessionOf(member: User): User {
		SecurityContextHolder.setContext(
			SecurityContextHolder.createEmptyContext().apply {
				authentication = UsernamePasswordAuthenticationToken(
					KansoLocalUser(member.id, member.email, member.displayName),
					null,
					listOf(SimpleGrantedAuthority("ROLE_USER")),
				)
			},
		)
		return currentUser.require()
	}

	private fun bearer(member: User, scopes: Set<String> = OAuthScopes.ALL.toSet()): String =
		"Bearer " + grants.issue(member, scopes, clientId = CLIENT)

	private fun file(
		team: Team,
		owner: User,
		title: String,
		status: DefaultStatus = DefaultStatus.TODO,
		priority: TicketPriority = TicketPriority.NONE,
		estimate: Int? = null,
		assignees: List<UUID> = emptyList(),
	): TicketDetail = tickets.create(
		actor = asSessionOf(owner),
		teamId = team.id,
		title = title,
		description = null,
		status = status,
		priority = priority,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = assignees,
		docIds = emptyList(),
		estimate = estimate,
	).also { SecurityContextHolder.clearContext() }

	private fun inTeam(team: Team): List<TicketDetail> = tickets.list(
		teamId = team.id,
		includeDescendants = false,
		includeArchived = false,
		filters = TicketFilters(),
		sortBy = ViewSortBy.UPDATED,
		limit = 200,
		offset = 0,
	)

	// --- the call ------------------------------------------------------------

	private fun rpc(body: String, authorization: String?) = mvc.post(McpResource.PATH) {
		contentType = MediaType.APPLICATION_JSON
		content = body
		if (authorization != null) header("Authorization", authorization)
	}

	private fun call(tool: String, arguments: String, authorization: String) =
		rpc(callBody(tool, arguments), authorization)

	private fun read(ticket: String, member: User): String =
		textOf(call("kanso_get_ticket", """{"ticket":"$ticket"}""", bearer(member)))

	// --- links ---------------------------------------------------------------

	/**
	 * One row, two sentences. The assertion is on *both* ends because that is the only way
	 * to catch the mistake this rendering can make: a `phrase` that ignored `outgoing`
	 * would print "blocks" on both, and every single-ended assertion would pass.
	 */
	@Test
	fun `a blocks edge reads as blocks at one end and blocked by at the other`() {
		val alice = user()
		val team = teamOf(alice, "Planning")
		val first = file(team, alice, "Migrate the schema")
		val second = file(team, alice, "Ship the screen")

		val drawn = textOf(
			call(
				"kanso_link_tickets",
				"""{"from":"${first.identifier}","to":"${second.identifier}","type":"blocks"}""",
				bearer(alice),
			),
		)
		assertTrue(
			drawn.contains("${first.identifier} blocks ${second.identifier}"),
			"the confirmation is the sentence, not a row id: $drawn",
		)

		// Asserted on the phrases and not on the column widths: "blocked by" does not contain
		// "blocks", so each end can be checked for one and against the other, and the pair of
		// assertions is what catches a `phrase` that ignored `outgoing` and printed "blocks"
		// on both. A padded literal would go red the day somebody widens the column.
		val upstream = read(first.identifier!!, alice)
		assertTrue(upstream.contains("blocks"), "the predecessor blocks: $upstream")
		assertFalse(upstream.contains("blocked by"), "and is not the one waiting: $upstream")
		assertTrue(upstream.contains(second.identifier!!), "and names the far end: $upstream")

		val downstream = read(second.identifier!!, alice)
		assertTrue(downstream.contains("blocked by"), "the successor waits: $downstream")
		assertFalse(downstream.contains("blocks"), "and blocks nothing: $downstream")
		assertTrue(downstream.contains(first.identifier!!), "and names what it waits on: $downstream")
	}

	/**
	 * The refusal an agent can act on, and the proof that this tool goes through
	 * `ScheduleService` rather than inserting its own row.
	 *
	 * A tool reaching `TicketLinkRepository` would draw this second arrow happily and leave
	 * a cycle in the graph the scheduler walks. The chain in the message is the tell: no
	 * code in the `mcp` package could produce it.
	 */
	@Test
	fun `a blocks edge that would close a loop is refused with the chain it would close`() {
		val alice = user()
		val team = teamOf(alice, "Loops")
		val first = file(team, alice, "First")
		val second = file(team, alice, "Second")

		call(
			"kanso_link_tickets",
			"""{"from":"${first.identifier}","to":"${second.identifier}","type":"blocks"}""",
			bearer(alice),
		).andExpect { status { isOk() } }

		val refused = textOf(
			call(
				"kanso_link_tickets",
				"""{"from":"${second.identifier}","to":"${first.identifier}","type":"blocks"}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("close a loop"), "refused as a loop: $refused")
		assertTrue(refused.contains("->"), "and the chain is in it: $refused")
		// And the graph is unchanged: the second ticket is blocked by the first and blocks
		// nothing. A refusal that had inserted first would leave both sentences printed here.
		val downstream = read(second.identifier!!, alice)
		assertTrue(downstream.contains("blocked by"), "the original edge survives: $downstream")
		assertFalse(downstream.contains("blocks"), "and the refused one was never drawn: $downstream")
	}

	/**
	 * The refusal names what the caller typed.
	 *
	 * `TicketLinkService.unlink` and `ScheduleService.unlink` both answer with the two
	 * UUIDs they were handed, which an agent addressing tickets as `KAN-12` has never seen.
	 * Remove the rewrite in `LinkTicketsTool` and this goes red on the second assertion
	 * with a message full of hex.
	 */
	@Test
	fun `removing an edge that is not there is refused in the identifiers the caller typed`() {
		val alice = user()
		val team = teamOf(alice, "Erasing")
		val first = file(team, alice, "First")
		val second = file(team, alice, "Second")

		val refused = textOf(
			call(
				"kanso_link_tickets",
				"""{"from":"${first.identifier}","to":"${second.identifier}","type":"relates","remove":true}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains(first.identifier!!), "it names the near end: $refused")
		assertTrue(refused.contains(second.identifier!!), "and the far end: $refused")
		assertFalse(
			refused.contains(first.ticket.id.toString()),
			"and no id the caller has never seen: $refused",
		)
	}

	/** A ticket attached to nothing prints no heading for it. */
	@Test
	fun `a ticket with no links and no parts says nothing about either`() {
		val alice = user()
		val team = teamOf(alice, "Bare")
		val lonely = file(team, alice, "Nothing attached")

		val answer = read(lonely.identifier!!, alice)
		assertFalse(answer.contains("links:"), "no empty links heading: $answer")
		assertFalse(answer.contains("sub-tickets"), "no empty sub-tickets heading: $answer")
	}

	// --- splitting -----------------------------------------------------------

	/**
	 * The parts are filed, parented, and inherit what a caller should not have to restate.
	 *
	 * The priority assertion is the one worth having: a part of an urgent ticket that came
	 * out `none` would be a split that quietly downgraded the work, and nothing in the
	 * confirmation would say so.
	 */
	@Test
	fun `splitting files the parts under the parent, inheriting its team and priority`() {
		val alice = user()
		val team = teamOf(alice, "Splitting")
		val big = file(team, alice, "Rewrite the importer", priority = TicketPriority.URGENT, estimate = 13)

		val answer = textOf(
			call(
				"kanso_split_ticket",
				"""{"ticket":"${big.identifier}","parts":[
					{"title":"Parse the export","estimate":5},
					{"title":"Map the columns","estimate":5},
					{"title":"Write the rows","estimate":3,"status":"in_progress"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(answer.contains("3 sub-ticket(s)"), "three were filed: $answer")

		val children = inTeam(team).filter { it.ticket.parentId == big.ticket.id }
		assertEquals(3, children.size, "and three rows carry the parent")
		assertTrue(
			children.all { it.ticket.priority == TicketPriority.URGENT },
			"a part of an urgent ticket is urgent: ${children.map { it.ticket.priority }}",
		)
		assertTrue(children.all { it.ticket.teamId == team.id }, "and lands in the parent's team")
		assertEquals(
			setOf(DefaultStatus.TODO, DefaultStatus.TODO, DefaultStatus.IN_PROGRESS).size,
			children.map { it.ticket.status }.toSet().size,
			"the status each part asked for, defaulted to todo",
		)

		// And the parent now reads as a parent, with the number computed on read.
		val parent = read(big.identifier!!, alice)
		assertTrue(parent.contains("sub-tickets — 0 of 3 done"), "the parent counts them: $parent")
		assertTrue(parent.contains("Parse the export"), "and lists them: $parent")
	}

	/**
	 * All or nothing, which is the whole reason this is one tool instead of ten calls.
	 *
	 * The third part names an address nobody answers to, so `McpPeople.resolve` raises after
	 * two children have already been inserted and parented. Nothing may survive. Take the
	 * `@Transactional` off `SplitTicketTool.call` and this goes red at two.
	 */
	@Test
	fun `a split whose last part is refused files none of the earlier ones`() {
		val alice = user()
		val team = teamOf(alice, "Atomic")
		val big = file(team, alice, "Three parts, one bad")
		val before = inTeam(team).size

		val refused = textOf(
			call(
				"kanso_split_ticket",
				"""{"ticket":"${big.identifier}","parts":[
					{"title":"Good one"},
					{"title":"Good two"},
					{"title":"Bad three","assignees":["nobody@nowhere.test"]}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("nobody@nowhere.test"), "it says which address: $refused")
		assertEquals(before, inTeam(team).size, "and not one part survived the refusal")
	}

	/**
	 * One level deep, refused in the identifier the caller typed.
	 *
	 * Without `SplitTicketTool.refuseNesting` this still refuses — `SubTicketService`'s own
	 * rule 2 fires inside the transaction — but with the parent's UUID in the message
	 * instead of `PXXX-1`, so the last assertion is what proves the rewrite is there.
	 */
	@Test
	fun `a sub-ticket cannot be split again, and the refusal names the parent to split instead`() {
		val alice = user()
		val team = teamOf(alice, "Nesting")
		val big = file(team, alice, "Top level")
		call(
			"kanso_split_ticket",
			"""{"ticket":"${big.identifier}","parts":[{"title":"The only part"}]}""",
			bearer(alice),
		).andExpect { status { isOk() } }

		val child = inTeam(team).first { it.ticket.parentId == big.ticket.id }
		val before = inTeam(team).size

		val refused = textOf(
			call(
				"kanso_split_ticket",
				"""{"ticket":"${child.identifier}","parts":[{"title":"A third level"}]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("do not nest"), "refused as nesting: $refused")
		assertTrue(refused.contains(child.identifier!!), "naming what was asked for: $refused")
		assertFalse(
			refused.contains(child.ticket.id.toString()),
			"and no id the caller has never seen: $refused",
		)
		assertEquals(before, inTeam(team).size, "and nothing was filed")
	}

	/**
	 * A misspelling inside the list is refused, and the refusal says *which* part.
	 *
	 * `McpArguments.objects` handing back a reader per element is what makes this possible;
	 * a raw `List<Map<…>>` would have dropped `titel` in silence and then refused for the
	 * missing `title`, which sends the caller looking in the wrong place.
	 */
	@Test
	fun `an unknown key inside a part is refused, with the index of the part that has it`() {
		val alice = user()
		val team = teamOf(alice, "Typos")
		val big = file(team, alice, "To split")
		val before = inTeam(team).size

		val refused = textOf(
			call(
				"kanso_split_ticket",
				"""{"ticket":"${big.identifier}","parts":[
					{"title":"Fine"},
					{"titel":"Typo"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("titel"), "it names the key: $refused")
		assertTrue(refused.contains("parts[1]"), "and which part carried it: $refused")
		assertEquals(before, inTeam(team).size, "and the good part was rolled back with it")
	}

	/** A bound, not a truncation — the parts of a split have to add up to it. */
	@Test
	fun `a split past the bound is refused rather than shortened`() {
		val alice = user()
		val team = teamOf(alice, "Bounded")
		val big = file(team, alice, "Far too many")
		val before = inTeam(team).size

		val parts = (1..21).joinToString(",") { """{"title":"Part $it"}""" }
		val refused = textOf(
			call("kanso_split_ticket", """{"ticket":"${big.identifier}","parts":[$parts]}""", bearer(alice)),
		)
		assertTrue(refused.contains("at most 20"), "it names the bound: $refused")
		assertTrue(refused.contains("21"), "and what was asked: $refused")
		assertEquals(before, inTeam(team).size, "and filed nothing")
	}

	/**
	 * `TicketAccess` applies to an agent unchanged, on a door that did not exist yesterday.
	 *
	 * `AgentRightsTest` makes this argument for the four tools KAN-52 shipped. Re-asserted
	 * on this one because a writing tool is only as safe as the service it reaches, and a
	 * `SubTicketService.setParent` call made before the access check would have written two
	 * tickets into somebody else's team before refusing.
	 */
	@Test
	fun `splitting a ticket in a team its owner may not touch is refused and files nothing`() {
		val alice = user()
		val bob = user()
		val hers = teamOf(bob, "Not Alice's")
		val big = file(hers, bob, "Bob's big ticket")
		val before = inTeam(hers).size

		val refused = textOf(
			call(
				"kanso_split_ticket",
				"""{"ticket":"${big.identifier}","parts":[{"title":"Alice helps herself"}]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("not one of your teams"), "refused in the member's own words: $refused")
		assertEquals(before, inTeam(hers).size, "and her part was never filed")
	}

	// --- workload ------------------------------------------------------------

	/**
	 * The evidence, with the charge and the work-in-flight in separate columns.
	 *
	 * That separation is the assertion worth having: `WorkloadService.OPEN_STATUSES` is
	 * everything unsettled and `StatusCategory.STARTED` is what is actually moving, and a
	 * single column would have hidden whichever one the reader needed. Three open of which
	 * one started reads as `3` and `1`, never as one number twice.
	 */
	@Test
	fun `team workload counts the charge and the work in flight as two different numbers`() {
		val alice = user()
		val team = teamOf(alice, "Loaded")
		file(team, alice, "Queued", status = DefaultStatus.TODO, estimate = 5, assignees = listOf(alice.id))
		file(team, alice, "Moving", status = DefaultStatus.IN_PROGRESS, estimate = 3, assignees = listOf(alice.id))
		file(team, alice, "Unsized", status = DefaultStatus.TODO, assignees = listOf(alice.id))
		file(team, alice, "Nobody's", status = DefaultStatus.TODO)
		// Settled work is on nobody's plate, so it must not raise either number.
		file(team, alice, "Finished", status = DefaultStatus.DONE, estimate = 8, assignees = listOf(alice.id))

		val answer = textOf(call("kanso_team_workload", """{"team":"${team.key}"}""", bearer(alice)))
		val hers = answer.lines().first { it.contains(alice.email) }.split(Regex("\\s+"))
		assertEquals(alice.email, hers[0])
		assertEquals("3", hers[1], "three open, the done one excluded: $answer")
		assertEquals("1", hers[2], "one of them actually started: $answer")
		assertEquals("8", hers[3], "the sized part weighs 8, not 16: $answer")
		assertEquals("1", hers[4], "and one carries no estimate at all: $answer")

		assertTrue(answer.contains("(nobody assigned)"), "the orphan pile is a row: $answer")
		assertTrue(answer.contains("started"), "and the columns are named: $answer")
	}

	/**
	 * It is a read, and it recommends nobody.
	 *
	 * The scope half is structural — a read-only grant reaching it proves `writes = false`
	 * is declared, and a `@Transactional(readOnly = true)` tool cannot insert anything
	 * anyway. The second half is the ticket's constraint asserted as text: no tool in this
	 * server may hand back a name it chose.
	 */
	@Test
	fun `team workload is readable with a read-only grant, and proposes nobody`() {
		val alice = user()
		val team = teamOf(alice, "Read only")
		file(team, alice, "Something", assignees = listOf(alice.id))

		val answer = textOf(
			call("kanso_team_workload", """{"team":"${team.key}"}""", bearer(alice, setOf(OAuthScopes.READ))),
		)
		assertTrue(answer.contains(alice.email), "the read went through: $answer")
		for (verdict in listOf("suggest", "recommend", "should take", "best fit")) {
			assertFalse(answer.contains(verdict, ignoreCase = true), "no verdict in the answer: $answer")
		}
	}

	/**
	 * And the two writing tools are not, which is the gate [McpController] applies before a
	 * service is reached at all. HTTP rather than a tool result, so a client can run a
	 * step-up flow off it.
	 */
	@Test
	fun `a read-only grant cannot reach either planning write, and is told which scope to ask for`() {
		val alice = user()
		val team = teamOf(alice, "Scoped")
		val big = file(team, alice, "Untouchable")
		val other = file(team, alice, "Also untouchable")
		val readOnly = bearer(alice, setOf(OAuthScopes.READ))
		val before = inTeam(team).size

		call(
			"kanso_split_ticket",
			"""{"ticket":"${big.identifier}","parts":[{"title":"Nope"}]}""",
			readOnly,
		).andExpect {
			status { isForbidden() }
			header { string("WWW-Authenticate", containsString(OAuthScopes.WRITE)) }
		}
		call(
			"kanso_link_tickets",
			"""{"from":"${big.identifier}","to":"${other.identifier}","type":"relates"}""",
			readOnly,
		).andExpect {
			status { isForbidden() }
			header { string("WWW-Authenticate", containsString(OAuthScopes.WRITE)) }
		}
		assertEquals(before, inTeam(team).size, "and neither wrote anything")
	}

	private companion object {
		const val CLIENT = "claude-code"
	}
}

package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import dev.kanso.service.ProjectService
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
 * `kanso_plan`, driven through the real door: a grant, a bearer, [McpBearerFilter], and a
 * JSON-RPC `tools/call` on the other side. Never the tool object directly, for
 * [McpPlanningTest]'s reason.
 *
 * **Almost every assertion below is "and nothing was filed", and that is the point of the
 * suite rather than a habit.** `kanso_plan` is the first tool here whose failure could
 * leave a shape in the backlog that no single call can undo — nine tickets, two of them
 * parented, one edge drawn — so the refusals are worth more than the success. They are
 * *provable* because [PlanDraft] raises every one of them before the first insert: this
 * suite is `@Transactional` and joins the tool's own transaction, so it could never
 * observe a rollback, which is exactly why the design does not rely on one. See the note
 * on the last test in this file for the one guard that stays unproven, and why.
 *
 * Its own suite rather than more of [McpPlanningTest], which is 570 lines and about a
 * ticket's relationship to other tickets that already exist. Everything here is about
 * tickets that do not exist yet, which is the whole of what a local `ref` is for.
 */
@Transactional
class McpPlanTest : PostgresTest() {

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
	@Autowired lateinit var projects: ProjectService

	@Autowired lateinit var tools: List<McpTool>

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	private val mvc: MockMvc by lazy {
		mcpMvc(build, currentUser, tools, authorizations, clients, users, transactionManager)
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- fixtures ------------------------------------------------------------

	private fun user(role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "planned-${UUID.randomUUID()}@kanso.test",
		displayName = "Planner ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "Q${UUID.randomUUID().toString().take(4).uppercase()}"

	/** A team somebody has claimed — [McpPlanningTest] takes the same precaution, for its reason. */
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

	private fun projectIn(team: Team, owner: User, name: String) = projects.create(
		actor = asSessionOf(owner),
		name = name,
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = team.id,
		docIds = emptyList(),
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

	private fun plan(arguments: String, authorization: String) = call("kanso_plan", arguments, authorization)

	private fun read(ticket: String, member: User): String =
		textOf(call("kanso_get_ticket", """{"ticket":"$ticket"}""", bearer(member)))

	// --- the whole plan, in one call -----------------------------------------

	/**
	 * The gesture the ticket is about: four tickets, a hierarchy and a dependency, filed by
	 * one call into one project.
	 *
	 * The assertion that matters most is the **pairing of `ref` to identifier** in the
	 * answer. That is the only place the model's own vocabulary and Kanso's meet, and an
	 * agent that cannot make the join has to count rows to know which ticket is `door` — and
	 * will eventually miscount and tell somebody the wrong number. Everything else here is
	 * checked in the database rather than in the prose, because a tool that reported a
	 * hierarchy it had not written would pass a text-only assertion.
	 */
	@Test
	fun `one call files the tickets, hangs the parts under their parent and draws the dependency`() {
		val alice = user()
		val team = teamOf(alice, "Greenfield")
		val project = projectIn(team, alice, "The MCP door")

		val answer = textOf(
			plan(
				"""{"team":"${team.key}","project":"The MCP door","tickets":[
					{"ref":"door","title":"The OAuth door","priority":"urgent","estimate":8},
					{"ref":"discovery","title":"Protected resource discovery","parent":"door"},
					{"ref":"consent","title":"The consent screen","parent":"door","status":"backlog"},
					{"ref":"tools","title":"The tool surface","estimate":5}
				],"links":[
					{"from":"door","to":"tools","type":"blocks"}
				]}""",
				bearer(alice),
			),
		)

		assertTrue(answer.contains("Filed 4 ticket(s) in ${team.key}"), "four, and it says where: $answer")
		assertTrue(answer.contains("project The MCP door"), "and which project: $answer")

		val filed = inTeam(team)
		assertEquals(4, filed.size, "four rows exist")
		val byTitle = filed.associateBy { it.ticket.title }
		val door = byTitle.getValue("The OAuth door")

		// The pairing: every `ref` the caller invented appears against the identifier it got.
		for ((ref, title) in listOf(
			"door" to "The OAuth door",
			"discovery" to "Protected resource discovery",
			"consent" to "The consent screen",
			"tools" to "The tool surface",
		)) {
			val identifier = byTitle.getValue(title).identifier
			assertTrue(
				answer.lines().any { it.contains(ref) && it.contains(identifier!!) },
				"`$ref` is reported against $identifier: $answer",
			)
		}

		// The hierarchy is in the rows, not only in the prose.
		val parts = filed.filter { it.ticket.parentId == door.ticket.id }.map { it.ticket.title }.toSet()
		assertEquals(
			setOf("Protected resource discovery", "The consent screen"),
			parts,
			"two parts hang under the parent the plan named",
		)
		assertTrue(
			byTitle.getValue("The tool surface").ticket.parentId == null,
			"and the ticket that named no parent is top-level",
		)

		// Every ticket landed in the plan's project, which nothing in `tickets` restated.
		assertTrue(
			filed.all { it.ticket.projectId == project.project.id },
			"all four are in the project: ${filed.map { it.ticket.projectId }}",
		)
		// The priority is per-ticket and is not inherited by anything — this is a plan, not a
		// split, and `none` on the other three is the value they asked for.
		assertEquals(TicketPriority.URGENT, door.ticket.priority, "the ticket that said urgent is urgent")
		assertEquals(
			TicketPriority.NONE,
			byTitle.getValue("The tool surface").ticket.priority,
			"and a plan inherits no priority between siblings",
		)

		// And the edge is a real `blocks` row, read back through the reading tool.
		val upstream = read(door.identifier!!, alice)
		assertTrue(upstream.contains("blocks"), "the parent blocks the tool surface: $upstream")
		val downstream = read(byTitle.getValue("The tool surface").identifier!!, alice)
		assertTrue(downstream.contains("blocked by"), "and that one waits: $downstream")
	}

	/** A plan with no project names none, and the tickets are filed all the same. */
	@Test
	fun `a plan may name no project at all`() {
		val alice = user()
		val team = teamOf(alice, "Projectless")

		val answer = textOf(
			plan("""{"team":"${team.key}","tickets":[{"ref":"a","title":"Just the one"}]}""", bearer(alice)),
		)
		assertTrue(answer.contains("Filed 1 ticket(s)"), "filed: $answer")
		assertFalse(answer.contains("project"), "and no project is mentioned: $answer")
		assertEquals(1, inTeam(team).size)
		assertTrue(inTeam(team).single().ticket.projectId == null, "the row is in no project")
	}

	// --- the constraint, as a test -------------------------------------------

	/**
	 * **The version of this ticket that must not be built, asserted as a refusal.**
	 *
	 * KAN-72's constraint is that Kanso embeds no LLM: `kanso_plan` records a plan a model
	 * formed and never derives one from a spec. That is a claim about a surface, so it can be
	 * tested like one — the argument a spec-reading tool would have to take is not served,
	 * and `McpArguments.refuseUnknown` says so and lists what is. If somebody ever adds it,
	 * this goes red and they have to come and read the sentence above.
	 */
	@Test
	fun `kanso_plan does not take a spec, and says what it does take`() {
		val alice = user()
		val team = teamOf(alice, "No model here")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","spec":"# Design\n\nWe should build a door.",
					"tickets":[{"ref":"a","title":"Whatever it decides"}]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("does not take: spec"), "the argument is refused: $refused")
		assertTrue(refused.contains("tickets"), "and the served ones are named: $refused")
		assertEquals(0, inTeam(team).size, "and no ticket was invented from it")
	}

	// --- the refusals, none of which write anything --------------------------

	/**
	 * The spec's third test, and the one it says matters most after the dry run: a plan that
	 * fails late lands nothing.
	 *
	 * Provable here precisely because there is no dry run — `McpPeople.resolve` runs inside
	 * [PlanDraft], so the fourth ticket's bad address is refused while the transaction still
	 * holds nothing. Move that resolution down into the writing loop and this goes red at
	 * three.
	 */
	@Test
	fun `a plan whose last ticket names an unknown assignee files none of the earlier ones`() {
		val alice = user()
		val team = teamOf(alice, "Atomic plan")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"a","title":"Good one"},
					{"ref":"b","title":"Good two"},
					{"ref":"c","title":"Good three"},
					{"ref":"d","title":"Bad four","assignees":["nobody@nowhere.test"]}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("nobody@nowhere.test"), "it says which address: $refused")
		assertEquals(0, inTeam(team).size, "and not one of the four survived")
	}

	/**
	 * The estimate scale, refused before the first insert and named against its `ref`.
	 *
	 * This is the refusal that used to live *after* a row — `TicketService.create` calls
	 * `EffortPoints.from`, so left to it a plan whose third ticket is estimated `4` would
	 * have filed two and rolled them back. `PlanDraft.estimateOf` asks the same domain
	 * function earlier, which is what makes the count below assertable at all. Delete that
	 * method and this test goes red on the count while still passing on the message.
	 */
	@Test
	fun `an off-scale estimate is refused before anything is filed, and names the ticket that carried it`() {
		val alice = user()
		val team = teamOf(alice, "Off scale")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"first","title":"Sized fine","estimate":3},
					{"ref":"second","title":"Also fine","estimate":5},
					{"ref":"third","title":"Not on the scale","estimate":4}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("third"), "it names the ref, not just the number: $refused")
		assertTrue(refused.contains("4"), "and the value: $refused")
		assertTrue(refused.contains("13"), "and the scale it is not on: $refused")
		assertEquals(0, inTeam(team).size, "and the two good ones were never filed")
	}

	/**
	 * The loop, refused in the caller's own vocabulary with the chain that closes it.
	 *
	 * `ScheduleService.linkRefusal` would also refuse this — it is the authority and it still
	 * runs on every edge — but only after three tickets exist, and KAN-70 made it name the
	 * chain in *identifiers*: an agent would be handed three numbers for rows that stop
	 * existing a moment later. So the assertion is doubled deliberately: the refs are in the
	 * message and the identifiers cannot be, because nothing was filed to have any.
	 */
	@Test
	fun `a blocks cycle among a plan's own tickets is refused in refs, and files nothing`() {
		val alice = user()
		val team = teamOf(alice, "Looping plan")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"schema","title":"Migrate the schema"},
					{"ref":"api","title":"Serve it"},
					{"ref":"screen","title":"Draw it"}
				],"links":[
					{"from":"schema","to":"api","type":"blocks"},
					{"from":"api","to":"screen","type":"blocks"},
					{"from":"screen","to":"schema","type":"blocks"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("close a loop"), "refused as a loop: $refused")
		assertTrue(refused.contains("->"), "with the chain in it: $refused")
		for (ref in listOf("schema", "api", "screen")) {
			assertTrue(refused.contains(ref), "naming `$ref`, which is all the caller has: $refused")
		}
		assertEquals(0, inTeam(team).size, "and the three tickets were never filed")
	}

	/**
	 * A `relates` triangle is not a loop, which is the assertion that stops the check above
	 * from being a check on any cycle at all.
	 *
	 * The scheduler has only ever looked at `blocks` (`DependencyRepository` filters on it in
	 * SQL), so `relates` orders nothing and three of them in a ring is a perfectly ordinary
	 * plan. A `refuseLoop` that walked every type would refuse this, and nobody would find
	 * out from the test above.
	 */
	@Test
	fun `a ring of relates links is not a loop and is filed`() {
		val alice = user()
		val team = teamOf(alice, "Related ring")

		val answer = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"a","title":"One"},
					{"ref":"b","title":"Two"},
					{"ref":"c","title":"Three"}
				],"links":[
					{"from":"a","to":"b","type":"relates"},
					{"from":"b","to":"c","type":"relates"},
					{"from":"c","to":"a","type":"relates"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(answer.contains("Filed 3 ticket(s)"), "filed, not refused: $answer")
		assertTrue(answer.contains("relates to"), "and the edges read as sentences: $answer")
		assertEquals(3, inTeam(team).size)
	}

	/**
	 * Three levels, refused in refs before anything exists.
	 *
	 * `SubTicketService.parentRefusal` is the authority and would refuse it too, in UUIDs and
	 * after three inserts. The middle ticket is the one that cannot be both, so the sentence
	 * names it and says where the third should go instead.
	 */
	@Test
	fun `a plan asking for three levels is refused, and says which ref cannot be both`() {
		val alice = user()
		val team = teamOf(alice, "Too deep")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"epic","title":"The whole thing"},
					{"ref":"part","title":"A part of it","parent":"epic"},
					{"ref":"bit","title":"A bit of the part","parent":"part"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("do not nest"), "refused as nesting: $refused")
		assertTrue(refused.contains("part"), "naming the middle ticket: $refused")
		assertTrue(refused.contains("epic"), "and where the third should hang instead: $refused")
		assertEquals(0, inTeam(team).size, "and nothing was filed")
	}

	/** A parent nobody in the plan answers to, with the way out named. */
	@Test
	fun `a parent that is not in the plan is refused with the tools that can reach one that exists`() {
		val alice = user()
		val team = teamOf(alice, "Absent parent")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"a","title":"Orphan","parent":"nowhere"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("nowhere"), "it names the ref that resolves to nothing: $refused")
		assertTrue(refused.contains("kanso_create_ticket"), "and the tool that can file under a real one: $refused")
		assertEquals(0, inTeam(team).size)
	}

	/**
	 * The confusion this surface invites, refused on purpose.
	 *
	 * An agent that has spent a conversation typing `KAN-142` will type one into `links`.
	 * Accepted, it would have named a brand-new ticket and drawn the edge between two rows
	 * nobody meant — a plan that succeeds and is wrong, which is worse than any refusal. The
	 * message has to say which tool does reach standing work, or the agent is simply stuck.
	 */
	@Test
	fun `a link naming a real identifier instead of a ref is refused, and points at the tool that takes one`() {
		val alice = user()
		val team = teamOf(alice, "Mixed vocabularies")
		val standing = textOf(
			call("kanso_create_ticket", """{"team":"${team.key}","title":"Already here"}""", bearer(alice)),
		)
		val existing = standing.substringAfter("Created ").substringBefore(" —")
		val before = inTeam(team).size

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"new","title":"Depends on standing work"}
				],"links":[
					{"from":"$existing","to":"new","type":"blocks"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("looks like a ticket identifier"), "it says what the caller typed: $refused")
		assertTrue(refused.contains("kanso_link_tickets"), "and which tool takes one: $refused")
		assertTrue(refused.contains("new"), "and lists what this plan does file: $refused")
		assertEquals(before, inTeam(team).size, "and the new ticket was not filed")
	}

	/** Two tickets answering to one name would make every refusal above ambiguous. */
	@Test
	fun `a repeated ref is refused`() {
		val alice = user()
		val team = teamOf(alice, "Same name twice")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"door","title":"First"},
					{"ref":"door","title":"Second"},
					{"ref":"window","title":"Third"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("one `ref` per ticket"), "refused as a repeat: $refused")
		assertTrue(refused.contains("door"), "naming the one that repeats: $refused")
		assertFalse(refused.contains("window"), "and not the one that does not: $refused")
		assertEquals(0, inTeam(team).size)
	}

	/**
	 * A misspelling inside one ticket of fifty, refused with the index of the ticket that has
	 * it — `McpArguments.objects` handing back a reader per element is what makes that
	 * possible.
	 */
	@Test
	fun `an unknown key inside one ticket is refused, with the index of the ticket that has it`() {
		val alice = user()
		val team = teamOf(alice, "Typo in a plan")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","tickets":[
					{"ref":"a","title":"Fine"},
					{"ref":"b","titel":"Typo"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("titel"), "it names the key: $refused")
		assertTrue(refused.contains("tickets[1]"), "and which ticket carried it: $refused")
		assertEquals(0, inTeam(team).size)
	}

	/** A bound, not a truncation: a plan cut to fifty has dependencies pointing at nothing. */
	@Test
	fun `a plan past the ticket bound is refused rather than shortened`() {
		val alice = user()
		val team = teamOf(alice, "Bounded plan")

		val many = (1..51).joinToString(",") { """{"ref":"r$it","title":"Ticket $it"}""" }
		val refused = textOf(plan("""{"team":"${team.key}","tickets":[$many]}""", bearer(alice)))

		assertTrue(refused.contains("at most 50"), "it names the bound: $refused")
		assertTrue(refused.contains("51"), "and what was asked: $refused")
		assertEquals(0, inTeam(team).size, "and filed nothing")
	}

	/** Structure is not invented: an unknown project is refused with the ones that exist. */
	@Test
	fun `a plan naming a project that does not exist is refused with the projects that do`() {
		val alice = user()
		val team = teamOf(alice, "Structure")
		projectIn(team, alice, "The real one")

		val refused = textOf(
			plan(
				"""{"team":"${team.key}","project":"The imagined one",
					"tickets":[{"ref":"a","title":"Into thin air"}]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("The imagined one"), "it names what was asked for: $refused")
		assertTrue(refused.contains("The real one"), "and what there is: $refused")
		assertTrue(refused.contains("does not create one"), "and says it will not invent it: $refused")
		assertEquals(0, inTeam(team).size, "and filed nothing into no project")
	}

	/** And the same for the team, which is `TicketLines.teamByKey`'s refusal reached from here. */
	@Test
	fun `a plan naming a team that does not exist is refused with the keys that do`() {
		val alice = user()
		val team = teamOf(alice, "Real team")

		val refused = textOf(plan("""{"team":"NOPE","tickets":[{"ref":"a","title":"Nowhere"}]}""", bearer(alice)))
		assertTrue(refused.contains("No team with key `NOPE`"), "refused by key: $refused")
		assertTrue(refused.contains(team.key), "and the real ones listed: $refused")
	}

	// --- the seat and the scope ----------------------------------------------

	/**
	 * `TicketAccess` applies to an agent unchanged, on the newest writing tool.
	 *
	 * Re-asserted here rather than left to `AgentRightsTest` because a plan makes three kinds
	 * of write — a ticket, a parenthood, an edge — and a tool that reached any of them before
	 * the access check would have put work in somebody else's team before refusing.
	 */
	@Test
	fun `a plan filed into a team its owner may not touch is refused and files nothing`() {
		val alice = user()
		val bob = user()
		val hers = teamOf(bob, "Not Alice's team")

		val refused = textOf(
			plan(
				"""{"team":"${hers.key}","tickets":[
					{"ref":"a","title":"Alice helps herself"},
					{"ref":"b","title":"Twice over","parent":"a"}
				]}""",
				bearer(alice),
			),
		)
		assertTrue(refused.contains("not one of your teams"), "refused in the member's own words: $refused")
		assertEquals(0, inTeam(hers).size, "and neither ticket was filed")
	}

	/**
	 * The gate [McpController] applies before a service is reached at all — HTTP rather than
	 * a tool result, so a client can run a step-up flow off it.
	 */
	@Test
	fun `a read-only grant cannot reach kanso_plan, and is told which scope to ask for`() {
		val alice = user()
		val team = teamOf(alice, "Scoped plan")

		plan("""{"team":"${team.key}","tickets":[{"ref":"a","title":"Nope"}]}""", bearer(alice, setOf(OAuthScopes.READ)))
			.andExpect {
				status { isForbidden() }
				header { string("WWW-Authenticate", containsString(OAuthScopes.WRITE)) }
			}
		assertEquals(0, inTeam(team).size, "and it wrote nothing")
	}

	// --- the parent kanso_create_ticket could not set ------------------------

	/**
	 * The dead-end KAN-20 named, closed.
	 *
	 * Its own words: `kanso_create_ticket` could not set a parent, so an agent wanting a
	 * fourth part of an already-split ticket "would have no way to file one that is not an
	 * orphan" — which is why it withdrew its "split only once" rule rather than dead-end the
	 * caller. `TicketService.create` still cannot set a parent and did not have to: the tool
	 * creates and then calls `SubTicketService.setParent` in one transaction, which is what
	 * `SplitTicketTool` already did.
	 */
	@Test
	fun `kanso_create_ticket files a fourth part under a ticket that was already split`() {
		val alice = user()
		val team = teamOf(alice, "A fourth part")
		val big = textOf(
			call("kanso_create_ticket", """{"team":"${team.key}","title":"The big one"}""", bearer(alice)),
		).substringAfter("Created ").substringBefore(" —")
		call(
			"kanso_split_ticket",
			"""{"ticket":"$big","parts":[{"title":"One"},{"title":"Two"},{"title":"Three"}]}""",
			bearer(alice),
		).andExpect { status { isOk() } }

		val answer = textOf(
			call(
				"kanso_create_ticket",
				"""{"team":"${team.key}","title":"The fourth part","parent":"$big"}""",
				bearer(alice),
			),
		)
		assertTrue(answer.contains("part of $big"), "the answer says what it is part of: $answer")

		val parent = inTeam(team).first { it.identifier == big }
		val parts = inTeam(team).filter { it.ticket.parentId == parent.ticket.id }
		assertEquals(4, parts.size, "and the fourth is a part, not an orphan: ${parts.map { it.ticket.title }}")
		assertTrue(
			read(big, alice).contains("0 of 4 done"),
			"the parent counts four: ${read(big, alice)}",
		)
	}

	/**
	 * And a sub-ticket cannot be named as a parent, refused in the identifier the caller
	 * typed rather than the UUID `SubTicketService` answers with.
	 *
	 * Take `TicketStructure.refuseNesting` out of `CreateTicketTool` and this still refuses —
	 * rule 2 fires inside the transaction — but with hex in the message, so the last
	 * assertion is what proves the rewrite is there.
	 */
	@Test
	fun `kanso_create_ticket refuses a sub-ticket as a parent, in the identifier the caller typed`() {
		val alice = user()
		val team = teamOf(alice, "No third level")
		val big = textOf(
			call("kanso_create_ticket", """{"team":"${team.key}","title":"Top level"}""", bearer(alice)),
		).substringAfter("Created ").substringBefore(" —")
		call("kanso_split_ticket", """{"ticket":"$big","parts":[{"title":"The part"}]}""", bearer(alice))
			.andExpect { status { isOk() } }

		val parentId = inTeam(team).first { it.identifier == big }.ticket.id
		val child = inTeam(team).first { it.ticket.parentId == parentId }
		val before = inTeam(team).size

		val refused = textOf(
			call(
				"kanso_create_ticket",
				"""{"team":"${team.key}","title":"A third level","parent":"${child.identifier}"}""",
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

	private companion object {
		const val CLIENT = "claude-code"
	}
}

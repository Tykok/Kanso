package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.TicketFilters
import dev.kanso.repo.UserRepository
import dev.kanso.service.ActivityService
import dev.kanso.service.CustomFieldService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketFieldService
import dev.kanso.service.TicketService
import dev.kanso.service.ViewSortBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four tools, driven the whole way: a grant, a bearer token, [McpBearerFilter], and a
 * JSON-RPC `tools/call` on the other side.
 *
 * Never a tool object called directly. The question this suite exists to answer is
 * whether a *token* lands on its owner's rights, and a tool invoked in-process with a
 * hand-built `User` answers it by assumption — the argument [AgentRightsTest] makes at
 * length, applied one layer up. The standalone MockMvc is [McpProtocolTest]'s, for the
 * same reason it is there: this suite runs in dev mode, where the container's own answer
 * at `/api/mcp` is that there is no door.
 */
@Transactional
class McpToolsTest : PostgresTest() {

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
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var values: TicketFieldService
	@Autowired lateinit var jdbc: JdbcClient

	@Autowired lateinit var tools: List<McpTool>

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	private val mvc: MockMvc by lazy {
		mcpMvc(build, currentUser, tools, authorizations, clients, users, transactionManager)
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- fixtures ------------------------------------------------------------

	private fun user(role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "tools-${UUID.randomUUID()}@kanso.test",
		displayName = "Tools ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "T${UUID.randomUUID().toString().take(4).uppercase()}"

	/**
	 * A team somebody has claimed — the same precaution [AgentRightsTest] takes, and for
	 * the same reason: `TicketAccess`'s open-chain clause leaves a team nobody has joined
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

	// --- the call ------------------------------------------------------------

	private fun rpc(body: String, authorization: String?) = mvc.post(McpResource.PATH) {
		contentType = MediaType.APPLICATION_JSON
		content = body
		if (authorization != null) header("Authorization", authorization)
	}

	private fun call(tool: String, arguments: String, authorization: String) =
		rpc(callBody(tool, arguments), authorization)

	private fun titlesIn(team: Team): List<String> = tickets.list(
		teamId = team.id,
		includeDescendants = false,
		includeArchived = false,
		filters = TicketFilters(),
		sortBy = ViewSortBy.UPDATED,
		limit = 50,
		offset = 0,
	).map { it.ticket.title }

	private fun fileTicket(team: Team, owner: User, title: String): TicketDetail = tickets.create(
		actor = asSessionOf(owner),
		teamId = team.id,
		title = title,
		description = null,
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).also { SecurityContextHolder.clearContext() }

	// --- the surface ---------------------------------------------------------

	/**
	 * The list is the contract. A tool that exists and is not declared is unreachable; one
	 * declared and missing is a client's first call failing — so the names are asserted
	 * exactly, not counted.
	 */
	@Test
	fun `tools list answers the real surface, and every entry is callable as declared`() {
		rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", bearer(user())).andExpect {
			status { isOk() }
			jsonPath("$.result.tools.length()") { value(8) }
			// By index, because the order is sorted and stable — a client that caches the
			// list keyed on its content must not see it change between restarts. KAN-20 added
			// three and KAN-72 a fourth, and every one of them sorts into the middle rather
			// than onto the end, which is exactly why the assertion is by index and not by
			// membership: `kanso_plan` landed at 4 and pushed three tools along.
			jsonPath("$.result.tools[0].name") { value("kanso_create_ticket") }
			jsonPath("$.result.tools[1].name") { value("kanso_get_ticket") }
			jsonPath("$.result.tools[2].name") { value("kanso_link_tickets") }
			jsonPath("$.result.tools[3].name") { value("kanso_list_tickets") }
			jsonPath("$.result.tools[4].name") { value("kanso_plan") }
			jsonPath("$.result.tools[5].name") { value("kanso_split_ticket") }
			jsonPath("$.result.tools[6].name") { value("kanso_team_workload") }
			jsonPath("$.result.tools[7].name") { value("kanso_update_ticket") }
			// Every tool carries prose and an object schema. A tool with neither is one the
			// agent has to guess at, and guessing is what the four-tools-not-forty argument in
			// the spec exists to prevent.
			jsonPath("$.result.tools[0].description") { exists() }
			jsonPath("$.result.tools[0].inputSchema.type") { value("object") }
			jsonPath("$.result.tools[7].inputSchema.type") { value("object") }
			// The ones that write are declared as writing on the schema too, by requiring the
			// arguments they cannot invent — a `required` list nobody could satisfy would be
			// a tool an agent calls once and abandons.
			jsonPath("$.result.tools[0].inputSchema.required[0]") { value("team") }
			jsonPath("$.result.tools[7].inputSchema.required[0]") { value("ticket") }
			// And the one argument in the set that is a list of objects carries its element
			// shape, not a bare `array`: a client that validates locally has to be able to
			// refuse a part with no title before it sends the call.
			jsonPath("$.result.tools[5].inputSchema.properties.parts.items.type") { value("object") }
			jsonPath("$.result.tools[5].inputSchema.properties.parts.items.required[0]") { value("title") }
			jsonPath("$.result.tools[5].inputSchema.properties.parts.items.additionalProperties") { value(false) }
			// `kanso_plan` carries two of them, and the second is the one worth asserting: a
			// `links` array declared as a bare `array` would let a client send `{"form":…}`
			// and only find out from the server. `additionalProperties` reaching the element
			// is what refuses that locally.
			jsonPath("$.result.tools[4].inputSchema.properties.tickets.items.required[0]") { value("ref") }
			jsonPath("$.result.tools[4].inputSchema.properties.links.items.type") { value("object") }
			jsonPath("$.result.tools[4].inputSchema.properties.links.items.additionalProperties") { value(false) }
		}
	}

	@Test
	fun `an unknown tool is a JSON-RPC error naming the ones that exist`() {
		rpc(
			"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"kanso_delete_everything"}}""",
			bearer(user()),
		).andExpect {
			status { isOk() }
			jsonPath("$.error.code") { value(-32602) }
			jsonPath("$.error.message") { value(containsString("kanso_list_tickets")) }
			jsonPath("$.result") { doesNotExist() }
		}
	}

	/**
	 * Re-asserted here rather than left to [McpBearerFilterTest], because `tools/call` is
	 * the first thing on this endpoint that writes. A guard accidentally in front of
	 * something else would leave that suite green while this method answered strangers.
	 */
	@Test
	fun `an unauthenticated tools call is refused with the challenge, and writes nothing`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		rpc(callBody("kanso_create_ticket", """{"team":"${hers.key}","title":"Filed by nobody"}"""), null)
			.andExpect {
				status { isUnauthorized() }
				header { string("WWW-Authenticate", McpChallenge.header(OAuthScopes.ALL, "http://localhost")) }
			}

		assertEquals(emptyList(), titlesIn(hers), "a refused request must not leave a row behind")
	}

	// --- reading -------------------------------------------------------------

	@Test
	fun `list tickets answers its owner's team`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		fileTicket(hers, alice, "Fix the overlap warning")

		val answer = textOf(call("kanso_list_tickets", """{"team":"${hers.key}"}""", bearer(alice)))

		assertTrue(answer.contains("${hers.key}-1"), "the identifier a person would type: $answer")
		assertTrue(answer.contains("Fix the overlap warning"), "and the title: $answer")
	}

	/**
	 * The vocabulary, not a second dialect. `labelColour` is refused by
	 * `TicketFilterVocabulary` for `GET /api/tickets` and for a saved view, and the whole
	 * point of routing through it is that it is refused here too — with the same sentence,
	 * because there is only one that can be raised.
	 */
	@Test
	fun `list tickets refuses a filter nobody serves, naming the ones that are`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		val answer = textOf(
			call("kanso_list_tickets", """{"team":"${hers.key}","filters":{"labelColour":"indigo"}}""", bearer(alice)),
		)

		assertTrue(answer.contains("labelColour"), "the name that was refused: $answer")
		assertTrue(answer.contains("estimateMin"), "and the vocabulary that would have been served: $answer")
	}

	@Test
	fun `list tickets refuses an unknown team by naming the teams that exist`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		val answer = textOf(call("kanso_list_tickets", """{"team":"NOPE"}""", bearer(alice)))

		assertTrue(answer.contains("NOPE"), "the key that was asked for: $answer")
		assertTrue(answer.contains(hers.key), "and one that exists, so the retry is not a guess: $answer")
	}

	@Test
	fun `get ticket reads one by the identifier a person would type`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Timeline drags past its cycle")

		val answer = textOf(
			call("kanso_get_ticket", """{"ticket":"${filed.identifier}"}""", bearer(alice)),
		)

		// `!!` and not a fallback: this ticket was filed into a team, so it has an identifier,
		// and the day that stops being true this test should fail rather than quietly assert
		// something weaker.
		assertTrue(answer.contains(filed.identifier!!), "the identifier: $answer")
		assertTrue(answer.contains("Timeline drags past its cycle"), "the title: $answer")
		assertTrue(answer.contains("todo"), "and the status, in the vocabulary the wire uses: $answer")
	}

	/**
	 * The shape `V35` exists to have added *before* an agent pinned the old one.
	 *
	 * Every field the team defined, by name, whether or not this ticket holds a value — and
	 * the unset one is the reason it is worth the extra query. An agent that has seen
	 * `Customer: not set` knows the field exists and what to call it in
	 * `kanso_update_ticket`; printing only the filled ones would make a team's vocabulary
	 * discoverable exclusively on the tickets that already use it.
	 */
	@Test
	fun `get ticket names every custom field the team defined, set or not`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "A customer hit the sync bug")
		val severity = fields.define(asSessionOf(alice), hers.id, "Severity", "select", false, listOf("low", "high"))
		fields.define(asSessionOf(alice), hers.id, "Customer", "text", false, emptyList())
		values.setValues(asSessionOf(alice), filed.ticket.id, mapOf(severity.id.toString() to "high"))
		SecurityContextHolder.clearContext()

		val answer = textOf(
			call("kanso_get_ticket", """{"ticket":"${filed.identifier}"}""", bearer(alice)),
		)

		assertTrue(answer.contains("custom fields:"), "the section: $answer")
		assertTrue(answer.contains("Severity: high"), "the value it holds, by name: $answer")
		assertTrue(
			answer.contains("Customer: not set"),
			"a field with no value must still be named, or an agent cannot learn it exists: $answer",
		)
		// Never the id. That is the whole reason this tool pays for a definitions read.
		assertTrue(!answer.contains(severity.id.toString()), "a UUID leaked into an agent's page: $answer")
	}

	/**
	 * A team that has defined none draws no section at all — not an empty heading, which
	 * costs an agent tokens to read and rule out on every ticket in the instance.
	 */
	@Test
	fun `get ticket says nothing about custom fields when the team has none`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Nothing custom here")

		val answer = textOf(
			call("kanso_get_ticket", """{"ticket":"${filed.identifier}"}""", bearer(alice)),
		)

		assertTrue(!answer.contains("custom fields"), "an empty section was drawn anyway: $answer")
	}

	/**
	 * The write side, by the same names the read side prints — and case-insensitively,
	 * because `severity` and `Severity` are the same field to everybody except a map lookup.
	 */
	@Test
	fun `update ticket sets a custom field by name, and refuses one that is not there`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Timeline drags past its cycle")
		val severity = fields.define(asSessionOf(alice), hers.id, "Severity", "select", false, listOf("low", "high"))
		SecurityContextHolder.clearContext()

		val answer = textOf(
			call(
				"kanso_update_ticket",
				"""{"ticket":"${filed.identifier}","fields":{"severity":"high"}}""",
				bearer(alice),
			),
		)
		assertTrue(answer.startsWith("Updated"), "the write was reported: $answer")
		assertEquals(
			"high",
			values.valuesOf(asSessionOf(alice), filed.ticket.id)[severity.id],
			"the tool reported a write it did not make",
		)
		SecurityContextHolder.clearContext()

		// A name nobody defined ends the exchange in one more call rather than sending the
		// agent guessing — the discipline `McpErrors` describes.
		val refused = call(
			"kanso_update_ticket",
			"""{"ticket":"${filed.identifier}","fields":{"Blast radius":"wide"}}""",
			bearer(alice),
		)
		refused.andExpect { jsonPath("$.result.isError") { value(true) } }
		assertTrue(textOf(refused).contains("Severity"), "the refusal lists what does exist: ${textOf(refused)}")
	}

	/**
	 * The type reaches the agent's refusal too. An agent handed "400" learns nothing; one
	 * told "expects a number" fixes its own call.
	 */
	@Test
	fun `update ticket refuses a wrongly typed custom field with a sentence naming the type`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Sized wrong")
		fields.define(asSessionOf(alice), hers.id, "Impact", "number", false, emptyList())
		SecurityContextHolder.clearContext()

		val refused = call(
			"kanso_update_ticket",
			"""{"ticket":"${filed.identifier}","fields":{"Impact":"three"}}""",
			bearer(alice),
		)

		refused.andExpect { jsonPath("$.result.isError") { value(true) } }
		val text = textOf(refused)
		assertTrue(text.contains("Impact"), "the field: $text")
		assertTrue(text.contains("number"), "and what it expected: $text")
	}

	@Test
	fun `get ticket answers a ticket that is not there with a sentence, not a failure`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		val result = call("kanso_get_ticket", """{"ticket":"${hers.key}-404"}""", bearer(alice))
		result.andExpect {
			status { isOk() }
			jsonPath("$.result.isError") { value(true) }
		}
		assertTrue(textOf(result).contains("${hers.key}-404"), "and it names what was asked for")
	}

	// --- writing -------------------------------------------------------------

	@Test
	fun `create ticket files it in the team, with the number the service allocates`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		val answer = textOf(
			call(
				"kanso_create_ticket",
				"""{"team":"${hers.key}","title":"Filed by Claude","priority":"high"}""",
				bearer(alice),
			),
		)

		assertTrue(answer.contains("${hers.key}-1"), "the number came from the team's counter: $answer")
		assertEquals(listOf("Filed by Claude"), titlesIn(hers), "and the row is really there")
	}

	/**
	 * The premise of the branch, at the tool boundary: the same member is refused through
	 * both doors, and the two refusals carry the same sentence. Only one rule can produce
	 * that — which is what makes "an agent is not a new kind of user" a fact about the code
	 * rather than a paragraph in a spec.
	 */
	@Test
	fun `create ticket is refused in a team its owner may not touch, in the words the member is refused with`() {
		val alice = user()
		val bob = user()
		val his = teamOf(bob, "His")

		val toMember = runCatching {
			tickets.create(
				actor = asSessionOf(alice),
				teamId = his.id,
				title = "Filed by the member",
				description = null,
				status = TicketStatus.TODO,
				priority = TicketPriority.NONE,
				start = null,
				due = null,
				projectId = null,
				assigneeIds = emptyList(),
				docIds = emptyList(),
			)
		}.exceptionOrNull()
		SecurityContextHolder.clearContext()

		val toAgent = textOf(
			call("kanso_create_ticket", """{"team":"${his.key}","title":"Filed by an agent"}""", bearer(alice)),
		)

		assertTrue(toMember is AccessDeniedException, "the fixture refusal is the rule's own")
		assertTrue(
			toAgent.contains(toMember.message!!),
			"the agent is turned away by the rule that turns its owner away: $toAgent",
		)
		assertEquals(emptyList(), titlesIn(his), "and nothing was filed on the way to being refused")
	}

	/**
	 * A misspelled argument is a refusal, not a silent drop — the same rule
	 * `TicketFilterVocabulary.SERVED` enforces on filters, for the same reason: a create
	 * that quietly ignored `assignee` would answer "done" about a ticket nobody was given.
	 */
	@Test
	fun `create ticket refuses an argument nobody serves rather than ignoring it`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		val answer = textOf(
			call(
				"kanso_create_ticket",
				"""{"team":"${hers.key}","title":"Filed sloppily","assignee":"alice@kanso.test"}""",
				bearer(alice),
			),
		)

		assertTrue(answer.contains("assignee"), "the argument that was refused: $answer")
		assertEquals(emptyList(), titlesIn(hers), "and the ticket was not filed anyway")
	}

	@Test
	fun `update ticket moves the status and hands the work over in one call`() {
		val alice = user()
		val bob = user()
		val hers = teamOf(alice, "Hers")
		teamRepo.addMember(hers.id, bob.id, MemberRole.MEMBER)
		val filed = fileTicket(hers, alice, "Work to hand over")

		call(
			"kanso_update_ticket",
			"""{"ticket":"${filed.identifier}","status":"in_progress","assignees":["${bob.email}"]}""",
			bearer(alice),
		).andExpect {
			status { isOk() }
			jsonPath("$.result.isError") { value(false) }
		}

		val after = tickets.get(filed.ticket.id)
		assertEquals(TicketStatus.IN_PROGRESS, after.ticket.status, "the status moved")
		assertEquals(listOf(bob.id), after.assigneeIds, "and the assignee with it")
	}

	/**
	 * The assertion that matters most in this file.
	 *
	 * A request naming a good status and an assignee that does not exist must land
	 * *neither* — a tool that half-applies is worse than a tool that refuses, because the
	 * agent reads a failure and the backlog holds a change nobody asked for. The two edits
	 * go through one `TicketService.patch`, so there is one transaction and no order in
	 * which half of it survives.
	 */
	@Test
	fun `update ticket refuses a request it cannot fully apply, and applies none of it`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Work that must not move")

		val answer = textOf(
			call(
				"kanso_update_ticket",
				"""{"ticket":"${filed.identifier}","status":"done","assignees":["ghost@kanso.test"]}""",
				bearer(alice),
			),
		)

		assertTrue(answer.contains("ghost@kanso.test"), "the half that could not be read is named: $answer")
		val after = tickets.get(filed.ticket.id)
		assertEquals(TicketStatus.TODO, after.ticket.status, "the half that could have been applied was not")
		assertEquals(emptyList(), after.assigneeIds, "and neither was the other")
	}

	@Test
	fun `update ticket is refused on a ticket its owner may not touch`() {
		val alice = user()
		val bob = user()
		val his = teamOf(bob, "His")
		val filed = fileTicket(his, bob, "Bob's work")

		val answer = textOf(
			call("kanso_update_ticket", """{"ticket":"${filed.identifier}","status":"done"}""", bearer(alice)),
		)

		assertTrue(answer.contains("not one of your teams"), "the rule's own sentence: $answer")
		assertEquals(TicketStatus.TODO, tickets.get(filed.ticket.id).ticket.status, "and nothing moved")
	}

	/**
	 * A read grant reaching a writing tool is the one refusal that stays HTTP, because it
	 * is the one a client can *fix*: a 403 naming the scope starts a step-up flow, where a
	 * tool result saying "no" only teaches the agent to rephrase.
	 */
	@Test
	fun `a read-only grant cannot reach a writing tool, and is told which scope to ask for`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		call(
			"kanso_create_ticket",
			"""{"team":"${hers.key}","title":"Filed on a read grant"}""",
			bearer(alice, setOf(OAuthScopes.READ)),
		).andExpect {
			status { isForbidden() }
			header {
				string("WWW-Authenticate", McpChallenge.insufficientScope(OAuthScopes.WRITE))
			}
		}

		assertEquals(emptyList(), titlesIn(hers), "and nothing was written before the refusal")
	}

	@Test
	fun `a read-only grant still reaches a reading tool`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		fileTicket(hers, alice, "Readable on a read grant")

		val answer = textOf(
			call("kanso_list_tickets", """{"team":"${hers.key}"}""", bearer(alice, setOf(OAuthScopes.READ))),
		)
		assertTrue(answer.contains("Readable on a read grant"), "a read scope reads: $answer")
	}

	// --- provenance ----------------------------------------------------------

	/**
	 * Who typed it and what typed it, both recorded, and they are two columns because they
	 * are two facts. `V19__activity_via_client_id_target.sql` moved the foreign key onto the
	 * public `client_id` precisely so this row could be written; before this branch nothing
	 * wrote it, and `AgentRightsTest` carried a tripwire saying so.
	 */
	@Test
	fun `a write through a tool records the member who owns it and the client that typed it`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")

		call("kanso_create_ticket", """{"team":"${hers.key}","title":"Filed by Claude, as Alice"}""", bearer(alice))
			.andExpect { status { isOk() } }

		val filed = tickets.list(
			teamId = hers.id,
			includeDescendants = false,
			includeArchived = false,
			filters = TicketFilters(),
			sortBy = ViewSortBy.UPDATED,
			limit = 10,
			offset = 0,
		).single()

		val entry = activity.forEntity(ActivityEntity.TICKET, filed.ticket.id).single()
		assertEquals(alice.id, entry.actor?.id, "the token acts as its owner, so the history says its owner")

		val recorded = jdbc
			.sql("SELECT via_client_id FROM activity WHERE id = :id")
			.param("id", entry.id)
			.query(String::class.java)
			.optional()
		assertEquals(CLIENT, recorded.orElse(null), "and the application that typed it is durable, not request-scoped")
	}

	/** The other half of the same fact: a person's own write names no client. */
	@Test
	fun `a write through a session records no client, because there was none`() {
		val alice = user()
		val hers = teamOf(alice, "Hers")
		val filed = fileTicket(hers, alice, "Typed by Alice herself")

		val entry = activity.forEntity(ActivityEntity.TICKET, filed.ticket.id).single()
		val recorded = jdbc
			.sql("SELECT via_client_id FROM activity WHERE id = :id")
			.param("id", entry.id)
			.query(String::class.java)
			.optional()
		assertTrue(recorded.isEmpty, "a column filled for everybody would say nothing about anybody")
	}

	private companion object {
		const val CLIENT = "claude-code"
	}
}

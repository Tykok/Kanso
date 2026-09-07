package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoAgentUser
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
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A reader's agent reads, and does not write — and nothing in `mcp/` had to be told so.
 *
 * `AgentRightsTest` makes the branch-level version of this argument: an agent is not a new
 * kind of user, so `TicketAccess` applies to it unchanged. This file is that argument
 * carried onto the read-only seat, and the load-bearing detail is the **scope**: every
 * grant below is issued with `kanso:write`. `McpController`'s scope gate is therefore
 * satisfied and cannot be the thing refusing. What refuses is the seat, one layer further
 * in, where every writing tool eventually reaches a service that reaches `TicketAccess`.
 *
 * That is the answer to "does the MCP refusal fall out of the same rule": it does, and the
 * proof is that `McpTool.writes` was not touched. Those two questions stay separate on
 * purpose — `writes` is about the *grant* ("did this connection ask for write access"),
 * the seat is about the *person* ("may this person write at all") — and a viewer's agent
 * that had been refused by the scope gate would have told us nothing about the second.
 */
@Transactional
class ViewerAgentTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var current: CurrentUser
	@Autowired lateinit var tools: List<McpTool>

	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService
	@Autowired lateinit var transactionManager: PlatformTransactionManager

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	/** `oidc`, for the reason `AgentRightsTest` gives: this is an instance that has a door. */
	private val filter by lazy { McpBearerFilter(authorizations, clients, users, transactionManager, authMode = "oidc") }

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "agent-seat-${UUID.randomUUID()}@kanso.test",
		displayName = "Seat ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private val admin: User by lazy { user(InstanceRole.ADMIN) }

	private fun teamWith(member: User): Team {
		val team = teams.create(admin, "Agents ${UUID.randomUUID()}", "A${UUID.randomUUID().toString().take(4).uppercase()}", null)
		teamRepo.addMember(team.id, member.id, MemberRole.MEMBER)
		return team
	}

	/** The `User` a controller sees on a session request from this person. */
	private fun asPerson(person: User): User {
		val principal = KansoLocalUser(person.id, person.email, person.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
		return current.require()
	}

	/**
	 * The `User` a tool is handed on a request carrying this person's token.
	 *
	 * Reached the long way round — a real grant, a real bearer, a real pass through
	 * [McpBearerFilter] — because the question is whether a *token* lands on its owner's
	 * seat, and a hand-built `KansoAgentUser` would answer it by assumption.
	 */
	private fun asAgent(person: User): User {
		val token = grants.issue(person, OAuthScopes.ALL.toSet(), clientId = CLIENT)
		val request = MockHttpServletRequest("POST", McpResource.PATH)
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
		filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

		val principal = SecurityContextHolder.getContext().authentication?.principal
		assertTrue(principal is KansoAgentUser, "the grant was refused before this test's own assertions could run")
		assertEquals(person.id, principal.kansoUserId, "the principal standing is this person's, not a leftover")
		return current.require()
	}

	private fun tool(name: String): McpTool =
		tools.firstOrNull { it.name == name } ?: error("no tool named $name; this server serves ${tools.map { it.name }}")

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	/**
	 * Every writing tool, refused, with a `kanso:write` grant in hand.
	 *
	 * Driven off `McpTool.writes` rather than off a list of tool names: a fifth tool added
	 * next month arrives here on its own, and if it writes it has to be refused. A tool that
	 * declares `writes = true` and is *not* refused is either a tool that skipped the
	 * services or a hole in the seat, and both are the same bug wearing different clothes.
	 */
	@Test
	fun `a viewer's agent is refused by every writing tool, though the grant says write`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teamWith(viewer)
		val existing = ticketIn(team)
		val other = ticketIn(team)
		val writing = tools.filter { it.writes }
		assertTrue(writing.isNotEmpty(), "no writing tools were found; the sweep below would assert nothing")

		val agent = asAgent(viewer)
		for (writer in writing) {
			val refused = assertFailsWith<AccessDeniedException>("`${writer.name}` let a reader's agent write") {
				writer.call(agent, argumentsFor(writer.name, team, existing, other))
			}
			assertEquals(
				TicketAccess.READS_NOT_WRITES,
				refused.message,
				"`${writer.name}` refused with words of its own, which means `mcp/` grew a rule of its own",
			)
		}
	}

	/**
	 * The two doors, compared.
	 *
	 * Not "the agent is refused" on its own — a hard-coded throw in `mcp/` would satisfy
	 * that and would be exactly the second answer this design exists to avoid. The same
	 * person is refused twice, once through a session and once through a token, and the two
	 * refusals must be the same type carrying the same sentence. Only one rule can do that.
	 */
	@Test
	fun `the agent is refused with the same words its owner is refused with`() {
		val viewer = user(InstanceRole.VIEWER)
		val team = teamWith(viewer)
		val existing = ticketIn(team)
		val other = ticketIn(team)
		val writer = tool("kanso_create_ticket")

		val toPerson = assertFailsWith<AccessDeniedException> {
			writer.call(asPerson(viewer), argumentsFor(writer.name, team, existing, other))
		}
		SecurityContextHolder.clearContext()
		val toAgent = assertFailsWith<AccessDeniedException> {
			writer.call(asAgent(viewer), argumentsFor(writer.name, team, existing, other))
		}

		assertEquals(toPerson::class, toAgent::class, "a refusal of a different type is a second rule")
		assertEquals(toPerson.message, toAgent.message, "the token is a way in, not a way around the seat")
	}

	/**
	 * And the half that must keep working, or the seat is worthless to an agent.
	 *
	 * Compared against a member's answer rather than against a literal: a viewer's agent
	 * that saw *less* would not be the product, and one that saw more would be a leak.
	 */
	@Test
	fun `a viewer's agent reads exactly what a member's agent reads`() {
		val viewer = user(InstanceRole.VIEWER)
		val member = user(InstanceRole.MEMBER)
		val team = teamWith(member)
		ticketIn(team)

		SecurityContextHolder.clearContext()
		val memberSaw = tool("kanso_list_tickets").call(asAgent(member), mapOf("team" to team.key))
		SecurityContextHolder.clearContext()
		val viewerSaw = tool("kanso_list_tickets").call(asAgent(viewer), mapOf("team" to team.key))

		assertTrue(memberSaw.contains(READABLE), "the fixture is worth nothing if the read finds nothing")
		assertEquals(memberSaw, viewerSaw, "reads are the reason the seat exists; they are not narrowed")
	}

	/** A ticket somebody who may write has already filed, for a reader to be refused on. */
	private fun ticketIn(team: Team): String = tickets.create(
		actor = admin,
		teamId = team.id,
		title = READABLE,
		description = null,
		status = DefaultStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).identifier!!

	/**
	 * Arguments valid enough to reach the service.
	 *
	 * The refusal has to come from the seat and not from the argument reader, so every tool
	 * is called with something `McpArguments` accepts and a ticket that really exists. A
	 * `NotFoundException` from any of these would be the test lying to itself: it would look
	 * like a refusal and would say nothing at all about who may write.
	 */
	private fun argumentsFor(name: String, team: Team, existing: String, other: String): Map<String, Any?> =
		when (name) {
			"kanso_create_ticket" -> mapOf("team" to team.key, "title" to "Filed by an agent")
			"kanso_update_ticket" -> mapOf("ticket" to existing, "status" to "done")
			// Two different tickets, not one twice: `TicketLinkService` refuses a self-link, and
			// a refusal that came from *that* would look exactly like the one this test is
			// asserting while saying nothing about the seat.
			"kanso_link_tickets" -> mapOf("from" to existing, "to" to other, "type" to "relates")
			"kanso_split_ticket" -> mapOf("ticket" to existing, "parts" to listOf(mapOf("title" to "A part")))
			// Two tickets and one edge between them, so the plan is one `TicketAccess` would
			// have to refuse rather than one `PlanDraft` refuses first. A single ticket with no
			// links would still reach the seat, but it would stop exercising the two writes a
			// plan makes past the first — and those are the ones that could have skipped it.
			"kanso_plan" -> mapOf(
				"team" to team.key,
				"tickets" to listOf(
					mapOf("ref" to "one", "title" to "Planned by an agent"),
					mapOf("ref" to "two", "title" to "And its part", "parent" to "one"),
				),
				"links" to listOf(mapOf("from" to "one", "to" to "two", "type" to "relates")),
			)

			else -> error("`$name` writes but this test does not know how to call it; teach it here")
		}

	private companion object {
		const val CLIENT = "claude-code"

		/** Distinctive enough that finding it in a tool's prose output means something. */
		const val READABLE = "Work a reader may read"
	}
}

package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.DefaultStatus
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.CommentService
import dev.kanso.service.CreateComment
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketService
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

/**
 * Triage, driven by an agent — `KAN-30`.
 *
 * The ticket asked for "smart triage and summaries", and the two loudest docstrings in
 * this package refuse it as written: `TeamWorkloadTool` says a ranking Kanso shipped
 * would be "arithmetic dressed as judgement", and `PlanTool` says Kanso embeds no LLM.
 * Both are right, and both leave the same hole — the judgement is in the conversation and
 * the conversation has no hands. An agent could not read a ticket's thread, could not see
 * what was waiting for a decision, and could not record one.
 *
 * So: no intelligence here either. A read that names the queue, a read that includes the
 * case, and a write that records the ruling a person just agreed to — through
 * `TriageService.decide`, so the cycle, the mirror and the activity behave exactly as they
 * do when the same decision is made on the screen.
 *
 * Driven through the filter and the token like every other suite in this package, for the
 * reason `McpDriver` gives: a suite that called the tools in-process would be arguing that
 * they work for somebody who got past no door.
 */
@Transactional
class McpTriageTest : PostgresTest() {

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
	@Autowired lateinit var comments: CommentService

	@Autowired lateinit var tools: List<McpTool>

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	private val mvc: MockMvc by lazy {
		mcpMvc(build, currentUser, tools, authorizations, clients, users, transactionManager)
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- fixtures ------------------------------------------------------------

	private fun user(role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "triage-${UUID.randomUUID()}@kanso.test",
		displayName = "Triage ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "R${UUID.randomUUID().toString().take(4).uppercase()}"

	/** Claimed by somebody, so a refusal cannot pass for the open-chain reason. */
	private fun teamOf(owner: User): Team {
		val admin = user(InstanceRole.ADMIN)
		val team = teams.create(asSessionOf(admin), "Triage ${UUID.randomUUID()}", key(), null)
		teamRepo.addMember(team.id, owner.id, MemberRole.MEMBER)
		SecurityContextHolder.clearContext()
		return team
	}

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

	private fun fileTicket(team: Team, owner: User, title: String): TicketDetail = tickets.create(
		actor = asSessionOf(owner),
		teamId = team.id,
		title = title,
		description = null,
		status = "todo",
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	).also { SecurityContextHolder.clearContext() }

	private fun bearer(member: User, scopes: Set<String> = OAuthScopes.ALL.toSet()): String =
		"Bearer " + grants.issue(member, scopes, clientId = "claude-code")

	private fun call(tool: String, arguments: String, authorization: String) =
		mvc.post(McpResource.PATH) {
			contentType = MediaType.APPLICATION_JSON
			content = callBody(tool, arguments)
			header("Authorization", authorization)
		}

	// --- the queue -----------------------------------------------------------

	@Test
	fun `the queue names what is waiting, by the identifier the write tool takes back`() {
		val member = user()
		val team = teamOf(member)
		val waiting = fileTicket(team, member, "Export CSV times out")

		call("kanso_triage_queue", """{"team":"${team.key}"}""", bearer(member))
			.andExpect {
				status { isOk() }
				content { string(containsString(waiting.identifier!!)) }
				content { string(containsString("Export CSV times out")) }
			}
	}

	@Test
	fun `a ticket already ruled on is out of the queue, and its neighbour is still in it`() {
		val member = user()
		val team = teamOf(member)
		val decided = fileTicket(team, member, "Already decided")
		fileTicket(team, member, "Still waiting")

		call(
			"kanso_triage",
			"""{"ticket":"${decided.identifier}","decision":"backlogged"}""",
			bearer(member),
		).andExpect { status { isOk() } }

		call("kanso_triage_queue", """{"team":"${team.key}"}""", bearer(member))
			.andExpect {
				status { isOk() }
				// Both halves, because the absence alone would also pass for a queue that
				// answered nothing at all — which is what a broken team lookup looks like.
				content { string(containsString("Still waiting")) }
				content { string(org.hamcrest.Matchers.not(containsString("Already decided"))) }
			}
	}

	// --- the ruling ----------------------------------------------------------

	@Test
	fun `a decision lands through the same door a person's does`() {
		val member = user()
		val team = teamOf(member)
		val waiting = fileTicket(team, member, "Backlog this")

		call(
			"kanso_triage",
			"""{"ticket":"${waiting.identifier}","decision":"backlogged"}""",
			bearer(member),
		).andExpect { status { isOk() } }

		// `TriageService.decide` patches through `TicketService`, which is what makes the
		// mirror, the activity and the dependency cascade behave. Asserted on the status
		// because that is the consequence this decision carries.
		assertEquals(
			"backlog",
			tickets.get(asSessionOf(member), waiting.ticket.id).ticket.status,
		)
	}

	@Test
	fun `a second ruling is refused in the words a person is refused with`() {
		val member = user()
		val team = teamOf(member)
		val waiting = fileTicket(team, member, "Twice")
		val token = bearer(member)

		call("kanso_triage", """{"ticket":"${waiting.identifier}","decision":"closed"}""", token)
			.andExpect { status { isOk() } }

		call("kanso_triage", """{"ticket":"${waiting.identifier}","decision":"backlogged"}""", token)
			.andExpect {
				status { isOk() }
				content { string(containsString("already triaged")) }
			}
	}

	@Test
	fun `a duplicate names what it duplicates, and nothing else may`() {
		val member = user()
		val team = teamOf(member)
		val original = fileTicket(team, member, "The original")
		val copy = fileTicket(team, member, "The copy")
		val token = bearer(member)

		call("kanso_triage", """{"ticket":"${copy.identifier}","decision":"duplicate"}""", token)
			.andExpect {
				status { isOk() }
				content { string(containsString("duplicate names the ticket it duplicates")) }
			}

		call(
			"kanso_triage",
			"""{"ticket":"${copy.identifier}","decision":"duplicate","duplicateOf":"${original.identifier}"}""",
			token,
		).andExpect {
			status { isOk() }
			content { string(containsString(original.identifier!!)) }
		}
	}

	@Test
	fun `a read-only grant cannot rule`() {
		val member = user()
		val team = teamOf(member)
		val waiting = fileTicket(team, member, "Not yours to decide")

		call(
			"kanso_triage",
			"""{"ticket":"${waiting.identifier}","decision":"closed"}""",
			bearer(member, setOf(OAuthScopes.READ)),
		).andExpect {
			// 403 at the filter and not a refusal in the envelope: a scope a grant does not
			// carry is not a tool declining, it is a door. `McpBearerFilter` answers it
			// before `TriageTool.call` is ever reached, which is where `writes = true`
			// earns its keep.
			status { isForbidden() }
		}
	}

	// --- the case ------------------------------------------------------------

	@Test
	fun `the expensive read carries the thread, so the case can be read at all`() {
		val member = user()
		val team = teamOf(member)
		val subject = fileTicket(team, member, "Export CSV times out")
		comments.create(
			asSessionOf(member),
			CreateComment(ticketId = subject.ticket.id, body = "Happens over 40k rows"),
		)
		SecurityContextHolder.clearContext()

		// `kanso_get_ticket` excluded comments on the rule that what is in it is what the
		// writing tools need. `kanso_triage` is a writing tool whose argument is a
		// judgement about the case, and the case is the thread — the same move `KAN-20`
		// made for dependencies when `kanso_link_tickets` arrived.
		call("kanso_get_ticket", """{"ticket":"${subject.identifier}"}""", bearer(member))
			.andExpect {
				status { isOk() }
				content { string(containsString("Happens over 40k rows")) }
				// By email, like every other person this server prints: what a reading tool
				// prints is what a writing tool takes back.
				content { string(containsString(member.email)) }
			}
	}
}

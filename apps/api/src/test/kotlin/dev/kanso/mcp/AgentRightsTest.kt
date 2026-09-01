package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.CurrentUser
import dev.kanso.auth.KansoAgentUser
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.ActivityEntity
import dev.kanso.domain.ActivityKind
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.Team
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.ActivityService
import dev.kanso.service.TeamService
import dev.kanso.service.TicketDetail
import dev.kanso.service.TicketService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The premise of the whole branch, asserted: an agent is not a new kind of user.
 *
 * If this test ever needs a special case to pass, the design has grown a second answer
 * to "who may touch this ticket" — and the spec's opening paragraph says what happens
 * next: the two answers disagree, and one of them is wrong in production.
 *
 * Every test here reaches its actor the long way round — a grant is issued, a request
 * carrying its token is run through [McpBearerFilter], and the `User` handed to the
 * service is whatever `CurrentUser` then resolves off the security context. Constructing
 * a `KansoAgentUser` by hand would have been three lines shorter and would have asserted
 * nothing: the question is precisely whether a *token* lands on the member's own row,
 * and a hand-built principal answers it by assumption.
 *
 * `TicketAccess` is never named below, and that is deliberate — it is what these tests
 * are here to catch running, so calling it directly would be marking its own homework.
 */
@Transactional
class AgentRightsTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var activity: ActivityService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var current: CurrentUser
	@Autowired lateinit var jdbc: JdbcClient

	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var authorizations: OAuth2AuthorizationService
	@Autowired lateinit var consents: OAuth2AuthorizationConsentService
	@Autowired lateinit var transactionManager: PlatformTransactionManager

	private val grants by lazy { TestGrants(clients, authorizations, consents) }

	/**
	 * `oidc`, though the suite runs in dev mode. `McpBearerFilterTest` asserts the dev-mode
	 * refusal on the container's own filter; what is under test here is what happens on an
	 * instance that has a door, so this one is built with the mode that opens it.
	 */
	private val filter by lazy { McpBearerFilter(authorizations, clients, users, transactionManager, authMode = "oidc") }

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "rights-${UUID.randomUUID()}@kanso.test",
		displayName = "Rights ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "R${UUID.randomUUID().toString().take(4).uppercase()}"

	/**
	 * A team somebody has claimed.
	 *
	 * `TeamService.create` does not enrol its creator, and `TicketAccess`'s open-chain
	 * clause leaves a team nobody has joined editable by everyone — so a team created and
	 * left empty would refuse nothing, and every refusal asserted below would pass for the
	 * wrong reason.
	 */
	private fun teamOf(owner: User, admin: User, name: String): Team {
		val team = teams.create(admin, name, key(), null)
		teamRepo.addMember(team.id, owner.id, MemberRole.MEMBER)
		return team
	}

	/** The `User` a controller would be handed on a session request from this member. */
	private fun asMember(member: User): User {
		val principal = KansoLocalUser(member.id, member.email, member.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
		return current.require()
	}

	/**
	 * The `User` a controller would be handed on a request carrying this member's token.
	 *
	 * Scopes default to everything the instance grants, so that a refusal below can only
	 * ever be about who the owner is. A test where the agent was also short of a scope
	 * would pass whether or not team membership was consulted.
	 */
	private fun asAgent(
		member: User,
		scopes: Set<String> = OAuthScopes.ALL.toSet(),
		clientId: String = CLIENT,
	): User {
		val token = grants.issue(member, scopes, clientId = clientId)
		val request = MockHttpServletRequest("POST", McpResource.PATH)
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
		filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

		val principal = SecurityContextHolder.getContext().authentication?.principal
		assertTrue(
			principal is KansoAgentUser,
			"the grant was refused before any of this test's own assertions could run",
		)
		// Not paranoia about the filter — `McpBearerFilterTest` owns that. It is that a
		// second call to this helper without a clear in between would leave the *previous*
		// member standing, and every test below would go on asserting about the wrong
		// person in a file whose whole job is proving who acts as whom.
		assertEquals(
			member.id,
			principal.kansoUserId,
			"the principal now standing is this member's, not one left over from a previous call",
		)
		return current.require()
	}

	private fun createIn(team: Team, actor: User, title: String): TicketDetail = tickets.create(
		actor = actor,
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
	)

	private fun titlesIn(team: Team): List<String> = tickets.search(
		teamId = team.id,
		includeDescendants = false,
		projectId = null,
		statuses = emptyList(),
		assigneeId = null,
		includeArchived = false,
		limit = 50,
		offset = 0,
	).map { it.ticket.title }

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	/**
	 * The assertion the branch exists to make.
	 *
	 * Not "the agent is refused" — that much a hard-coded `throw` in the MCP layer would
	 * satisfy, and it would be a second answer to a question `TicketAccess` already
	 * answers. The same member is refused twice, once through each door, and the two
	 * refusals are compared: same type, same sentence. Only one rule can produce that.
	 */
	@Test
	fun `an agent's write into another team is refused with the same words its owner is refused with`() {
		val admin = user(InstanceRole.ADMIN)
		val member = user(InstanceRole.MEMBER)
		val theirs = teamOf(user(InstanceRole.MEMBER), admin, "Theirs")

		val toMember = assertFailsWith<AccessDeniedException> {
			createIn(theirs, asMember(member), "Filed by the member")
		}
		val toAgent = assertFailsWith<AccessDeniedException> {
			createIn(theirs, asAgent(member), "Filed by an agent")
		}

		assertEquals(
			toMember::class,
			toAgent::class,
			"a refusal of a different type is a second rule, and two rules eventually disagree",
		)
		assertEquals(
			toMember.message,
			toAgent.message,
			"the agent is turned away by the rule that turns its owner away, not by one of its own",
		)
	}

	/**
	 * Two teams, two members, one grant. The grant is member A's, and the thing it must
	 * not reach is member B's work.
	 *
	 * The write into A's own team is not padding: without it this test would still pass on
	 * an implementation that refused agents everything, which is a different bug and not
	 * the one being pinned.
	 */
	@Test
	fun `an agent reaches its owner's team and stops at the other member's`() {
		val admin = user(InstanceRole.ADMIN)
		val alice = user(InstanceRole.MEMBER)
		val bob = user(InstanceRole.MEMBER)
		val hers = teamOf(alice, admin, "Hers")
		val his = teamOf(bob, admin, "His")

		val agent = asAgent(alice)

		val filed = createIn(hers, agent, "Work Alice may file")
		assertEquals(hers.id, filed.ticket.teamId, "the grant carries her own membership, and that is not nothing")

		assertFailsWith<AccessDeniedException>(
			"a token is a way in, not a way around the team the work belongs to",
		) {
			createIn(his, agent, "Work Alice may not file")
		}
	}

	/**
	 * Reads are open in Kanso — `architecture.md` says so, and `TicketService.search` takes
	 * no actor to prove it. So the honest assertion is not that the agent is blocked, which
	 * would be a rule Kanso does not have; it is that the agent sees neither more nor less
	 * than the member does. A widened read would fail here, and so would a narrowed one.
	 */
	@Test
	fun `an agent reads exactly what its owner reads, no wider and no narrower`() {
		val admin = user(InstanceRole.ADMIN)
		val alice = user(InstanceRole.MEMBER)
		val bob = user(InstanceRole.MEMBER)
		val his = teamOf(bob, admin, "His")
		createIn(his, asMember(bob), "Bob's visible work")

		// The member's own reads first, so the agent's are compared against a live answer
		// rather than a literal this test made up.
		val ownerSaw = titlesIn(his)
		SecurityContextHolder.clearContext()
		asAgent(alice)
		val agentSaw = titlesIn(his)

		assertEquals(listOf("Bob's visible work"), ownerSaw, "the fixture is worth nothing if the read finds nothing")
		assertEquals(ownerSaw, agentSaw, "reads are the member's reads — the token neither opens nor closes anything")
	}

	/**
	 * Who typed it, and what typed it, are two different questions with two different
	 * answers — and the first one is the member.
	 *
	 * A row attributed to the client, to an anonymous actor or to nobody would each break
	 * the same promise: the consent screen tells a member their agent acts *as them*, and
	 * the feed is where that either turns out to be true or does not.
	 *
	 * The second question now has an answer on disk too. This test used to end on a
	 * tripwire asserting `activity.via_client_id` was *empty* — a gap pinned rather than
	 * described, with instructions to rename the test and delete the assertion on the day
	 * somebody filled it in. `ActivityService.clientTyping` is that day: the column is
	 * written from the principal `McpBearerFilter` leaves standing, so provenance outlives
	 * the request that carried it.
	 */
	@Test
	fun `a write through a grant is the member's own in the log, with the client recorded alongside`() {
		val admin = user(InstanceRole.ADMIN)
		val alice = user(InstanceRole.MEMBER)
		val hers = teamOf(alice, admin, "Hers")

		val agent = asAgent(alice)
		val filed = createIn(hers, agent, "Filed by Claude, as Alice")

		val entry = activity.forEntity(ActivityEntity.TICKET, filed.ticket.id).single()
		assertEquals(ActivityKind.CREATED, entry.kind, "the row this test reads has to be the one the create wrote")
		val actor = assertNotNull(entry.actor, "an unattributed change is exactly what a token must not produce")
		assertEquals(alice.id, actor.id, "the token acts as its owner, so the history says its owner")
		assertEquals(alice.email, actor.email, "and the feed will name her, not the application")

		// Provenance reaches the request. `Principals.kt` says the client travels "so the
		// activity feed can say which application typed a change", and this is the way in.
		val principal = SecurityContextHolder.getContext().authentication?.principal as KansoAgentUser
		assertEquals(CLIENT, principal.clientId, "which application typed it is not lost on the way in")
		assertEquals(alice.id, principal.kansoUserId, "and it is carried alongside her, not instead of her")

		// And it reaches disk, which is the half that used to be missing. The value is the
		// *public* `client_id`, which is what `V19__activity_via_client_id_target.sql` moved
		// the foreign key onto for exactly this write — the surrogate `V18` first pointed at
		// is a key no service layer ever holds.
		val recorded = jdbc
			.sql("SELECT via_client_id FROM activity WHERE id = :id")
			.param("id", entry.id)
			.query(String::class.java)
			.optional()
		assertEquals(
			CLIENT,
			recorded.orElse(null),
			"provenance is durable: the feed can name the application long after the request ended",
		)
	}

	/**
	 * The scopes on a grant say what an agent may attempt. They do not say who its owner
	 * is, and an instance role is the second question — which is why a `kanso:write` grant
	 * held on behalf of a plain member still cannot configure the instance.
	 *
	 * The admin's agent succeeding is what stops this from being a test that a token can
	 * never create a team.
	 */
	@Test
	fun `an agent is an admin exactly when its owner is`() {
		val member = user(InstanceRole.MEMBER)
		val admin = user(InstanceRole.ADMIN)

		val toMember = assertFailsWith<AccessDeniedException> {
			teams.create(asMember(member), "Refused to the member", key(), null)
		}
		SecurityContextHolder.clearContext()
		val toAgent = assertFailsWith<AccessDeniedException> {
			teams.create(asAgent(member), "Refused to the agent", key(), null)
		}
		assertEquals(
			toMember.message,
			toAgent.message,
			"a write scope is permission to act as the member, not permission to outrank them",
		)

		SecurityContextHolder.clearContext()
		val created = teams.create(asAgent(admin), "Allowed to the admin's agent", key(), null)
		assertEquals(
			"Allowed to the admin's agent",
			created.name,
			"the owner's role travels through the token too — the rule is unchanged, not inverted",
		)
	}

	private companion object {
		const val CLIENT = "claude-code"
	}
}

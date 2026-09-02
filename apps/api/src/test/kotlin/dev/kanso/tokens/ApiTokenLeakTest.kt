package dev.kanso.tokens

import dev.kanso.MockMvcTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TicketAccess
import dev.kanso.service.TicketService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The test this slice exists to pass, and it is `PublicLeakTest`'s shape aimed at a
 * different class of caller.
 *
 * That file's move is the one worth copying: assert against what actually reaches the
 * wire, through the whole stack, because the leak that matters is the one a later refactor
 * introduces without anybody opening this file. So nothing here calls a service directly —
 * every request goes through `SecurityConfig`'s real filter chain with a real
 * `Authorization` header, and what comes back is read as bytes.
 *
 * Two claims, and the second is the one the ticket is about.
 *
 * **A token does not widen its owner.** `ApiTokenFilter` produces a `KansoTokenUser`, which
 * implements `KansoAuthenticatedUser`, which is what `CurrentUser` reads — so
 * `ReadOnlySeatInterceptor` and `TicketAccess` are supposed to apply to a Bearer caller
 * exactly as they do to a browser, with no clause of their own. "Supposed to" is the part
 * worth a test: nothing about that wiring is visible from either of those files, and the
 * plausible mistake — treating a machine caller as exempt because it is a machine — would
 * be invisible in review and would silently promote every read-only seat on the instance.
 *
 * **A refused credential is not an anonymous caller with rights.** This suite runs in dev
 * mode, which is the honest place to assert it: `DevAuthenticationFilter` answers an
 * unauthenticated request as `dev@kanso.local`, an admin. So on this configuration a
 * fall-through is not a subtle downgrade, it is a total one, and a 200 anywhere below is a
 * full compromise rather than a warning sign.
 */
@Transactional
class ApiTokenLeakTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamRepository
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var json: ObjectMapper

	private val owner: User by lazy { person("Leak owner", InstanceRole.OWNER) }

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "bearer-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private fun team(label: String) = teams.insert(
		name = "$label ${UUID.randomUUID()}",
		key = "B${UUID.randomUUID().toString().take(4).uppercase()}",
		parentTeamId = null,
	)

	private fun ticket(teamId: UUID, title: String) = tickets.create(
		actor = owner,
		teamId = teamId,
		title = title,
		description = "The description of $title",
		status = TicketStatus.TODO,
		priority = TicketPriority.NONE,
		start = null,
		due = null,
		projectId = null,
		assigneeIds = emptyList(),
		docIds = emptyList(),
	)

	/**
	 * A token, created the way a person creates one: over HTTP, as themselves.
	 *
	 * Through the endpoint rather than the service, because the plaintext this returns is
	 * the one thing a member ever sees and the response is where they see it. A helper that
	 * reached into the service would not be exercising the door this ticket opened.
	 */
	private fun issue(who: User, vararg scopes: String): String = requireNotNull(issueOrNull(who, *scopes)) {
		"the creation response carried no `secret` field, which is the one thing it exists to carry"
	}

	private fun issueOrNull(who: User, vararg scopes: String): String? {
		val body = json.writeValueAsString(mapOf("name" to "A CLI", "scopes" to scopes.toList()))
		val response = mvc.perform(
			MockMvcRequestBuilders.post("/api/me/tokens")
				.header(DevAuthenticationFilter.HEADER, who.email)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body),
		).andReturn().response

		assertEquals(201, response.status, "a member has to be able to make one: ${response.contentAsString}")
		return json.readTree(response.contentAsString)["secret"].asText()
	}

	/** A request wearing a Bearer token and nothing else — no dev header, no cookie. */
	private fun asToken(secret: String, method: String = "GET", path: String = "/api/tickets", body: String? = null) =
		mvc.perform(
			MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(path))
				.header(HttpHeaders.AUTHORIZATION, "Bearer $secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body ?: "{}"),
		).andReturn().response

	private fun asPerson(who: User, path: String = "/api/tickets") = mvc.perform(
		MockMvcRequestBuilders.get(path).header(DevAuthenticationFilter.HEADER, who.email),
	).andReturn().response

	// --- a refused credential names nobody ------------------------------------

	/**
	 * The four refusals, over the deployed chain, on the mode where getting it wrong hands
	 * out an admin.
	 *
	 * `ApiTokenFilterTest` asserts the same four against mock servlet objects and can prove
	 * more precisely that the chain was never called. This asserts the thing that actually
	 * matters to a deployment: the *status a client receives*, with every real filter in
	 * place — and in particular that `dev@kanso.local` is not what comes back.
	 */
	@Test
	fun `a bad bearer is refused by the running application, not answered as an admin`() {
		val revoked = issue(owner, OAuthScopes.READ, OAuthScopes.WRITE)
		val id = json.readTree(asPerson(owner, "/api/me/tokens").contentAsString)
			.first { it["prefix"].asText() == revoked.take(ApiTokenSecret.PREFIX_LENGTH) }["id"].asText()
		assertEquals(
			204,
			mvc.perform(
				MockMvcRequestBuilders.delete("/api/me/tokens/$id")
					.header(DevAuthenticationFilter.HEADER, owner.email),
			).andReturn().response.status,
			"revocation itself has to work, or the case below proves nothing",
		)

		val bad = mapOf(
			"malformed" to "Bearer",
			"empty after the scheme" to "Bearer ",
			"not a token at all" to "Bearer ${'"'}what${'"'}",
			"shaped like ours but never issued" to "Bearer ${ApiTokenSecret.MARKER}never-existed-at-all-nope",
			"revoked a moment ago" to "Bearer $revoked",
		)

		for ((what, header) in bad) {
			val response = mvc.perform(
				MockMvcRequestBuilders.get("/api/tickets").header(HttpHeaders.AUTHORIZATION, header),
			).andReturn().response

			assertEquals(
				401,
				response.status,
				"$what was answered ${response.status} — in dev mode a fall-through here is served as " +
					"dev@kanso.local, an admin: ${response.contentAsString.take(200)}",
			)
			assertFalse(
				response.contentAsString.contains("dev@kanso.local"),
				"$what came back carrying the dev identity, which means the request was served as it",
			)
		}
	}

	/**
	 * Dev mode's header and a bad token on the same request, which is the sharpest version
	 * of the fall-through: the caller supplies both a credential that will be rejected and
	 * an identity that would be honoured if the rejection merely stood aside.
	 */
	@Test
	fun `a bad bearer beside a dev identity header is still refused`() {
		val response = mvc.perform(
			MockMvcRequestBuilders.get("/api/tickets")
				.header(HttpHeaders.AUTHORIZATION, "Bearer ${ApiTokenSecret.MARKER}forged")
				.header(DevAuthenticationFilter.HEADER, owner.email),
		).andReturn().response

		assertEquals(
			401,
			response.status,
			"the caller asked to be identified by a token; a header cannot rescue a refused one",
		)
	}

	/**
	 * And the other way round: a *good* token beside a header naming somebody else runs as
	 * the token's owner. The token is the credential; the header is a claim with nothing
	 * behind it, and the filter that resolved the token has already answered the question.
	 */
	@Test
	fun `a valid token wins over a dev identity header naming somebody else`() {
		val mine = person("A member", InstanceRole.MEMBER)
		val secret = issue(mine, OAuthScopes.READ)

		val response = mvc.perform(
			MockMvcRequestBuilders.get("/api/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer $secret")
				.header(DevAuthenticationFilter.HEADER, owner.email),
		).andReturn().response

		assertEquals(200, response.status, response.contentAsString)
		assertTrue(
			response.contentAsString.contains(mine.email),
			"the request ran as the token's owner: ${response.contentAsString.take(200)}",
		)
		assertFalse(
			response.contentAsString.contains(owner.email),
			"and not as the instance owner the header named",
		)
	}

	// --- a token does not widen its owner -------------------------------------

	/**
	 * **The claim this ticket turns on.**
	 *
	 * The token carries `kanso:write`, deliberately, so the scope gate cannot be what
	 * refuses it — and the sentence is asserted, not just the code, because a 403 from
	 * somewhere else would let a genuinely unguarded route look green. What has to answer is
	 * `ReadOnlySeatInterceptor`, reading the owner's role out of the database off a
	 * principal that arrived by Bearer, with no line of code anywhere about tokens.
	 */
	@Test
	fun `a read-only seat's token is refused every write, even granted the write scope`() {
		val viewer = person("A reader", InstanceRole.VIEWER)
		val secret = issue(viewer, OAuthScopes.READ, OAuthScopes.WRITE)
		val home = team("Bearer seat")

		val body = json.writeValueAsString(
			mapOf("teamId" to home.id.toString(), "title" to "A ticket a reader should not be able to file"),
		)
		val response = asToken(secret, method = "POST", path = "/api/tickets", body = body)

		assertEquals(403, response.status, "a seat that a token can talk its way past is not a seat")
		assertTrue(
			response.contentAsString.contains(TicketAccess.READS_NOT_WRITES),
			"and refused *as a seat* — a 403 for some other reason would hide an unguarded route: " +
				response.contentAsString.take(200),
		)
	}

	/**
	 * The reads, compared rather than counted.
	 *
	 * A token sees exactly what its owner sees — no more, which is the security claim, and
	 * no less, which is the reason the door was opened at all. Asserted by fetching one
	 * endpoint twice, once with the token and once as the person, and comparing the bytes.
	 * That needs no knowledge of `TicketAccess`'s rules and stays true when they change,
	 * which is the property a hand-written expectation would lose.
	 *
	 * Note what this deliberately does **not** claim. An earlier version of this test
	 * asserted that a ticket in a team the owner does not belong to was invisible, and it
	 * failed — because in Kanso it is visible, to them and to any member. Team membership
	 * bounds what you may *change*, not what you may read; `ReadOnlySeatLeakTest` says the
	 * same thing from the other side ("a viewer reads exactly what a member reads"). So the
	 * assertion is the comparison, and the per-reader boundary is asserted below where one
	 * actually exists.
	 */
	@Test
	fun `a token reads exactly what its owner reads, byte for byte`() {
		val mine = person("A member", InstanceRole.MEMBER)
		val home = team("Bearer home")
		teams.addMember(home.id, mine.id, MemberRole.MEMBER)

		// Pinned non-null rather than checked at each assertion: a ticket with no
		// identifier would make the `contains` below vacuously true, which is the one way
		// this test could pass while proving nothing.
		val visible = requireNotNull(ticket(home.id, "Two-level sub-tickets").identifier)

		val secret = issue(mine, OAuthScopes.READ)
		val byToken = asToken(secret)
		val byPerson = asPerson(mine)

		assertEquals(200, byToken.status, byToken.contentAsString.take(200))
		assertEquals(200, byPerson.status, byPerson.contentAsString.take(200))
		assertEquals(
			byPerson.contentAsString,
			byToken.contentAsString,
			"a token is a member with a different way in; the read model must not be able to tell which",
		)
		assertTrue(
			byToken.contentAsString.contains(visible),
			"and the comparison is not two empty lists agreeing with each other",
		)
	}

	/**
	 * The read boundary that *is* per-reader, which is what makes the comparison above
	 * mean something.
	 *
	 * A ticket no team has claimed belongs to whoever wrote it, and `TicketAccess.mayRead`
	 * refuses everybody else — `DraftReadLeakTest` is the file that pins that for cookies.
	 * This is the same rule through a Bearer header, in both directions: the author's token
	 * reads their draft, and another member's token is told it does not exist.
	 *
	 * A 404 and not a 403, for `DraftReadLeakTest`'s reason: a refusal that confirms the row
	 * is there is what lets somebody walk UUIDs.
	 */
	@Test
	fun `another member's draft is no more readable by a token than by its owner`() {
		val author = person("An author", InstanceRole.MEMBER)
		val stranger = person("A stranger", InstanceRole.MEMBER)
		val draft = tickets.create(
			actor = author,
			teamId = null,
			title = "A draft nobody else should read",
			description = null,
			status = TicketStatus.TODO,
			priority = TicketPriority.NONE,
			start = null,
			due = null,
			projectId = null,
			assigneeIds = emptyList(),
			docIds = emptyList(),
		).ticket.id

		assertEquals(
			200,
			asToken(issue(author, OAuthScopes.READ), path = "/api/tickets/$draft").status,
			"the author's own token reads their own draft, or the door is useless to them",
		)
		val refused = asToken(issue(stranger, OAuthScopes.READ), path = "/api/tickets/$draft")
		assertEquals(
			404,
			refused.status,
			"a draft reached by presenting a token instead of a cookie: ${refused.contentAsString.take(200)}",
		)
		assertFalse(
			refused.contentAsString.contains("nobody else should read"),
			"and its title did not arrive in the refusal either",
		)
	}

	// --- the secret is shown once ---------------------------------------------

	/**
	 * `PublicLeakTest`'s second assertion, one surface along: what reaches the wire is
	 * checked as *serialised bytes*, not as DTO fields, so a field added to a nested
	 * response three refactors from now is caught here without anybody remembering this
	 * file exists.
	 */
	@Test
	fun `the secret appears in the creation response and in nothing else, ever`() {
		val mine = person("A member", InstanceRole.MEMBER)
		val secret = issue(mine, OAuthScopes.READ)

		val listing = asPerson(mine, "/api/me/tokens")
		assertEquals(200, listing.status, listing.contentAsString)

		val body = listing.contentAsString
		assertFalse(body.contains(secret), "the listing carries the plaintext, which is the one thing it cannot")
		assertFalse(
			body.contains(ApiTokenSecret.hash(secret)),
			"nor the digest — it is not a credential, but it is the lookup key and nobody needs it on a screen",
		)
		assertFalse(body.contains("\"secret\""), "and there is no field of that name for anything to fill later")

		// The prefix *is* on the wire, on purpose, and is the part that makes the row
		// identifiable. It has to be a genuine prefix of the token and much shorter than it.
		val prefix = requireNotNull(json.readTree(body).single()["prefix"].asText())
		assertTrue(secret.startsWith(prefix), "a prefix that is not the token's prefix identifies nothing")
		assertTrue(prefix.length < secret.length / 2, "and it stays a label rather than a head start")
		assertNotNull(json.readTree(body).single()["scopeProse"], "a screen has to be able to say what it may do")
	}

	/**
	 * Somebody else's token is not listable, not revocable, and not distinguishable from
	 * one that never existed. There is no path under `/api/me` that names a user, and this
	 * asserts the absence is real rather than merely unrouted.
	 */
	@Test
	fun `one member cannot see or revoke another's tokens`() {
		val mine = person("A member", InstanceRole.MEMBER)
		val theirs = person("Another member", InstanceRole.MEMBER)
		issue(mine, OAuthScopes.READ)

		val listing = asPerson(theirs, "/api/me/tokens")
		assertEquals(200, listing.status)
		assertEquals("[]", listing.contentAsString.trim(), "a member's token list is theirs alone")

		val mineId = json.readTree(asPerson(mine, "/api/me/tokens").contentAsString).single()["id"].asText()
		val stolen = mvc.perform(
			MockMvcRequestBuilders.delete("/api/me/tokens/$mineId")
				.header(DevAuthenticationFilter.HEADER, theirs.email),
		).andReturn().response

		assertEquals(
			404,
			stolen.status,
			"and a 404 rather than a 403: telling them it exists confirms a guessed id is a real token",
		)
		assertEquals(1, json.readTree(asPerson(mine, "/api/me/tokens").contentAsString).size(), "still there")
	}

	// --- the closed vocabulary ------------------------------------------------

	@Test
	fun `a scope this instance does not grant is refused with a sentence, not stored`() {
		val mine = person("A member", InstanceRole.MEMBER)

		for (asked in listOf(listOf("kanso:admin"), listOf(OAuthScopes.READ, "kanso:everything"), emptyList())) {
			val response = mvc.perform(
				MockMvcRequestBuilders.post("/api/me/tokens")
					.header(DevAuthenticationFilter.HEADER, mine.email)
					.contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(mapOf("name" to "A CLI", "scopes" to asked))),
			).andReturn().response

			assertEquals(400, response.status, "$asked is not a vocabulary this instance has: ${response.contentAsString}")
			assertTrue(
				response.contentAsString.contains(OAuthScopes.READ),
				"$asked — the refusal names what does exist, or nobody can act on it",
			)
		}

		assertEquals(
			"[]",
			asPerson(mine, "/api/me/tokens").contentAsString.trim(),
			"and none of those refusals left a row behind",
		)
	}
}

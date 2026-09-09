package dev.kanso.tokens

import dev.kanso.PostgresTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.KansoTokenUser
import dev.kanso.auth.hash
import dev.kanso.db.Users
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.mcp.McpBearerFilter
import dev.kanso.mcp.McpResource
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.server.RequestPath
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.util.pattern.PathPatternParser
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter, driven with mock servlet objects for the reason `McpBearerFilterTest` gives:
 * a filter is a function of a request and a response, and `PostgresTest` has no web
 * environment to spend a second Spring context on. `ApiTokenLeakTest` drives the deployed
 * chain for the questions that need one.
 *
 * Most of this file is one claim, asserted from several directions: **a `Bearer` header is
 * answered here or by nobody.** The four ways it can be bad each get their own test, and
 * each asserts two things — the status, and that `MockFilterChain` was never called. The
 * second is the one that matters. A filter that refused with a 401 *and then chained*
 * would pass every status assertion in this file while handing the request to
 * `DevAuthenticationFilter`, which names an unauthenticated caller `dev@kanso.local`: an
 * admin. So "the chain was not called" is written out every time rather than factored into
 * a helper, because it is the assertion, not the plumbing.
 */
@Transactional
class ApiTokenFilterTest : PostgresTest() {

	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var service: ApiTokenService
	@Autowired lateinit var repository: ApiTokenRepository
	@Autowired lateinit var transactionManager: PlatformTransactionManager

	/**
	 * Every chain the application built. One, in dev mode, where the authorisation
	 * server's is absent — `McpBearerFilterTest` records why that matters for the ordering
	 * assertion at the bottom of this file.
	 */
	@Autowired lateinit var chains: List<SecurityFilterChain>

	/** Generous, so no test below trips the limit by accident. The limit has its own. */
	private val filter by lazy { ApiTokenFilter(service, ApiTokenRateLimit(perMinute = 1_000)) }

	private fun member(role: InstanceRole = InstanceRole.MEMBER): User = users.createLocalUser(
		email = "token-${UUID.randomUUID()}@kanso.test",
		displayName = "Token owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	/**
	 * A token, made the way a person makes one — through the service, which reads the owner
	 * off the security context.
	 *
	 * Not through [ApiTokenRepository.insert] with a secret this test generated, and the
	 * difference is the point: what every test below presents is a string that came out of
	 * `ApiTokenService.create`, so the generator, the digest and the lookup are asserted to
	 * agree rather than assumed to. A test that hashed its own secret would still pass if
	 * `create` stored a digest of something else entirely.
	 */
	private fun issue(owner: User, vararg scopes: String): String {
		val principal = KansoLocalUser(owner.id, owner.email, owner.displayName)
		SecurityContextHolder.setContext(
			SecurityContextHolder.createEmptyContext().apply {
				authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
			},
		)
		val created = service.create("A CLI on my laptop", scopes.toList())
		SecurityContextHolder.clearContext()
		return created.secret
	}

	private fun call(
		header: String?,
		method: String = "GET",
		path: String = "/api/tickets",
		through: ApiTokenFilter = filter,
	): Pair<MockHttpServletResponse, MockFilterChain> {
		val request = MockHttpServletRequest(method, path)
		if (header != null) request.addHeader("Authorization", header)
		val response = MockHttpServletResponse()
		val chain = MockFilterChain()
		through.doFilter(request, response, chain)
		return response to chain
	}

	private fun principal() = SecurityContextHolder.getContext().authentication?.principal

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	// --- the door opens -------------------------------------------------------

	@Test
	fun `a valid token becomes its owner, and carries the scopes it was granted`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)

		val (response, chain) = call("Bearer $secret")

		assertEquals(200, response.status, "nothing refused it, so the chain answered")
		assertNotNull(chain.request, "the request has to reach the application, or the door opens onto nothing")
		val who = principal()
		assertTrue(who is KansoTokenUser, "a token names a member, not a machine account")
		assertEquals(owner.id, who.kansoUserId, "it acts as its owner, with their rights and no others")
		assertEquals(owner.email, who.kansoEmail, "which is what every service downstream reads off the principal")
		assertEquals(setOf(OAuthScopes.READ), who.scopes, "a read grant travels as a read grant")
	}

	@Test
	fun `the marker is part of the secret, so a token is recognisable on sight`() {
		val secret = issue(member(), OAuthScopes.READ)

		assertTrue(secret.startsWith(ApiTokenSecret.MARKER), "a secret scanner matches on exactly this")
		assertEquals(200, call("Bearer $secret").first.status, "and the marker is presented back verbatim")
		// Lowercase `bearer` is a legal scheme name: RFC 7230 says the scheme is
		// case-insensitive, and some HTTP clients normalise it. A filter that compared
		// case-sensitively would refuse a perfectly good token from one of them.
		assertEquals(200, call("bearer $secret").first.status, "the scheme name is case-insensitive")
	}

	// --- the four refusals ----------------------------------------------------

	@Test
	fun `a malformed bearer is refused, and does not continue down the chain`() {
		for (header in listOf("Bearer", "Bearer ", "Bearer    ", "Bearer not-a-kanso-token", "Bearer ${'$'}%^&")) {
			val (response, chain) = call(header)

			assertEquals(401, response.status, "'$header' is not a credential this instance issued")
			assertNull(chain.request, "'$header' was refused and then chained anyway, which is not a refusal")
			assertNull(principal(), "'$header' left an identity standing for a handler to read")
		}
	}

	@Test
	fun `an unknown token is refused, even one shaped exactly like ours`() {
		// The marker is public and trivially copied, so a forgery wears it. The digest is
		// what decides, and this asserts the filter is asking the digest and not the shape.
		val (response, chain) = call("Bearer ${ApiTokenSecret.MARKER}Zm9yZ2VkLW5vdC1yZWFsLWF0LWFsbC1ub3BlMDA")

		assertEquals(401, response.status, "wearing the prefix is not the same as being in the table")
		assertNull(chain.request, "a refusal that still chains is no refusal")
		assertNull(principal())
	}

	@Test
	fun `a revoked token stops working on the very next request`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)
		assertEquals(200, call("Bearer $secret").first.status, "valid before revocation, or this proves nothing")
		SecurityContextHolder.clearContext()

		val id = tokensOf(owner).single().id
		asOwner(owner) { service.revoke(id) }

		val (response, chain) = call("Bearer $secret")
		assertEquals(401, response.status, "no window: the filter resolves against the row revocation deleted")
		assertNull(chain.request, "and the request stops here rather than arriving as somebody else")
		assertNull(principal())
	}

	/**
	 * The fourth refusal, and the one that is not obvious.
	 *
	 * `LocalAuthService` checks `active` once, at sign-in, which is sound for a session
	 * because a session is minted at the moment of the check. A token is minted once and
	 * lives until it is deleted, so an account deactivated after the fact would keep
	 * answering through every token it ever made — and deactivating is exactly the gesture
	 * an admin reaches for when somebody leaves.
	 *
	 * The column is written here by hand because nothing in the product writes it yet:
	 * `users.active` is only ever set true, and `LocalAuthService`'s check is equally
	 * unreachable today. Both are still worth having, and this is the one of the two whose
	 * absence would matter *after* the fact — a sign-in refused is a person locked out on
	 * the spot, a token honoured is a credential nobody remembers exists.
	 */
	@Test
	fun `a token whose owner was deactivated is refused`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)
		assertEquals(200, call("Bearer $secret").first.status, "valid while the account is")
		SecurityContextHolder.clearContext()

		Users.update({ Users.id eq owner.id }) { it[Users.active] = false }

		val (response, chain) = call("Bearer $secret")
		assertEquals(401, response.status, "the credential outlives the session; it must not outlive the account")
		assertNull(chain.request)
		assertNull(principal())
	}

	// --- the rule the whole file is about -------------------------------------

	/**
	 * **The fall-through.** This is the test the design exists for.
	 *
	 * The plausible wrong filter sniffs for its own prefix and lets anything else past. The
	 * request then reaches `DevAuthenticationFilter`, which names an unauthenticated caller
	 * `dev@kanso.local` — an admin on this instance — or, in oidc mode, reaches the session
	 * chain and is served as whoever's cookie came along. Either way somebody who presented
	 * a credential Kanso *rejected* is served as somebody with rights.
	 *
	 * Both halves are asserted: a bad token that does not look like ours, and a bad token
	 * that does. The first is the one a prefix test lets through.
	 */
	@Test
	fun `a bad bearer never reaches the filters that would name it`() {
		for (bad in listOf("Bearer an-oauth-looking-token", "Bearer ${ApiTokenSecret.MARKER}nope", "Bearer x")) {
			val (response, chain) = call(bad)

			assertEquals(401, response.status, "'$bad' has to be answered by the filter that was addressed")
			assertNull(
				chain.request,
				"'$bad' continued down the chain — the next filter is DevAuthenticationFilter, " +
					"which would answer it as ${'"'}dev@kanso.local${'"'}, an admin",
			)
		}
	}

	/**
	 * The same rule where a cookie is also in play, which is the case a "step aside if
	 * somebody is already authenticated" fast path would get wrong.
	 *
	 * `SecurityContextHolderFilter` runs before this filter, so a request carrying a
	 * session cookie arrives with its authentication already loaded. A filter that read
	 * that and stood aside would serve every request holding both a cookie and a bad
	 * Bearer as the cookie's identity — and it would look like an optimisation.
	 */
	@Test
	fun `a bad bearer is not served as the identity a cookie had already loaded`() {
		val browser = member()
		val session = SecurityContextHolder.createEmptyContext().apply {
			val who = KansoLocalUser(browser.id, browser.email, browser.displayName)
			authentication = UsernamePasswordAuthenticationToken(who, null, who.authorities)
		}
		SecurityContextHolder.setContext(session)

		val (response, chain) = call("Bearer ${ApiTokenSecret.MARKER}revoked-or-never-existed")

		assertEquals(401, response.status, "the caller asked to be identified by a token; the token is not valid")
		assertNull(
			chain.request,
			"the request was served with a session cookie's identity despite the credential being refused",
		)
	}

	@Test
	fun `no authorization header at all is the browser, and passes through untouched`() {
		val (response, chain) = call(null)

		assertEquals(200, response.status, "the cookie path is the common case and this filter is not on it")
		assertNotNull(chain.request, "the request continued to the chain that knows about cookies")
		assertNull(principal(), "and this filter named nobody")
	}

	@Test
	fun `a scheme this filter does not offer is somebody else's business`() {
		// `Basic` in particular: an instance behind a reverse proxy doing its own auth, or a
		// client that guessed. Refusing it here would be this filter answering a question it
		// was not asked, and would break a deployment that adds one later.
		//
		// `BearerToken` is the interesting one, and it is why the scheme test checks for a
		// separator rather than matching six letters: it begins with `Bearer` and is not it.
		for (header in listOf("Basic dXNlcjpwYXNz", "Digest username=\"x\"", "Token abc123", "BearerToken abc")) {
			val (response, chain) = call(header)

			assertEquals(200, response.status, "'$header' does not say Bearer, so it is not a claim on this door")
			assertNotNull(chain.request, "'$header' must continue to whatever does understand it")
		}
	}

	// --- the scope gate -------------------------------------------------------

	@Test
	fun `a read-only token is refused a write, and told which scope is missing`() {
		val secret = issue(member(), OAuthScopes.READ)

		val (response, chain) = call("Bearer $secret", method = "POST", path = "/api/tickets")

		assertEquals(403, response.status, "the credential is right and its permissions are not; 401 would say retry")
		assertNull(chain.request, "a write refused for scope must not reach the controller either")
		assertTrue(
			response.getHeader("WWW-Authenticate")!!.contains("insufficient_scope"),
			"RFC 6750 names this case, and a client can branch on it",
		)
		assertTrue(
			response.getHeader("WWW-Authenticate")!!.contains(OAuthScopes.WRITE),
			"and the answer names the scope, because the fix is a person making a new token",
		)
	}

	@Test
	fun `a write token may write, and a read token may still read`() {
		val writer = issue(member(), OAuthScopes.READ, OAuthScopes.WRITE)
		assertEquals(200, call("Bearer $writer", method = "POST", path = "/api/tickets").first.status)
		SecurityContextHolder.clearContext()

		val reader = issue(member(), OAuthScopes.READ)
		assertEquals(200, call("Bearer $reader", method = "GET", path = "/api/tickets").first.status)
	}

	/**
	 * The gate is on the method, so every unsafe verb is covered by construction rather
	 * than by a list somebody has to keep — and the safe ones stay open, which is what a
	 * read-only token is *for*.
	 */
	@Test
	fun `every unsafe method is gated and every safe one is not`() {
		val secret = issue(member(), OAuthScopes.READ)

		for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
			assertEquals(403, call("Bearer $secret", method = method).first.status, "$method writes")
			SecurityContextHolder.clearContext()
		}
		for (method in listOf("GET", "HEAD", "OPTIONS")) {
			assertEquals(200, call("Bearer $secret", method = method).first.status, "$method does not")
			SecurityContextHolder.clearContext()
		}
	}

	// --- the rate limit -------------------------------------------------------

	@Test
	fun `the request past the limit is refused, and the answer says how long to wait`() {
		val secret = issue(member(), OAuthScopes.READ)
		val throttled = ApiTokenFilter(service, ApiTokenRateLimit(perMinute = 2))

		assertEquals(200, call("Bearer $secret", through = throttled).first.status, "first")
		SecurityContextHolder.clearContext()
		assertEquals(200, call("Bearer $secret", through = throttled).first.status, "second")
		SecurityContextHolder.clearContext()

		val (response, chain) = call("Bearer $secret", through = throttled)
		assertEquals(429, response.status, "a loop is what this door's callers fail by, so the limit is day one")
		assertNull(chain.request, "a throttled request must not reach the application; that is the whole point")
		assertEquals("60", response.getHeader("Retry-After"), "obeyable without parsing prose")
		assertNull(principal(), "and nothing is left standing")
	}

	/**
	 * Per token, which is the axis that makes the limit usable: one runaway integration is
	 * throttled while the same person's other tokens keep working, and the row to revoke is
	 * named by the bucket that filled.
	 */
	@Test
	fun `one token's limit is not another's, even for the same member`() {
		val owner = member()
		val loud = issue(owner, OAuthScopes.READ)
		SecurityContextHolder.clearContext()
		val quiet = issue(owner, OAuthScopes.READ)
		val throttled = ApiTokenFilter(service, ApiTokenRateLimit(perMinute = 1))

		assertEquals(200, call("Bearer $loud", through = throttled).first.status)
		SecurityContextHolder.clearContext()
		assertEquals(429, call("Bearer $loud", through = throttled).first.status, "the loud one spent its minute")
		SecurityContextHolder.clearContext()

		assertEquals(
			200,
			call("Bearer $quiet", through = throttled).first.status,
			"a per-member limit would have taken this member's editor down with their CI job",
		)
	}

	// --- last_used_at ---------------------------------------------------------

	@Test
	fun `using a token stamps last_used_at, and never having used one leaves it null`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)

		assertNull(
			tokensOf(owner).single().lastUsedAt,
			"null means never picked up, which is a different fact from used long ago",
		)

		call("Bearer $secret")
		SecurityContextHolder.clearContext()

		assertNotNull(
			tokensOf(owner).single().lastUsedAt,
			"a settings screen that cannot say what is in use cannot be revoked from with any confidence",
		)
	}

	/**
	 * The decision the ticket asked to see written down, asserted rather than commented.
	 *
	 * `last_used_at` is a fact *about* a request, not a precondition of it: the caller
	 * presented a valid credential and passed the limit, so failing them now would turn a
	 * lock wait or a read-only replica into a 500 — on every request, since this write is
	 * on the hot path.
	 */
	@Test
	fun `a failure to stamp last_used_at does not fail the request`() {
		val secret = issue(member(), OAuthScopes.READ)
		val broken = ApiTokenFilter(service, ApiTokenRateLimit(perMinute = 1_000)) {
			throw IllegalStateException("the stamp could not be written")
		}

		val (response, chain) = call("Bearer $secret", through = broken)

		assertEquals(200, response.status, "bookkeeping that fails must not take an authorised request with it")
		assertNotNull(chain.request, "the request reached the application")
		assertTrue(principal() is KansoTokenUser, "and still ran as the token's owner")
	}

	/**
	 * Coarsened to one write per minute per token, asserted at the level where the clock is
	 * an argument. A webhook consumer polling at 10 rps would otherwise mean ten `UPDATE`s a
	 * second on one row, for a screen nobody is looking at.
	 *
	 * **The clock is truncated to microseconds, which is the column's own resolution.**
	 * `timestamptz` keeps microseconds and *rounds* to them — it does not truncate — so a
	 * `now()` carrying nanoseconds comes back a few hundred nanoseconds away from what went
	 * in, and `expected: …261426903Z but was: …261427Z` is what that reads like. Whether it
	 * happens at all is the platform's: the JVM's clock is nanosecond-resolution on Linux
	 * and microsecond on macOS, so this passed on every developer machine and failed on
	 * every CI run — which is exactly the shape of failure that gets called a flake and
	 * left alone.
	 *
	 * Truncating here rather than comparing loosely, because the looser comparison would
	 * weaken the three assertions below, and none of them is about sub-microsecond
	 * fidelity: what this test is for is the one-minute window.
	 */
	@Test
	fun `the stamp is not rewritten within the resolution window, and is after it`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)
		val presented = requireNotNull(service.authenticate(secret)).token

		val first = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)
		service.stamp(presented, first)
		val stamped = requireNotNull(service.authenticate(secret)).token
		assertEquals(first.toInstant(), stamped.lastUsedAt?.toInstant(), "the first use is recorded exactly")

		service.stamp(stamped, first.plusSeconds(5))
		assertEquals(
			first.toInstant(),
			requireNotNull(service.authenticate(secret)).token.lastUsedAt?.toInstant(),
			"five seconds later is the same answer to the only question this column is asked",
		)

		val later = first.plusSeconds(90)
		service.stamp(stamped, later)
		assertEquals(
			later.toInstant(),
			requireNotNull(service.authenticate(secret)).token.lastUsedAt?.toInstant(),
			"past the window it moves, or the column would freeze at the first use forever",
		)
	}

	// --- the partition with the MCP door --------------------------------------

	/**
	 * `/api/mcp` belongs to `McpBearerFilter`, and this filter standing aside there is what
	 * keeps a valid OAuth access token from being looked up in `api_tokens`, missed, and
	 * refused. Two filters claiming one header on one path is one of them breaking the
	 * other.
	 */
	@Test
	fun `the MCP endpoint is not this filter's, whatever shape the path arrives in`() {
		val secret = issue(member(), OAuthScopes.READ)

		// Every shape `McpBearerFilterTest` proves reaches the MCP endpoint. This filter has
		// to stand aside on all of them — and that test's lesson applies in reverse here: a
		// raw-URI comparison would claim `/api;x=y/mcp` and refuse an OAuth token on an
		// endpoint that is not this filter's.
		//
		// `/api/mcp/` is in the list and is deliberately not asserted to be *routed*:
		// `PathPatternParser` does not match a trailing slash, and `McpBearerFilter` guards
		// `/api/mcp/**` anyway. What matters is that both filters draw the border in the
		// same place, and they do because both derive it from `McpResource.PATH + "/**"`.
		val routed = PathPatternParser.defaultInstance.parse(McpResource.PATH)
		val shapes = listOf("/api/mcp", "/api;x=y/mcp", "/api;/mcp", "/api/mcp;session=1", "/api/%6Dcp", "/api/mcp/")

		// The border is real and not a coincidence of spelling: the path this filter *does*
		// answer on is not one Spring would route to the MCP endpoint. Without this the
		// loop below could pass with a `shouldNotFilter` that skipped everything.
		assertFalse(
			routed.matches(RequestPath.parse("/api/tickets", "")),
			"if the REST surface also routed to MCP there would be no partition to assert",
		)

		for (uri in shapes) {
			// A token this instance *did* issue, which is the case that would break: if this
			// filter claimed the path it would authenticate here and set a `KansoTokenUser`,
			// giving MCP a caller that never passed a consent screen.
			val (response, chain) = call("Bearer $secret", method = "POST", path = uri)
			assertEquals(200, response.status, "$uri — this filter has no business answering there")
			assertNotNull(chain.request, "$uri — the request continues to the filter that owns that door")
			assertNull(principal(), "$uri — and this filter named nobody on it")
			SecurityContextHolder.clearContext()
		}
	}

	// --- the session's own context -------------------------------------------

	/**
	 * The rule `AgentPrincipalFilter` carries and `McpBearerFilter` repeats, asserted on
	 * this filter too — and here the precondition is not thin at all: a request reaching
	 * this line may well have arrived with a `JSESSIONID`.
	 * `HttpSessionSecurityContextRepository` hands back the *stored* object rather than a
	 * copy, so an assignment through `getContext()` would replace the identity in a
	 * member's session with the token's, for every later request they make.
	 */
	@Test
	fun `the context a session would have handed in is left exactly as it was`() {
		val browser = member()
		val secret = issue(member(), OAuthScopes.READ)
		val session = SecurityContextHolder.createEmptyContext().apply {
			val who = KansoLocalUser(browser.id, browser.email, browser.displayName)
			authentication = UsernamePasswordAuthenticationToken(who, null, who.authorities)
		}
		SecurityContextHolder.setContext(session)

		call("Bearer $secret")

		assertEquals(
			browser.id,
			(session.authentication?.principal as KansoLocalUser).kansoUserId,
			"this is the session's own object; rewriting it hands a member's browser a token identity",
		)
		assertTrue(principal() is KansoTokenUser, "and this request still runs as the token — a fresh context")
	}

	// --- the secret, and where it is not --------------------------------------

	@Test
	fun `the table holds a digest and never the secret`() {
		val owner = member()
		val secret = issue(owner, OAuthScopes.READ)

		val stored = requireNotNull(service.authenticate(secret))
		val row = tokensOf(owner).single()

		assertNull(
			repository.findByHash(secret),
			"the secret is not itself a key into this table; only its digest is, which is the whole property",
		)
		assertEquals(
			stored.token.tokenId,
			requireNotNull(repository.findByHash(ApiTokenSecret.hash(secret))).tokenId,
			"and the digest is",
		)
		assertTrue(secret.startsWith(row.prefix), "the prefix a screen prints is genuinely this token's")
		assertTrue(row.prefix.length < secret.length, "a 'prefix' as long as the secret would be the secret")
		assertEquals(owner.id, stored.owner.id, "and the row resolves to the member who made it")
	}

	// --- the wiring ----------------------------------------------------------

	/**
	 * The wiring, not the behaviour: everything above drives a filter this test built, so
	 * something has to assert the deployed chain has one — and that it stands ahead of dev
	 * mode's filter, which is the fall-through the refusals above are about.
	 */
	@Test
	fun `the application's chain carries the filter, ahead of dev mode's and behind MCP's`() {
		assertEquals(1, chains.size, "in dev mode the authorisation server's chain is absent; this is Kanso's own")
		val positions = chains.single().filters.withIndex()
		val bearer = positions.firstOrNull { it.value is ApiTokenFilter }
		val mcp = positions.firstOrNull { it.value is McpBearerFilter }
		val dev = positions.firstOrNull { it.value is DevAuthenticationFilter }

		assertNotNull(bearer, "a filter nobody registered protects nothing")
		assertNotNull(mcp, "the MCP filter is the one this must not overtake")
		assertNotNull(dev, "this suite runs in dev mode, which is what makes the ordering below matter")
		assertTrue(mcp.index < bearer.index, "MCP gets first refusal on its own endpoint")
		assertTrue(
			bearer.index < dev.index,
			"a bad token must be refused before the filter that would call it dev@kanso.local",
		)
	}

	/**
	 * The condition the deployed filter runs in: no transaction on the thread.
	 *
	 * `McpBearerFilterTest` records what this catches — every other test in a
	 * `@Transactional` class has already opened the transaction the filter needs, so a
	 * filter that cannot open its own would be green here and raise `No transaction in
	 * context` on every request of a real server. This filter reaches the database only
	 * through `ApiTokenService`, whose methods are `@Transactional`, and that is what this
	 * asserts.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun `a valid token resolves with no transaction on the thread, which is how it is deployed`() {
		val transactions = TransactionTemplate(transactionManager)
		val owner = requireNotNull(transactions.execute { member() })
		val secret = requireNotNull(transactions.execute { issue(owner, OAuthScopes.READ) })

		val (response, chain) = call("Bearer $secret")

		assertEquals(200, response.status, "the request continued, so nothing refused it")
		assertNotNull(chain.request)
		val who = principal()
		assertTrue(who is KansoTokenUser, "a filter that cannot read its own table cannot name anybody")
		assertEquals(owner.id, who.kansoUserId, "and the member it names is the one who made the token")
	}

	/**
	 * `ApiTokenService`'s three member-facing methods take no user id, so a test cannot ask
	 * them about somebody — it has to *be* them. That is the property that makes the
	 * signature safe, and this is what it costs a test: the person goes into the security
	 * context for the duration of one call.
	 */
	private fun <T> asOwner(owner: User, block: () -> T): T {
		val principal = KansoLocalUser(owner.id, owner.email, owner.displayName)
		SecurityContextHolder.setContext(
			SecurityContextHolder.createEmptyContext().apply {
				authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
			},
		)
		return try {
			block()
		} finally {
			SecurityContextHolder.clearContext()
		}
	}

	private fun tokensOf(owner: User): List<ApiToken> = asOwner(owner) { service.list() }
}

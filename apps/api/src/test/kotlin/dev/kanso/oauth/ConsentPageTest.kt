package dev.kanso.oauth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one screen in Kanso a person reads before handing an agent their work.
 *
 * Tested as a string because it is one — a pure render, so every assertion here is about
 * copy and escaping rather than about a servlet. The escaping tests are the point: a
 * client chooses its own name, and this page puts that name in Kanso's voice next to an
 * Authorise button.
 */
class ConsentPageTest {

	private val fresh = ClientEvidence(listOf("claude.ai"), Duration.ofMinutes(4))

	private fun page(
		clientName: String = "Claude Code",
		email: String = "elie@example.com",
		scopes: List<String> = OAuthScopes.ALL,
		evidence: ClientEvidence = fresh,
	) = ConsentPage.render(clientName, email, scopes, clientId = "claude-code", state = "st4te", evidence = evidence)

	@Test
	fun `it names the client and the member, because approving the wrong one is the failure`() {
		val html = page()
		assertTrue(html.contains("Claude Code"))
		assertTrue(html.contains("elie@example.com"))
	}

	@Test
	fun `it shows each scope as a sentence, not as an identifier`() {
		val html = page()
		for (scope in OAuthScopes.ALL) assertTrue(html.contains(OAuthScopes.prose(scope)))
	}

	@Test
	fun `it says the grant is revocable, beside the button that gives it`() {
		assertTrue(page().contains("Settings"), "a grant nobody knows how to undo is not consent")
	}

	@Test
	fun `it posts to the library's endpoint, carrying the state it was given`() {
		val html = page()
		assertTrue(html.contains("action=\"/oauth2/authorize\""))
		assertTrue(html.contains("method=\"post\""))
		assertTrue(html.contains("value=\"st4te\""))
	}

	@Test
	fun `a client name containing markup is escaped, not rendered`() {
		// A client registers itself, unauthenticated, and picks its own name. Rendering
		// that verbatim would let it write the page it is asking to be approved on.
		val html = page(clientName = "<script>alert(1)</script>")
		assertFalse(html.contains("<script>alert"))
		assertTrue(html.contains("&lt;script&gt;"))
	}

	@Test
	fun `a state containing a quote cannot break out of its attribute`() {
		val html = ConsentPage.render("C", "e@x.test", OAuthScopes.ALL, "c", "\" onload=\"x", fresh)
		assertFalse(html.contains("onload=\"x\""))
	}

	@Test
	fun `it renders in both themes, because it borrows no stylesheet`() {
		assertTrue(
			page().contains("prefers-color-scheme: dark"),
			"served from the API, it has no app CSS to inherit",
		)
	}

	/**
	 * The name is the client's own text and the whole screen used to be nothing else, which
	 * is what made open registration rest on a field the attacker fills in: `/connect/register`
	 * is unauthenticated and `claude.ai` is an allowed host by default, so anyone can be
	 * "Claude Code" with a callback on it. These two lines are the parts nobody registering
	 * gets to choose.
	 */
	@Test
	fun `it shows where the code would go and how old the registration is`() {
		val html = page()
		assertTrue(html.contains("claude.ai"), "the host is the only thing that says who receives the code")
		assertTrue(html.contains("4 minutes ago"), "a client registered minutes ago is the shape of an attack")
	}

	@Test
	fun `the age is read out in the coarsest unit that is still true`() {
		assertTrue(page(evidence = ClientEvidence(listOf("claude.ai"), Duration.ZERO)).contains("less than a minute ago"))
		assertTrue(page(evidence = ClientEvidence(listOf("claude.ai"), Duration.ofMinutes(1))).contains("1 minute ago"))
		assertTrue(page(evidence = ClientEvidence(listOf("claude.ai"), Duration.ofHours(3))).contains("3 hours ago"))
		assertTrue(page(evidence = ClientEvidence(listOf("claude.ai"), Duration.ofDays(400))).contains("400 days ago"))
	}

	@Test
	fun `a registration with no recorded instant says that, rather than implying age`() {
		val html = page(evidence = ClientEvidence(listOf("claude.ai"), null))
		assertFalse(html.contains(" ago"), "an unknown age must not be rendered as an old one")
		assertTrue(html.contains("did not record"))
	}

	@Test
	fun `several hosts are all named, and a client with none readable says so`() {
		assertTrue(
			page(evidence = ClientEvidence(listOf("claude.ai", "127.0.0.1"), Duration.ofMinutes(4)))
				.contains("claude.ai, 127.0.0.1"),
			"a member deciding about a code needs every address it might be sent to",
		)
		assertTrue(
			page(evidence = ClientEvidence(emptyList(), Duration.ofMinutes(4))).contains("cannot read"),
			"a blank line reads as reassurance, which is the one thing it is not",
		)
	}

	@Test
	fun `a host is escaped like everything else, because it came out of a registration`() {
		// Registered, therefore checked by `RedirectUriPolicy` — and escaped anyway. The
		// argument for trusting one value on this page is the argument that gets the next
		// one wrong.
		val html = page(evidence = ClientEvidence(listOf("<script>alert(1)</script>"), Duration.ofMinutes(4)))
		assertFalse(html.contains("<script>alert"))
		assertTrue(html.contains("&lt;script&gt;"))
	}

	/**
	 * The host, and never the whole URI: a path is more of the client's own text, and it is
	 * the host that decides who receives the code.
	 */
	@Test
	fun `evidence is derived from the registration, keeping hosts and dropping everything else`() {
		val evidence = ClientEvidence.of(
			redirectUris = listOf(
				"https://claude.ai/api/mcp/auth/callback",
				"https://CLAUDE.AI/another/path",
				"not a uri at all",
			),
			registeredAt = Instant.parse("2026-09-01T09:00:00Z"),
			now = Instant.parse("2026-09-01T10:30:00Z"),
		)

		assertEquals(listOf("claude.ai"), evidence.redirectHosts, "one host, lowercased, and no path from it")
		assertEquals(Duration.ofMinutes(90), evidence.registeredAge)
	}

	/**
	 * The consequential half of the pair below, and the one this file first left out:
	 * every other assertion here stays green with the scope inputs deleted, while
	 * Authorise quietly becomes a post the library reads as `access_denied` — the button
	 * says yes and the flow says no, on the one screen a person actually reads.
	 *
	 * `client_id` and `state` are folded in because they belong to the same contract,
	 * though they are lower stakes: without them the library answers `invalid_request`,
	 * which fails loudly rather than silently.
	 */
	@Test
	fun `the authorise form carries the client, the state, and one input per scope asked for`() {
		val authorise = page().substringAfter("<form").substringBefore("</form>")

		assertTrue(authorise.contains("""name="client_id""""), "the library refuses a decision with no client")
		assertTrue(authorise.contains("""name="state""""), "and refuses one with no consent nonce")
		assertEquals(
			OAuthScopes.ALL.size,
			Regex("""name="scope"""").findAll(authorise).count(),
			"one input per permission — a scope shown and not posted is a permission not granted",
		)
		for (scope in OAuthScopes.ALL) {
			assertTrue(authorise.contains("""value="$scope""""), "the page must post the scope it read out")
		}
	}

	/**
	 * Not in the brief, and the deny path is meaningless without it: the library reads a
	 * decision off the *presence* of `scope`, so a decline that posted the scopes would
	 * be an approval.
	 */
	@Test
	fun `declining posts the same request with no scope, which is how the library hears no`() {
		val html = page()
		val deny = html.substringAfter("<form", "").substringAfter("<form", "")
		assertTrue(deny.contains("Decline"), "the second form is the one that declines")
		assertFalse(deny.contains("name=\"scope\""), "a scope on this form would grant what it claims to refuse")
	}
}

package dev.kanso.oauth

import kotlin.test.Test
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

	private fun page(
		clientName: String = "Claude Code",
		email: String = "elie@example.com",
		scopes: List<String> = OAuthScopes.ALL,
	) = ConsentPage.render(clientName, email, scopes, clientId = "claude-code", state = "st4te")

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
		val html = ConsentPage.render("C", "e@x.test", OAuthScopes.ALL, "c", "\" onload=\"x")
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

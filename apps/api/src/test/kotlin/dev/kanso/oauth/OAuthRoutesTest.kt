package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard `PublicRoutesTest` is for `PublicRoutes`, and a separate one on purpose.
 *
 * `PublicRoutes` asserts every pattern it holds begins with `/api/public/`, which is
 * true and worth keeping — so the OAuth endpoints cannot live there without weakening
 * the assertion that makes that file readable. A sibling list with its own guard costs
 * one small file; widening the existing test costs the property it exists to protect.
 */
class OAuthRoutesTest {

	@Test
	fun `every open route is an OAuth or discovery path, and none is a wildcard`() {
		assertTrue(OAuthRoutes.ALL.isNotEmpty())
		for (pattern in OAuthRoutes.ALL) {
			assertTrue(
				pattern.startsWith("/oauth2/") ||
					pattern.startsWith("/connect/") ||
					pattern.startsWith("/.well-known/"),
				"$pattern is not an OAuth or discovery path",
			)
			assertFalse(pattern.contains("*"), "$pattern is a wildcard, which opens whatever lands under it")
		}
	}

	@Test
	fun `the two well-known documents are readable without a session`() {
		assertTrue(OAuthRoutes.OPEN_GET.contains("/.well-known/oauth-authorization-server"))
		assertTrue(OAuthRoutes.OPEN_GET.contains("/.well-known/oauth-protected-resource"))
	}

	@Test
	fun `no route that needs a member is open`() {
		// The consent screen is the browser's, behind the session. If it ever appears
		// here, anybody can approve a grant for anybody.
		assertFalse(OAuthRoutes.ALL.any { it.contains("consent") })
		assertEquals(
			emptyList(),
			OAuthRoutes.ALL.filter { it.startsWith("/api/") },
			"nothing under /api is opened by this list",
		)
	}
}

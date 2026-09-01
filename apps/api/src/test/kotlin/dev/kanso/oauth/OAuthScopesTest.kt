package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two scopes, and the sentence each one shows a member.
 *
 * A plain test rather than one behind Spring, because the interesting part is copy:
 * these two strings appear on a consent screen a person reads before granting an agent
 * access to their work, and "kanso:write" is not a sentence.
 */
class OAuthScopesTest {

	@Test
	fun `there are two scopes, namespaced`() {
		assertEquals(listOf("kanso:read", "kanso:write"), OAuthScopes.ALL)
	}

	@Test
	fun `each scope has prose, and neither sentence is its identifier`() {
		for (scope in OAuthScopes.ALL) {
			val sentence = OAuthScopes.prose(scope)
			assertTrue(sentence.length > 20, "$scope reads as an identifier, not a sentence")
			assertFalse(sentence.contains("kanso:"), "$scope leaks its identifier into the copy")
		}
	}

	/**
	 * `V18` adds `activity.via_client_id` and nothing under `src/main` writes it, so a
	 * sentence promising that a change is recorded as coming from the application was
	 * false — next to an Authorise button, and repeated on the settings screen through
	 * `GrantService.scopeProse`. `AgentRightsTest` pins the missing column; this pins the
	 * wording, so the two go back together or not at all.
	 */
	@Test
	fun `the write sentence claims no provenance, because none is recorded yet`() {
		assertFalse(
			OAuthScopes.prose(OAuthScopes.WRITE).contains("recorded", ignoreCase = true),
			"the consent screen must not promise a record this version of Kanso does not keep",
		)
	}

	@Test
	fun `an unknown scope is refused rather than shown raw`() {
		// A client may ask for anything. Rendering it verbatim on a consent screen is how
		// a scope called "and full access to your email" gets shown in Kanso's own voice.
		assertFailsWith<IllegalArgumentException> { OAuthScopes.prose("kanso:admin") }
	}
}

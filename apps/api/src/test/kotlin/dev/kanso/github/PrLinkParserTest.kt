package dev.kanso.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parser as a table of pure cases, which is what the design asks for and what a pure
 * function makes possible: no container, no context, no clock.
 *
 * Every one of these is a sentence somebody will actually write on a pull request. The two
 * that matter most are the ones where the answer is *not* a link — an unknown key, and a
 * key inside a longer word — because that is where a wrong answer closes somebody else's
 * ticket.
 */
class PrLinkParserTest {

	private fun keys(headRef: String? = null, title: String? = null, body: String? = null) =
		PrLinkParser.parse(headRef, title, body)

	@Test
	fun `a branch named after a ticket closes it`() {
		assertEquals(
			setOf(PrLink("KAN-142", closes = true)),
			keys(headRef = "feat/kan-142-overlap-warning"),
		)
	}

	@Test
	fun `a bare mention links and stays inert`() {
		assertEquals(
			setOf(PrLink("KAN-99", closes = false)),
			keys(body = "unlike KAN-99, this one keeps the index"),
		)
	}

	/**
	 * The whole reason `closes` exists. If a bare mention closed, this body would cancel a
	 * ticket for being cited as a counter-example.
	 */
	@Test
	fun `a mention of a ticket is not a promise about it`() {
		val links = keys(body = "See KAN-99 for why this is not that")
		assertTrue(links.none { it.closes }, "a citation is not a declaration: $links")
	}

	@Test
	fun `each keyword closes, in the title and in the body`() {
		for (word in listOf("Fixes", "Closes", "Resolves", "fixed", "close", "RESOLVED")) {
			assertEquals(
				setOf(PrLink("KAN-7", closes = true)),
				keys(title = "$word KAN-7"),
				"'$word' in a title",
			)
			assertEquals(
				setOf(PrLink("KAN-7", closes = true)),
				keys(body = "$word KAN-7"),
				"'$word' in a body",
			)
		}
	}

	@Test
	fun `a colon between the keyword and the key is allowed`() {
		assertEquals(setOf(PrLink("KAN-8", closes = true)), keys(body = "Fixes: KAN-8"))
	}

	@Test
	fun `lowercase input comes back as a Kanso key`() {
		assertEquals(setOf(PrLink("KAN-142", closes = true)), keys(headRef = "kan-142"))
	}

	@Test
	fun `several keys in one body are several links`() {
		assertEquals(
			setOf(PrLink("KAN-1", closes = true), PrLink("KAN-2", closes = false)),
			keys(body = "Fixes KAN-1. Groundwork for KAN-2."),
		)
	}

	/**
	 * The claims are OR-ed rather than last-one-wins, so the order the three fields are read
	 * in cannot change the answer. Both directions are asserted because a `put` instead of an
	 * `||` passes one of them.
	 */
	@Test
	fun `the strongest claim for a key wins, whichever field made it`() {
		assertEquals(
			setOf(PrLink("KAN-5", closes = true)),
			keys(headRef = "feat/kan-5-x", body = "also mentions KAN-5"),
			"the branch closes it and a later bare mention must not weaken that",
		)
		assertEquals(
			setOf(PrLink("KAN-5", closes = true)),
			keys(title = "KAN-5 groundwork", body = "Fixes KAN-5"),
			"a bare mention read first must not survive a keyword read after it",
		)
	}

	/**
	 * Greedy digits. A non-greedy `\d+?` here would return KAN-142 for a pull request about
	 * KAN-1425 — which is a link to the wrong ticket, and on merge, a *transition* on the
	 * wrong ticket.
	 */
	@Test
	fun `a longer number is its own key and not a prefix of one`() {
		assertEquals(setOf(PrLink("KAN-1425", closes = true)), keys(headRef = "fix/kan-1425"))
	}

	/**
	 * The candidate is `XKAN`, not `KAN` — so it matches no team and drops out at the caller.
	 * Asserted as "KAN-12 is not among them" rather than as an empty set, because the parser
	 * is deliberately liberal and returning `XKAN-12` is correct.
	 */
	@Test
	fun `a key inside a longer word does not yield that key`() {
		val links = keys(body = "XKAN-12 belongs to another tracker")
		assertTrue(links.none { it.key == "KAN-12" }, "must not claim KAN-12: $links")
	}

	@Test
	fun `an unknown key is returned as a candidate, for the caller to refuse`() {
		assertEquals(setOf(PrLink("ARCH-12", closes = false)), keys(body = "per ARCH-12"))
	}

	@Test
	fun `nothing on the pull request is no links, not a failure`() {
		assertEquals(emptySet(), keys(headRef = "main", title = "Bump deps", body = null))
		assertEquals(emptySet(), keys())
	}

	/**
	 * `synchronize` is the event this protects: the parser is never re-run on it, but a body
	 * that is only whitespace or only prose must be safe to re-run over regardless.
	 */
	@Test
	fun `prose with hyphens and numbers that is not a key`() {
		assertEquals(
			emptySet(),
			keys(body = "Reduces p99 from 30-40ms, see the overlap-warning section"),
		)
	}
}

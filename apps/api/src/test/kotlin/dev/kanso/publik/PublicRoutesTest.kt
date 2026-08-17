package dev.kanso.publik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A guard on the shape of the one widening this branch makes to the filter chain.
 *
 * Not a Spring test on purpose: standing a second application context up to send an
 * anonymous request would make the framework restart the shared one between classes,
 * which `SecurityBootstrapTest` already records as something not every bean here
 * survives. The e2e spec calls all three routes with no credentials and is where the
 * end-to-end claim is actually proved; this is what stops the *set* from growing
 * quietly, which is the failure mode that would not announce itself.
 */
class PublicRoutesTest {

	@Test
	fun `exactly three routes answer without a session`() {
		assertEquals(
			listOf(
				"/api/public/roadmap",
				"/api/public/roadmap/{teamKey}/{number}",
				"/api/public/roadmap/{teamKey}/{number}/vote",
			),
			PublicRoutes.ALL,
			"adding a fourth is a decision, and it has to be made here where it is visible",
		)
	}

	@Test
	fun `no pattern can swallow a route it was not written for`() {
		for (pattern in PublicRoutes.ALL) {
			assertTrue(
				pattern.startsWith("/api/public/"),
				"$pattern is outside the prefix everything anonymous lives under",
			)
			assertFalse(pattern.contains("*"), "$pattern is a wildcard, and would open whatever lands under it")
		}
	}

	@Test
	fun `writing is not among them`() {
		// The two writes behind these screens — publishing a ticket, listing where to
		// look — are `PublicationController`'s, they need a session, and `TicketAccess`
		// decides them. Voting is the only state change a stranger can make.
		assertEquals(
			listOf("/api/public/roadmap/{teamKey}/{number}/vote"),
			PublicRoutes.OPEN_POST.toList(),
		)
	}
}

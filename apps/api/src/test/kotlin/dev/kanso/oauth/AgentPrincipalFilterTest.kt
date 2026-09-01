package dev.kanso.oauth

import dev.kanso.auth.KansoLocalUser
import org.junit.jupiter.api.AfterEach
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The filter that makes an authorisation persistable — and the one thing it must not
 * touch on the way.
 *
 * A plain test, no Spring, because the test profile runs in dev mode where the
 * authorisation server's chain does not exist at all: there is no installed filter to
 * drive. What can be asserted here is the whole of the defect, though, because the
 * defect was a property of two lines rather than of the wiring.
 */
class AgentPrincipalFilterTest {

	private val member = KansoLocalUser(UUID.randomUUID(), "elie@example.test", "Elie")

	private fun signedIn(): SecurityContext = SecurityContextHolder.createEmptyContext().apply {
		authentication = UsernamePasswordAuthenticationToken(member, null, member.authorities)
	}

	private fun run() = AgentPrincipalFilter().doFilter(
		MockHttpServletRequest("GET", "/oauth2/authorize"),
		MockHttpServletResponse(),
		MockFilterChain(),
	)

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	/**
	 * The instance the holder carries on this chain **is** the instance in the member's
	 * `HttpSession`: `HttpSessionSecurityContextRepository` returns the stored object
	 * rather than a copy, and `SecurityContextHolderFilter` neither copies it nor saves
	 * it. So assigning into it rewrites the session, and the member's next request to any
	 * Kanso endpoint arrives with a bare `String` principal — `isAuthenticated()` true,
	 * `CurrentUser` unable to read it, every endpoint refused, Settings included. One
	 * top-level link to any path on this chain would have done that to whoever clicked
	 * it, so the object identity below is the security property.
	 */
	@Test
	fun `the context the session holds is left exactly as it was`() {
		val session = signedIn()
		val stored = session.authentication
		SecurityContextHolder.setContext(session)

		run()

		assertSame(
			stored,
			session.authentication,
			"this is the session's own object; rewriting it signs the member out of everything but the cookie",
		)
		assertSame(member, stored!!.principal, "and the rich principal is what CurrentUser reads")
	}

	@Test
	fun `this request still sees only the user id, because that is what the library persists`() {
		SecurityContextHolder.setContext(signedIn())

		run()

		val authentication = SecurityContextHolder.getContext().authentication
		assertEquals(
			member.kansoUserId.toString(),
			authentication?.principal,
			"a Kanso principal cannot make the library's JSON round trip, and the id is the key a token maps back through",
		)
		assertTrue(
			authentication!!.authorities.containsAll(member.authorities),
			"the authorities are the member's own; only the principal is narrowed",
		)
	}

	@Test
	fun `an authentication that is not a member's is left alone`() {
		val context = SecurityContextHolder.createEmptyContext().apply {
			authentication = UsernamePasswordAuthenticationToken("already-an-id", null, emptyList())
		}
		SecurityContextHolder.setContext(context)

		run()

		assertEquals(
			"already-an-id",
			SecurityContextHolder.getContext().authentication?.principal,
			"nothing to narrow, so nothing is replaced",
		)
	}
}

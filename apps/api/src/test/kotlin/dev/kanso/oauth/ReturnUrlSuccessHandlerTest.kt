package dev.kanso.oauth

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where a member lands after coming back from Google — which, before this handler
 * existed, was the app root and nothing else.
 *
 * The first-run door is `claude mcp add` → `/oauth2/authorize` → no session → the consent
 * controller redirects to the app's login screen with the way back → sign in → consent.
 * A member who *types a password* comes back through `apps/web`, which still holds the
 * `next` in its URL. A member who *clicks Google* does not: the provider round trip
 * leaves the app entirely and returns to the API, and a fixed success handler drops them
 * on the home screen while `claude mcp add` waits forever. So the way back is stashed in
 * the session on the way out and read back here.
 *
 * The other half of this file, and the more dangerous half: **every** sign-in in Kanso
 * that goes through a provider ends up in this method. A member with nothing stashed must
 * land exactly where `SimpleUrlAuthenticationSuccessHandler(webOrigin)` landed them, so
 * that is asserted rather than assumed.
 */
class ReturnUrlSuccessHandlerTest {

	private val webOrigin = "http://localhost:3000"
	private val handler = ReturnUrlSuccessHandler(webOrigin)

	/**
	 * As the provider's callback arrives: `GET /login/oauth2/code/google` on the API.
	 *
	 * The session is created whether or not anything is stashed in it, because "a session
	 * with no return address" and "no session at all" are two different branches and the
	 * first is the one nearly every member takes. A helper that only created the session
	 * when it had something to put in it would collapse them into one and leave the common
	 * case untested.
	 */
	private fun callback(stashed: ReturnAddress? = null): MockHttpServletRequest {
		val request = MockHttpServletRequest("GET", "/login/oauth2/code/google")
		request.scheme = "https"
		request.serverName = "api.example.com"
		request.serverPort = 443
		request.getSession(true)
		if (stashed != null) request.session!!.setAttribute(RETURN_URL_ATTRIBUTE, stashed)
		return request
	}

	/** As `ConsentController` writes it: valid from now, for [RETURN_URL_TTL]. */
	private fun stash(url: String) = ReturnAddress.validFrom(url, Instant.now())

	private fun landing(request: MockHttpServletRequest): String? {
		val response = MockHttpServletResponse()
		handler.onAuthenticationSuccess(
			request,
			response,
			UsernamePasswordAuthenticationToken("someone@kanso.test", null, emptyList()),
		)
		return response.redirectedUrl
	}

	@Test
	fun `a member with a session and nothing stashed lands where they always landed`() {
		val request = callback()
		assertNotNull(request.getSession(false), "the branch under test is the one where a session exists")
		assertEquals(webOrigin, landing(request), "an ordinary sign-in must not change destination")
	}

	@Test
	fun `a member who never had a session lands where they always landed`() {
		val request = MockHttpServletRequest("GET", "/login/oauth2/code/google")
		// `getSession(false)` answers null here, which is the shape of every sign-in that
		// did not start at the consent page.
		assertEquals(webOrigin, landing(request), "no session is not a reason to fail, only to default")
	}

	@Test
	fun `a member who started at the consent page is sent back to it`() {
		val consent = "https://api.example.com/oauth/consent?client_id=claude-code&scope=kanso%3Aread&state=s"
		assertEquals(
			consent,
			landing(callback(stash(consent))),
			"otherwise the agent's authorisation is abandoned in silence",
		)
	}

	/**
	 * It has to survive a login screen, a provider page and a callback, and nothing
	 * longer: what an expired address is good for is redirecting a sign-in that had
	 * nothing to do with it.
	 */
	@Test
	fun `a way back older than its own flow is dropped`() {
		val consent = "https://api.example.com/oauth/consent?client_id=claude-code&state=s"
		val stale = ReturnAddress.validFrom(consent, Instant.now().minus(RETURN_URL_TTL).minusSeconds(1))
		assertEquals(webOrigin, landing(callback(stale)), "an address this old belongs to an abandoned flow")
	}

	@Test
	fun `the way back is consumed, so it cannot redirect a later sign-in`() {
		val consent = "https://api.example.com/oauth/consent?client_id=claude-code&scope=kanso%3Aread&state=s"
		val request = callback(stash(consent))
		landing(request)
		assertNull(
			request.session!!.getAttribute(RETURN_URL_ATTRIBUTE),
			"a return address that outlives its flow sends the next sign-in somewhere it never asked to go",
		)
	}

	/**
	 * Nothing writes this attribute but `ConsentController`, which validated the URL when
	 * it built it. The check happens again here anyway, because the value is reflected
	 * into a `Location` header and the day it acquires a second writer is the day that
	 * matters — and there will be no test failure to announce it.
	 */
	@Test
	fun `a stashed value on another origin is refused, not followed`() {
		val refused = { url: String ->
			assertEquals(webOrigin, landing(callback(stash(url))), "$url must never be followed out of a sign-in")
		}
		refused("https://evil.example.com/steal")
		// Reads as this origin to a prefix comparison, and to a person, respectively.
		refused("https://api.example.com.evil.example.com/steal")
		refused("https://api.example.com@evil.example.com/steal")
		// Protocol-relative, in the two spellings `URI` disagrees with a browser about.
		refused("//evil.example.com/steal")
		refused("///evil.example.com/steal")
		refused("javascript:alert(1)")
	}
}

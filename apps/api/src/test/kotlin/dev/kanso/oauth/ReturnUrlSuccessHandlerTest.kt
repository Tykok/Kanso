package dev.kanso.oauth

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import kotlin.test.Test
import kotlin.test.assertEquals
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

	/** As the provider's callback arrives: `GET /login/oauth2/code/google` on the API. */
	private fun callback(stashed: String? = null): MockHttpServletRequest {
		val request = MockHttpServletRequest("GET", "/login/oauth2/code/google")
		request.scheme = "https"
		request.serverName = "api.example.com"
		request.serverPort = 443
		if (stashed != null) request.session!!.setAttribute(RETURN_URL_ATTRIBUTE, stashed)
		return request
	}

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
	fun `a member with nothing stashed lands where they always landed`() {
		assertEquals(webOrigin, landing(callback()), "an ordinary sign-in must not change destination")
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
		assertEquals(consent, landing(callback(consent)), "otherwise the agent's authorisation is abandoned in silence")
	}

	@Test
	fun `the way back is consumed, so it cannot redirect a later sign-in`() {
		val consent = "https://api.example.com/oauth/consent?client_id=claude-code&scope=kanso%3Aread&state=s"
		val request = callback(consent)
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
		assertEquals(webOrigin, landing(callback("https://evil.example.com/steal")))
		assertEquals(webOrigin, landing(callback("https://api.example.com.evil.example.com/steal")))
		assertEquals(webOrigin, landing(callback("//evil.example.com/steal")))
		assertEquals(webOrigin, landing(callback("javascript:alert(1)")))
	}
}

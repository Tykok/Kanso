package dev.kanso.oauth

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.config.KansoProperties
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BadRequestException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HTTP half of the consent screen: who is asking, who is answering, and what happens
 * when either of them is missing.
 *
 * A `MockHttpServletRequest` stands in RequestContextHolder because the anonymous branch
 * builds the return URL from the request that arrived — there is no servlet here, and the
 * URL it hands the login screen is the one thing on this page that gets reflected back
 * into a redirect, so it is worth asserting rather than skipping.
 */
@Transactional
class ConsentControllerTest : PostgresTest() {

	@Autowired lateinit var controller: ConsentController
	@Autowired lateinit var clients: RegisteredClientRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var props: KansoProperties

	private fun member(): User = users.createLocalUser(
		email = "consent-${UUID.randomUUID()}@kanso.test",
		displayName = "Consenting member",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	/** What `/connect/register` would have written, minus everything this page never reads. */
	private fun register(clientId: String, name: String) {
		clients.save(
			RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId(clientId)
				.clientName(name)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://127.0.0.1:8765/callback")
				.apply { OAuthScopes.ALL.forEach { scope(it) } }
				.clientSettings(
					ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build(),
				)
				.build(),
		)
	}

	/** Stands in for the auth filter, which has no servlet request here to run inside. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	private fun arriveWith(query: String) {
		val request = MockHttpServletRequest("GET", CONSENT_PAGE)
		request.serverName = "kanso.example.test"
		request.serverPort = 8080
		request.queryString = query
		RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
	}

	@BeforeEach
	fun arrive() {
		arriveWith("client_id=claude-code&scope=kanso%3Aread&state=s")
	}

	@AfterEach
	fun clearSecurityContext() {
		SecurityContextHolder.clearContext()
		RequestContextHolder.resetRequestAttributes()
	}

	@Test
	fun `a signed-in member gets the page, naming the client`() {
		register(clientId = "claude-code", name = "Claude Code")
		actAs(member())

		val response = controller.consent(
			clientId = "claude-code", scope = "kanso:read kanso:write", state = "s",
		)

		assertEquals(HttpStatus.OK, response.statusCode)
		assertTrue(response.body!!.contains("Claude Code"))
		assertTrue(response.headers.contentType!!.toString().startsWith("text/html"))
	}

	@Test
	fun `no session sends the member to log in, and back here afterwards`() {
		register(clientId = "claude-code", name = "Claude Code")
		SecurityContextHolder.clearContext()

		val response = controller.consent(clientId = "claude-code", scope = "kanso:read", state = "s")

		assertEquals(HttpStatus.FOUND, response.statusCode)
		val location = response.headers.location!!.toString()
		assertTrue(location.startsWith(props.webOrigin), "login lives in the app, not on the API")
		assertTrue(location.contains("next="), "dropping the request lands them on an empty screen")
		assertTrue(location.contains("consent"), "the round trip comes back to this page")
	}

	/**
	 * The bug a `queryParam(...).encode()` pair walks straight into: `&` and `=` are legal
	 * query characters, so nothing escapes them, and the app reads a return URL truncated
	 * at the first nested parameter — landing the member on a consent page with no client.
	 */
	@Test
	fun `the return URL survives being a parameter, nested ampersands and all`() {
		register(clientId = "claude-code", name = "Claude Code")
		SecurityContextHolder.clearContext()

		val location = controller
			.consent(clientId = "claude-code", scope = "kanso:read", state = "s")
			.headers.location!!.toString()

		val next = location.substringAfter("next=")
		assertFalse(next.contains("&"), "an unescaped separator ends the value the app reads")
		assertTrue(next.contains("client_id"), "the client is what the page cannot be rendered without")
		assertTrue(next.contains("state"), "without the state the library refuses the decision")
	}

	/**
	 * Built from the three parameters this page declares, not copied off the query string —
	 * so a crafted request cannot post its own text through the login screen's URL bar.
	 */
	@Test
	fun `the return URL is rebuilt from what this page reads, never reflected`() {
		register(clientId = "claude-code", name = "Claude Code")
		SecurityContextHolder.clearContext()
		arriveWith("client_id=claude-code&scope=kanso%3Aread&state=s&smuggled=surprise")

		val location = controller
			.consent(clientId = "claude-code", scope = "kanso:read", state = "s")
			.headers.location!!.toString()

		assertFalse(location.contains("smuggled"), "a parameter this page never reads is not its business to echo")
	}

	@Test
	fun `an unknown client is refused rather than shown with a blank name`() {
		actAs(member())
		assertFailsWith<BadRequestException> {
			controller.consent(clientId = "not-registered", scope = "kanso:read", state = "s")
		}
	}

	@Test
	fun `an unknown scope is refused rather than rendered`() {
		register(clientId = "claude-code", name = "Claude Code")
		actAs(member())
		assertFailsWith<IllegalArgumentException> {
			controller.consent(clientId = "claude-code", scope = "kanso:read kanso:everything", state = "s")
		}
	}
}

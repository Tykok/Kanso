package dev.kanso.oauth

import dev.kanso.MockMvcTest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One line of `application.yml`, asserted through the chain that is the whole of its effect.
 *
 * The distribution image puts Caddy in front of the JVM and terminates TLS there, so every
 * request Boot sees arrives on plain `http` over the loopback and the only surviving record
 * of what the *browser* asked for is `X-Forwarded-Proto`. `server.forward-headers-strategy:
 * framework` is what makes Boot register a `ForwardedHeaderFilter` to fold that header back
 * into the request — and nothing else in this repository mentions the property, so deleting
 * it is a one-character edit that no unit test could notice. Hence MockMvc: the filter is a
 * bean, the assertion has to arrive through the filter chain that holds it, and a test that
 * called a controller method directly would have walked past the only thing under test.
 *
 * `/oauth/consent` is the probe, and not for convenience. `ConsentController.returnUrl`
 * builds this page's own URL with `ServletUriComponentsBuilder.fromCurrentContextPath()`,
 * hands it to the browser as `?next=`, and stashes the same string for
 * [ReturnUrlSuccessHandler] to compare against `originOf(request)` when the member returns
 * from a provider. Unwrapped, both ends read the same unwrapped request, both say `http`,
 * the comparison *passes* — and a member on an `https` page is redirected to
 * `http://…/oauth/consent`. A downgrade that no amount of testing either half in isolation
 * can see, because the two halves agree with each other and disagree with the browser.
 *
 * The louder symptom is out of this suite's reach rather than absent: `application-test.yml`
 * sets `kanso.auth.mode: dev`, so there is no `oauth2Login` here and no
 * `redirect_uri=http://…/login/oauth2/code/google` for Google to refuse. Same filter, same
 * one line, and this is the half that can be asserted without a real provider.
 */
class ForwardedHeadersTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var clients: RegisteredClientRepository

	/**
	 * The controller refuses an unregistered client before it redirects anybody, so without
	 * a row here this would be asserting on that refusal and never reach the URL under test
	 * — the same reason [ConsentRouteTest] registers one. A fresh id each run because this
	 * class is not `@Transactional` and the row outlives the test.
	 */
	private val clientId = "forwarded-test-${UUID.randomUUID()}"

	@BeforeEach
	fun register() {
		clients.save(
			RegisteredClient.withId(UUID.randomUUID().toString())
				.clientId(clientId)
				.clientName("Claude Code")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://127.0.0.1:8765/callback")
				.apply { OAuthScopes.ALL.forEach { scope(it) } }
				.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
				.build(),
		)
	}

	@Test
	fun `a request forwarded over https gets an https way back, not a downgrade`() {
		val response = mvc.get(CONSENT_PAGE) {
			param("client_id", clientId)
			param("scope", OAuthScopes.READ)
			param("state", "s")
			// Both are what Caddy sends without being asked, and both are needed to state
			// the case honestly: a proxy that rewrote only the scheme would not exist.
			header("X-Forwarded-Proto", "https")
			header("X-Forwarded-Host", "kanso.example.com")
			// `application-test.yml` runs in dev mode, where `DevAuthenticationFilter`
			// authenticates anything that arrives without a principal — and a member with a
			// session is shown the consent page rather than sent to log in, which is the
			// branch that does not build a return address. See [ConsentRouteTest].
			with(anonymous())
		}.andReturn().response

		assertEquals(302, response.status, "the anonymous branch is the one that writes a way back")

		val next = UriComponentsBuilder.fromUriString(response.getHeader("Location").orEmpty())
			.build()
			.queryParams
			.getFirst("next")
			?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

		assertEquals(
			"https://kanso.example.com$CONSENT_PAGE",
			next?.substringBefore('?'),
			"an http way back sends a member off an https page — and is what Google refuses",
		)
	}
}

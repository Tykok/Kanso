package dev.kanso.oauth

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 8707 audience binding, at the first of its two enforcement points.
 *
 * The library carries `resource` and validates nothing — the spike established that by
 * driving a real authorisation — so without this a client asks for a token bound to
 * somebody else's resource and gets one. That is the confused-deputy shape, and the
 * reason this file exists rather than a comment saying the library handles it.
 *
 * Refusing here rather than only at `/api/mcp` matters because the member is still on
 * the screen: they find out before consenting, not after their agent holds a token that
 * never works.
 *
 * A plain test, no Spring. `OAuth2AuthorizationCodeRequestAuthenticationContext.with` is
 * public, which makes the rule assertable on its own — and it has to be, because the
 * test profile runs in dev mode where the whole chain is absent.
 */
class ResourceValidatorTest {

	private val ours = "https://kanso.example.com/api/mcp"

	private val client: RegisteredClient = RegisteredClient.withId("client-1")
		.clientId("client-1")
		.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
		.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
		.redirectUri("http://127.0.0.1:9000/callback")
		.scope(OAuthScopes.READ)
		.build()

	private fun context(vararg resource: String): OAuth2AuthorizationCodeRequestAuthenticationContext {
		val parameters: Map<String, Any> = when (resource.size) {
			0 -> emptyMap()
			1 -> mapOf("resource" to resource.single())
			else -> mapOf("resource" to arrayOf(*resource))
		}
		val token = OAuth2AuthorizationCodeRequestAuthenticationToken(
			"https://kanso.example.com/oauth2/authorize",
			"client-1",
			UsernamePasswordAuthenticationToken("a-user-id", null, emptyList()),
			"http://127.0.0.1:9000/callback",
			"state-1",
			setOf(OAuthScopes.READ),
			parameters,
		)
		return OAuth2AuthorizationCodeRequestAuthenticationContext.with(token)
			// The builder requires it even though this rule never reads it: the delegate
			// it wraps — the redirect-uri validator — does.
			.registeredClient(client)
			.build()
	}

	private fun validator(default: Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> = Consumer { }) =
		ResourceValidator(default) { ours }

	@Test
	fun `a resource naming this server passes`() {
		validator().accept(context(ours))
	}

	@Test
	fun `a resource naming another server is refused`() {
		val failure = assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			validator().accept(context("https://someone-else.example.com/api/mcp"))
		}
		assertEquals("invalid_target", failure.error.errorCode)
		assertTrue(
			failure.error.description?.contains(ours) == true,
			"the refusal names what this server would have accepted",
		)
	}

	/**
	 * The refusal used to quote the `resource` values back. They travel to the client as
	 * `error_description` on a redirect the browser follows, and they are the caller's own
	 * text — a `%23` in one decodes to `#`, ends the query and pushes `iss` into the
	 * fragment. `IssuerAppendingFailureHandler` now escapes what it appends, so that
	 * particular trick is closed twice; this closes the class of it, and costs the client
	 * nothing it does not already know, since it chose the value.
	 */
	@Test
	fun `the refusal does not read the caller's own text back out`() {
		val failure = assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			validator().accept(context("https://someone-else.example.com/%23/api/mcp"))
		}

		assertFalse(
			failure.error.description!!.contains("someone-else"),
			"a description a caller can write is a description that ends up in a header a browser follows",
		)
		assertTrue(failure.error.description!!.contains(ours), "what this server accepts is still said")
	}

	@Test
	fun `a missing resource is refused, because absence is not permission`() {
		// A token with no recorded audience is a token good everywhere. Treating an
		// absent `resource` as consent is exactly how the confused deputy gets rebuilt.
		val failure = assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			validator().accept(context())
		}
		assertEquals("invalid_target", failure.error.errorCode)
	}

	@Test
	fun `two resources are refused, because a token has one audience`() {
		// The spike found repeated parameters arrive as an Array<String>. One value or a
		// refusal — binding a token to two resources binds it to neither.
		assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			validator().accept(context(ours, "https://someone-else.example.com/api/mcp"))
		}
	}

	@Test
	fun `delegating to the library's whole default keeps scope validation alive`() {
		// setAuthenticationValidator *replaces*. Wrapping only the redirect-uri half —
		// which is what the plan specified — would silently drop the scope check, and a
		// client could ask for a scope it was never registered for. This is the assertion
		// that notices.
		val token = OAuth2AuthorizationCodeRequestAuthenticationToken(
			"https://kanso.example.com/oauth2/authorize",
			"client-1",
			UsernamePasswordAuthenticationToken("a-user-id", null, emptyList()),
			"http://127.0.0.1:9000/callback",
			"state-1",
			setOf(OAuthScopes.WRITE),
			mapOf("resource" to ours),
		)
		val context = OAuth2AuthorizationCodeRequestAuthenticationContext.with(token)
			.registeredClient(client)
			.build()

		val failure = assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			ResourceValidator(OAuth2AuthorizationCodeRequestAuthenticationValidator()) { ours }.accept(context)
		}
		assertEquals("invalid_scope", failure.error.errorCode, "the client is registered for read only")
	}

	@Test
	fun `the redirect-uri validator runs before this one`() {
		// Order is the rule, not a detail: an error on a request whose redirect_uri is
		// invalid must not be redirected anywhere. The library's own validator decides
		// that, so it goes first and its exception wins.
		val calls = mutableListOf<String>()
		val recording = Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> { calls += "redirect_uri" }
		assertFailsWith<OAuth2AuthorizationCodeRequestAuthenticationException> {
			ResourceValidator(recording) { ours }.accept(context("https://someone-else.example.com/api/mcp"))
		}
		assertEquals(listOf("redirect_uri"), calls, "the delegate must have been consulted first")
	}
}

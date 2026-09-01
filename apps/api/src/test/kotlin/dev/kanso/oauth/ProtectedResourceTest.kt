package dev.kanso.oauth

import dev.kanso.PostgresTest
import dev.kanso.mcp.McpChallenge
import dev.kanso.mcp.McpResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 9728, which the MCP specification makes a MUST for a protected server: this
 * document is how a client that has nothing finds out where to ask for something.
 *
 * Asserted field by field because every one of them is a client's only source for that
 * value — a wrong `resource` here produces a token bound to an audience this server
 * will then reject, and the symptom is a 401 that looks like a bug in the client.
 *
 * Every URL is derived from the request rather than configured, for the reason
 * `NotionOAuth` derives its own: an instance behind a different hostname than the one in
 * a property file would publish a document telling clients to go somewhere that does not
 * answer. So the test has to supply a request — `PostgresTest` starts no web environment,
 * and without one the derivation throws rather than guessing.
 */
class ProtectedResourceTest : PostgresTest() {

	@Autowired lateinit var controller: ProtectedResourceController

	@Autowired lateinit var objectMapper: ObjectMapper

	@BeforeTest
	fun onAnOrigin() {
		val request = MockHttpServletRequest("GET", McpChallenge.RESOURCE_METADATA_PATH).apply {
			scheme = "https"
			serverName = "kanso.example.com"
			serverPort = 443
		}
		RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
	}

	@AfterTest
	fun offIt() {
		RequestContextHolder.resetRequestAttributes()
	}

	@Test
	fun `the document names this server as the resource, without a trailing slash`() {
		val doc = controller.metadata()
		assertTrue(doc.resource.endsWith("/api/mcp"), "the canonical URI is the MCP endpoint itself")
		assertTrue(doc.resource.startsWith("http"), "a resource identifier needs its scheme")
		assertFalse(doc.resource.contains("#"), "a canonical URI carries no fragment")
	}

	@Test
	fun `it offers both scopes and only the bearer method`() {
		val doc = controller.metadata()
		assertEquals(listOf("kanso:read", "kanso:write"), doc.scopesSupported)
		assertEquals(listOf("header"), doc.bearerMethodsSupported, "a token in a query string ends up in a log")
	}

	@Test
	fun `it points at exactly one authorization server, this one`() {
		val doc = controller.metadata()
		assertEquals(1, doc.authorizationServers.size)
		assertTrue(doc.authorizationServers.single().startsWith("http"))
	}

	@Test
	fun `the challenge tells a client where to read that document, and what to ask for`() {
		val header = McpChallenge.header(OAuthScopes.ALL)
		assertTrue(header.startsWith("Bearer "))
		assertTrue(header.contains("resource_metadata="), "without this the client cannot start")
		assertTrue(header.contains("""scope="kanso:read kanso:write""""))
	}

	@Test
	fun `the wire names are RFC 9728's, not Kotlin's`() {
		// The field names are snake_case and a client matches them literally. Nothing else
		// in this codebase renames a field, so there is no house precedent to follow —
		// which makes this a round trip rather than a reading of the annotation.
		val json = objectMapper.writeValueAsString(controller.metadata())
		for (name in listOf("resource", "authorization_servers", "scopes_supported", "bearer_methods_supported")) {
			assertTrue(json.contains("\"$name\""), "$name is missing from the document a client reads")
		}
		assertFalse(json.contains("authorizationServers"), "camelCase leaked onto the wire")
	}

	@Test
	fun `the resource the document publishes is the one the validator will compare against`() {
		// One derivation, used by the document and by the authorise-time check. Two would
		// eventually disagree, and the symptom would be a token that is refused by the
		// server that issued it.
		assertEquals(McpResource.fromCurrentRequest(), controller.metadata().resource)
	}
}

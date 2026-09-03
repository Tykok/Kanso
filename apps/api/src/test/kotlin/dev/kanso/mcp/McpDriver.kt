package dev.kanso.mcp

import dev.kanso.auth.CurrentUser
import dev.kanso.repo.UserRepository
import org.springframework.boot.info.BuildProperties
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.transaction.PlatformTransactionManager

/**
 * How every MCP suite reaches the endpoint: the controller, the filter in front of it, and
 * the two lines that turn a `tools/call` into the text an agent would read.
 *
 * Here rather than repeated per suite, and that is not tidiness. This *is* the thing under
 * test — a suite that built its own chain could leave [McpBearerFilter] out and still pass
 * every assertion it makes about a tool, which would make the suite an argument that the
 * tools work when called by somebody who got past no door at all. One driver means a suite
 * cannot accidentally be weaker than its neighbour.
 *
 * `TestGrants` is the other half and already lives beside this, for the same reason.
 */
internal fun mcpMvc(
	build: BuildProperties,
	currentUser: CurrentUser,
	tools: List<McpTool>,
	authorizations: OAuth2AuthorizationService,
	clients: RegisteredClientRepository,
	users: UserRepository,
	transactionManager: PlatformTransactionManager,
): MockMvc = MockMvcBuilders.standaloneSetup(McpController(build, currentUser, tools))
	.addFilters<StandaloneMockMvcBuilder>(
		// `oidc`, because these suites run in dev mode and the filter's answer at
		// `/api/mcp` in dev mode is that there is no door at all — `McpProtocolTest` says
		// so at length. Standing the filter up as configured for a real instance is what
		// makes an assertion about a token mean anything.
		McpBearerFilter(authorizations, clients, users, transactionManager, authMode = "oidc"),
	)
	.build()

internal fun callBody(tool: String, arguments: String): String =
	"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""

/**
 * The text an agent reads back, whether the tool succeeded or refused.
 *
 * Unescaped by hand rather than parsed, because a Jackson round trip here would read the
 * response through the same library that wrote it — and `isError` versus `error` is
 * precisely the kind of thing that convention gets to rename on the way out. Reading the
 * raw string is the only way this suite sees what a client sees.
 */
internal fun textOf(result: ResultActionsDsl): String {
	val json = result.andReturn().response.contentAsString
	val marker = "\"text\":\""
	val start = json.indexOf(marker) + marker.length
	val end = json.indexOf("\"", start).let { first ->
		var i = first
		while (i > 0 && json[i - 1] == '\\') i = json.indexOf("\"", i + 1)
		i
	}
	return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"")
}

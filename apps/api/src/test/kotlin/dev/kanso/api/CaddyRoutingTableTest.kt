package dev.kanso.api

import dev.kanso.MockMvcTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `docker/Caddyfile` routes the API's paths outside `/api`, and this fails when it stops.
 *
 * The distribution image puts one origin in front of Next and the JVM, so every request is
 * the proxy's to place. Everything under `/api` is a rule anybody can hold in their head; the
 * rest of the table — `/oauth2`, `/login/oauth2`, `/oauth/consent`, `/connect/register` and
 * the `/.well-known` discovery documents — is a list, and a list rots the moment somebody
 * adds a controller.
 *
 * What makes it worth a test rather than a comment is *how* it rots. A new mapping outside
 * `/api` does not 500: it lands on Next, which answers its own 404 page as HTML, so the
 * caller gets a page rather than an error. Nothing in the API's logs records it, because the
 * request never reached the API — and the person who finds out is whoever deployed the
 * release rather than whoever wrote the controller.
 *
 * The same reasoning as `OAuthRoutesTest`, which this follows deliberately rather than
 * inventing a second shape: a set that has to stay in step with something else is worth a
 * test that says so, in the module that owns the set.
 *
 * Enumerated off `RequestMappingHandlerMapping` and never off a list somebody typed —
 * `UnguardedWriteTest` and `ReadOnlySeatLeakTest` are the house precedents — because the
 * route that goes missing is precisely the one nobody thought to type.
 *
 * On [MockMvcTest] because a handler mapping only exists in a servlet context, and that base
 * class is the one whose context the suite already caches: this costs a Spring context it
 * was going to pay for anyway, and no Postgres of its own.
 */
class CaddyRoutingTableTest : MockMvcTest() {

	/**
	 * Qualified by name for `ReadOnlySeatLeakTest`'s reason: the actuator contributes a
	 * second `RequestMappingHandlerMapping` for its own endpoints. Here that qualifier is
	 * load-bearing rather than tidy — the actuator's paths are deliberately *not* routed by
	 * the Caddyfile, so the unqualified bean would hand this test a pile of paths whose
	 * correct answer is "unreachable from outside, and that is the point".
	 */
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	lateinit var mappings: RequestMappingHandlerMapping

	@Test
	fun `every path the API serves outside api is routed to it by the Caddyfile`() {
		val routes = caddyRoutes()
		val outside = mappings.handlerMethods.keys
			.flatMap { it.pathPatternsCondition?.patternValues.orEmpty() }
			.filterNot { it.startsWith("/api/") || it == "/api" }
			.filterNot { it in NOT_ROUTED }
			.distinct()
			.sorted()

		val uncovered = outside.filterNot { pattern -> routes.any { it.covers(pattern) } }

		assertTrue(
			uncovered.isEmpty(),
			"${uncovered.size} path(s) the API serves outside `/api` are not routed to it by " +
				"`docker/Caddyfile`, so in the distribution image they land on Next and answer " +
				"its 404 page as HTML:\n" +
				uncovered.joinToString("\n") { "  $it" } +
				"\n\nAdd a route for each to the site block of `docker/Caddyfile`, one line, in the " +
				"shape `reverse_proxy <path> 127.0.0.1:8080` — the block is sorted by matcher " +
				"specificity, so the line can go anywhere in it. A path that is genuinely not " +
				"meant to be reachable from outside belongs in this test's `NOT_ROUTED` instead, " +
				"with the argument for why.\nThe Caddyfile currently routes: " +
				routes.joinToString(", ") { it.path },
		)
	}

	/**
	 * The enumeration found something, asserted separately so the guard above cannot pass
	 * on an empty set.
	 *
	 * Both halves, because each fails in its own silence. A handler mapping this test failed
	 * to reach yields no patterns and a green tick; a Caddyfile folded into `handle` blocks
	 * yields no routes and would at least go red — but red for a reason nobody would read as
	 * "the parser stopped understanding the file", which is what this says instead.
	 */
	@Test
	fun `the guard is reading both of the things it compares`() {
		assertTrue(
			mappings.handlerMethods.isNotEmpty(),
			"no request mappings were enumerated, so the guard above compares an empty set " +
				"against the Caddyfile and passes whatever the Caddyfile says",
		)
		assertTrue(
			caddyRoutes().size >= 5,
			"`docker/Caddyfile` parsed to fewer routes than the routing table has. Every route " +
				"has to stay one `reverse_proxy <path> <upstream>` line inside the site block; " +
				"folding them into `handle` blocks still satisfies Caddy and blinds this test.",
		)
	}

	/**
	 * A Caddy path matcher, and [covers] is the little of it this table uses.
	 *
	 * Caddy's `*` spans path separators, so a route ending in one under `/oauth2` claims
	 * `/oauth2/authorize` and everything below it. Matched against Spring's *pattern* rather
	 * than against a request: the two agree on every path in this table because none of them
	 * takes a variable, and a mapping that did would show up here as uncovered — which is the
	 * safe direction for a guard to be wrong in.
	 */
	private data class Route(val path: String) {
		private val matcher = Regex(path.split("*").joinToString(".*") { Regex.escape(it) })
		fun covers(pattern: String) = matcher.matches(pattern)
	}

	/**
	 * The `reverse_proxy` lines of `docker/Caddyfile` that carry a path.
	 *
	 * The catch-all — `reverse_proxy 127.0.0.1:3000`, no matcher — drops out on its own: its
	 * second token is an upstream and does not begin with `/`. That is the whole point of
	 * reading it rather than trusting it, since the catch-all is exactly the line that would
	 * make every path look covered.
	 */
	private fun caddyRoutes(): List<Route> = caddyfile().readLines()
		.map { it.trim() }
		.filter { it.startsWith("reverse_proxy ") }
		.mapNotNull { it.split(Regex("\\s+")).getOrNull(1) }
		.filter { it.startsWith("/") }
		.map { Route(it) }

	/**
	 * `docker/Caddyfile`, found by walking up from the module rather than by a relative path.
	 *
	 * Gradle runs this with `user.dir` at `apps/api`, but that is a fact about one runner:
	 * an IDE that sets it to the repository root, or a `--project-dir` invocation, would each
	 * put the file somewhere else. Walking up finds it under all three.
	 *
	 * And it fails rather than returning nothing. A guard test that goes green because it
	 * could not find its fixture is worse than no guard test — it is a green tick that
	 * actively asserts the thing it stopped checking.
	 */
	private fun caddyfile(): File {
		val start = File(System.getProperty("user.dir")).absoluteFile
		generateSequence(start) { it.parentFile }
			.map { File(it, "docker/Caddyfile") }
			.firstOrNull { it.isFile }
			?.let { return it }

		fail(
			"`docker/Caddyfile` was not found in $start or any directory above it, so this guard " +
				"could not check anything. It is failing rather than passing on purpose. If the " +
				"file moved, this test's search moves with it.",
		)
	}

	private companion object {

		/**
		 * Paths outside `/api` that the Caddyfile is right not to route, and why each one.
		 *
		 * `/error` is Spring's `BasicErrorController`. It is reached by a servlet *forward*
		 * from inside the container after a handler threw, never by a URL a client sent, so
		 * routing it would expose the error page as an endpoint and buy nothing.
		 *
		 * `/oauth/consent` and `/connect/register` are deliberately absent from this list:
		 * they are in the Caddyfile, which is where they belong. This set is for paths whose
		 * absence is a *decision*, and it is the only escape hatch this guard has — a path
		 * whose absence is a bug belongs in the Caddyfile, not here. `/v3/api-docs` was the
		 * first to test that line and went into the Caddyfile, which is the outcome this
		 * set exists to stay narrow enough to force.
		 */
		val NOT_ROUTED = setOf("/error")
	}
}

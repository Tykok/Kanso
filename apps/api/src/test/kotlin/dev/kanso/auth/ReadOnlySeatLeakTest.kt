package dev.kanso.auth

import dev.kanso.MockMvcTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.TicketAccess
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test this ticket exists to pass: a read-only seat cannot reach a single write.
 *
 * `PublicLeakTest` is the house precedent and this is its shape applied to a different
 * class of caller. The move both files make is the same one: do not assert about a list of
 * routes somebody typed out, because the route that leaks is the one nobody thought to
 * type. So the list comes from the application itself — every unsafe mapping Spring MVC
 * has registered, enumerated at runtime — and every one of them is *actually fired* as a
 * viewer and must come back 403.
 *
 * That is what makes this a tripwire rather than a snapshot. A controller added next month
 * by somebody who never opens this file arrives in the enumeration on its own, and either
 * its author put it in `ReadOnlySeat`'s exemption list on purpose or this test fails. There
 * is no third outcome, and in particular there is no outcome where a new write endpoint is
 * quietly reachable.
 *
 * Why the assertion can be this blunt — one status code for ninety endpoints, with no
 * fixture behind any of them — is `ReadOnlySeatInterceptor` running in `preHandle`. The
 * refusal happens before body binding, before path-variable conversion and before the
 * controller, so a nonsense UUID and an empty JSON body cannot produce a 400 that hides a
 * missing 403. If any of these ever answers something other than 403, the seat did not
 * hold, whatever the reason.
 */
@Transactional
class ReadOnlySeatLeakTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	/**
	 * Qualified by name: the actuator contributes a second `RequestMappingHandlerMapping` for
	 * its own endpoints, and this test is about the application's controllers.
	 */
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	lateinit var mappings: RequestMappingHandlerMapping

	private val viewer: User by lazy { person("A reader", InstanceRole.VIEWER) }
	private val member: User by lazy { person("A writer", InstanceRole.MEMBER) }

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "seat-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	/**
	 * One request, arriving through the whole filter chain as [who].
	 *
	 * The suite runs in dev mode, so identity is the `X-Kanso-User` header and
	 * `DevAuthenticationFilter` resolves it against a row that already exists — which is why
	 * these people are created with their role rather than provisioned by the header, since
	 * a header-provisioned account lands as a `MEMBER` and would assert nothing.
	 */
	private fun fire(who: User, method: String, path: String) = mvc.perform(
		MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(path))
			.header(DevAuthenticationFilter.HEADER, who.email)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"),
	).andReturn().response

	/**
	 * Every unsafe mapping the application has registered, as `"METHOD /pattern"`.
	 *
	 * Read off `RequestMappingHandlerMapping` rather than off a list in this file, for the
	 * reason the class comment gives. A mapping that declares no method answers all of them,
	 * so it is expanded into the four unsafe ones rather than skipped — the permissive case
	 * must not be the invisible one.
	 */
	private fun everyWrite(): List<String> = mappings.handlerMethods.keys
		.flatMap { info -> patternsOf(info).flatMap { pattern -> methodsOf(info).map { "$it $pattern" } } }
		.distinct()
		.sorted()

	private fun patternsOf(info: RequestMappingInfo): Set<String> =
		info.pathPatternsCondition?.patternValues.orEmpty()

	private fun methodsOf(info: RequestMappingInfo): List<String> =
		info.methodsCondition.methods
			.map { it.name }
			.filter { it in UNSAFE }
			.ifEmpty { if (info.methodsCondition.methods.isEmpty()) UNSAFE.toList() else emptyList() }

	/** `{id}` and friends filled with something that matches the segment and nothing else. */
	private fun urlFor(pattern: String): String =
		Regex("\\{[^}]+}").replace(pattern) { UUID.randomUUID().toString() }

	/**
	 * The assertion. Everything not deliberately exempted is refused, and refused *as a
	 * seat* — the sentence is checked as well as the code, because a 403 for some other
	 * reason (an admin-only endpoint saying "only the owner can do this") would let a
	 * genuinely unguarded route pass while looking green.
	 */
	@Test
	fun `every write in the application is refused to a read-only seat`() {
		val writes = everyWrite()
		// Without this the whole test passes on an empty enumeration, which is precisely how
		// a sweep like this rots into a green light that checks nothing.
		assertTrue(
			writes.size > 60,
			"the enumeration found only ${writes.size} unsafe mappings; the application has far more",
		)

		val swept = writes.filterNot { it in ReadOnlySeat.ALL }
		assertTrue(swept.size > 60, "the exemption list is swallowing the sweep: only ${swept.size} left to fire")

		val reached = mutableListOf<String>()
		for (write in swept) {
			val (method, pattern) = write.split(' ', limit = 2)
			val response = fire(viewer, method, urlFor(pattern))
			if (response.status != 403 || !response.contentAsString.contains(TicketAccess.READS_NOT_WRITES)) {
				reached += "$write -> ${response.status} ${response.contentAsString.take(160)}"
			}
		}

		assertEquals(
			emptyList(),
			reached,
			"a read-only seat reached ${reached.size} write(s). Each is either a route that must be " +
				"refused, or one somebody has to add to `ReadOnlySeat` on purpose:",
		)
	}

	/**
	 * The other half, and the reason the list above is a list and not a `403 everything`.
	 *
	 * A seat that could not set dark mode, clear its own inbox or fix a typo in its own name
	 * would be a worse product than a considered exemption list — and an exemption list
	 * nobody exercises is a claim rather than a feature. So each of these is driven to a
	 * real success and, where there is something to read back, read back.
	 */
	@Test
	fun `the seat still owns its own screen, its own inbox and its own account`() {
		val theme = mvc.perform(
			MockMvcRequestBuilders.put("/api/me/preferences")
				.header(DevAuthenticationFilter.HEADER, viewer.email)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"theme":"dark","density":"compact"}"""),
		).andReturn().response
		assertEquals(200, theme.status, "a reader may darken their own screen: ${theme.contentAsString}")
		assertTrue(theme.contentAsString.contains("\"theme\":\"dark\""), "and the choice is stored, not swallowed")

		val readAll = fire(viewer, "POST", "/api/notifications/read-all")
		assertEquals(200, readAll.status, "an inbox nobody can clear stops being an inbox: ${readAll.contentAsString}")

		val rename = mvc.perform(
			MockMvcRequestBuilders.put("/api/me")
				.header(DevAuthenticationFilter.HEADER, viewer.email)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"displayName":"A reader, spelled right"}"""),
		).andReturn().response
		assertEquals(200, rename.status, "a typo in your own name should not need an admin: ${rename.contentAsString}")

		// A bad `kind` on purpose: what is under test is that the *seat* let the request
		// through to the controller, and a 400 from `FavouriteKind.from` proves that better
		// than a 204 would — it can only have come from inside the handler.
		val pin = fire(viewer, "PUT", "/api/me/favourites/nonsense/${UUID.randomUUID()}")
		assertEquals(400, pin.status, "the pin reached the controller and was refused on its own terms")
		assertFalseContains(pin.contentAsString, "a sidebar pin is not the team's data")
	}

	/** A viewer sees the instance; that is the whole product. */
	@Test
	fun `a viewer reads exactly what a member reads`() {
		val paths = listOf("/api/teams", "/api/projects", "/api/tickets", "/api/people", "/api/me/favourites")
		for (path in paths) {
			val asViewer = mvc.perform(
				MockMvcRequestBuilders.get(path).header(DevAuthenticationFilter.HEADER, viewer.email),
			).andReturn().response
			val asMember = mvc.perform(
				MockMvcRequestBuilders.get(path).header(DevAuthenticationFilter.HEADER, member.email),
			).andReturn().response

			assertEquals(200, asViewer.status, "$path is a read, and a read seat is for reading")
			assertEquals(200, asMember.status, "$path has to answer the member too, or the comparison is empty")
		}

		// Compared rather than merely 200, on the one list where the two could plausibly
		// differ: `/api/tickets` is the product. Neither narrower nor wider — a seat that saw
		// less would not be the product Kanso is selling, and one that saw more would be a leak.
		val viewerSaw = mvc.perform(
			MockMvcRequestBuilders.get("/api/tickets").header(DevAuthenticationFilter.HEADER, viewer.email),
		).andReturn().response.contentAsString
		val memberSaw = mvc.perform(
			MockMvcRequestBuilders.get("/api/tickets").header(DevAuthenticationFilter.HEADER, member.email),
		).andReturn().response.contentAsString
		assertEquals(memberSaw, viewerSaw, "the seat reads the same backlog, or it is not a seat")
	}

	/**
	 * The exemption list, checked for rot and for creep.
	 *
	 * For rot: every entry has to name a mapping that exists, or it is a permission granted
	 * to nothing — and, worse, a line a reviewer would read as covering the route it used to
	 * name. For creep: `PublicRoutesTest` guards its list the same way, so that "just add one
	 * while you are in there" fails the suite rather than shipping.
	 */
	@Test
	fun `every exemption names a live route, and none of them is the team's data`() {
		val registered = mappings.handlerMethods.keys
			.flatMap { info -> patternsOf(info).flatMap { p -> info.methodsCondition.methods.map { "${it.name} $p" } } }
			.toSet()

		for (entry in ReadOnlySeat.ALL) {
			assertTrue(entry in registered, "`$entry` is exempted from the read-only seat but no controller serves it")
		}

		for (entry in ReadOnlySeat.OWN_SCREEN + ReadOnlySeat.OWN_ACCOUNT) {
			val path = entry.substringAfter(' ')
			assertTrue(
				OWN.any { path.startsWith(it) },
				"`$entry` is exempted as personal, but $path is not under any of $OWN — " +
					"if it really is personal, say so by putting it there; if it is not, it does not belong here",
			)
		}

		assertEquals(
			ReadOnlySeat.ALL.size,
			ReadOnlySeat.ALL.toSet().size,
			"the same route is exempted twice, which means one of the two arguments for it is unread",
		)
	}

	private fun assertFalseContains(body: String, why: String) =
		assertTrue(!body.contains(TicketAccess.READS_NOT_WRITES), "$why — but the seat refused it: $body")

	private companion object {
		val UNSAFE = setOf("POST", "PUT", "PATCH", "DELETE")

		/** Where a write that is genuinely about one person, and no one else, can live. */
		val OWN = listOf("/api/me", "/api/notifications", "/api/oauth/grants")
	}
}

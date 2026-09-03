package dev.kanso.auth

import dev.kanso.MockMvcTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The rung above `ReadOnlySeatLeakTest`: not "may a viewer write", but "does this write
 * know who is asking at all".
 *
 * That test answers the seat question completely and cannot answer this one. It fires
 * every unsafe mapping as a `VIEWER` and demands a 403, which `ReadOnlySeatInterceptor`
 * produces in `preHandle` — *before* the controller. So a route that reaches the database
 * with no idea who called it passes it green, because the seat turned the viewer away at
 * a door the route never opened. `PUT /api/users/{id}/notion-person` was exactly that:
 * refused to a viewer by the interceptor, wide open to every member behind it, taking a
 * colleague's user id straight off the path and rewriting their Notion identity.
 *
 * So this file asks the question the seat cannot, in two ways, and neither substitutes
 * for the other:
 *
 * 1. [every write can name the actor it is acting for] enumerates, the way both house
 *    precedents do — off `RequestMappingHandlerMapping`, never off a list somebody typed,
 *    because the route that leaks is the one nobody thought to type. It is structural and
 *    it is coarse on purpose: it proves the handler's controller can *reach* the acting
 *    identity, which is the shape all four holes in this sweep shared and the one a new
 *    controller written by somebody who never opens this file will share too.
 * 2. The three tests after it are behavioural and specific. They fire the routes this
 *    sweep guarded, as an ordinary member, and check the colleague's row afterwards —
 *    because "the request was refused" and "nothing was written" are two claims, and only
 *    the second is the one the owner asked for.
 *
 * The two openly-unguarded routes are pinned too, in
 * [the mirror's badge and its retry button stay open to every member]. An exemption
 * nobody exercises rots into a claim; worse, the next reader tightening this controller
 * would take the sync badge off every member's status bar and find out from a screenshot.
 * That pair is one test and its complement — [the mirror's identifiers are the
 * configurator's alone] — because `KAN-53` answered the badge's disclosure by cutting the
 * response in two rather than by closing the route, and a claim about who may ask is only
 * half of that decision.
 */
@Transactional
class UnguardedWriteTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository

	/** Qualified by name for `ReadOnlySeatLeakTest`'s reason: the actuator contributes one too. */
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	lateinit var mappings: RequestMappingHandlerMapping

	private val member: User by lazy { person("An ordinary member", InstanceRole.MEMBER) }
	private val admin: User by lazy { person("An administrator", InstanceRole.ADMIN) }

	/** The colleague nobody in this file is entitled to rewrite. */
	private val colleague: User by lazy { person("A colleague", InstanceRole.MEMBER) }

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "unguarded-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private fun fire(who: User, method: String, path: String, body: String = "{}") = mvc.perform(
		MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(path))
			.header(DevAuthenticationFilter.HEADER, who.email)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body),
	).andReturn().response

	// --- 1. the enumeration --------------------------------------------------

	/**
	 * Every unsafe mapping's controller holds a [CurrentUser], or is named below.
	 *
	 * What this proves and what it does not, stated plainly because a guard test that
	 * oversells itself is worse than none. It proves the handler's class *can* ask who is
	 * calling. It cannot prove the handler then asks — `ProjectController` injects
	 * `CurrentUser`, hands it to `archive` and `delete`, and drops it for `create` and
	 * `update`, which this test therefore passes and the report records as an open finding.
	 * Its value is the other direction: a controller that never took the dependency cannot
	 * be checking anything, in any of its methods, and three of this sweep's four holes
	 * were precisely that. There is no version of "I forgot" that this misses.
	 *
	 * Direct field, not one hop through a service, and that narrowness is why [ACTORLESS]
	 * is a list rather than a rule: `GrantsController` is genuinely correct and would fail
	 * a looser check for a worse reason. Naming it costs one line and makes the argument
	 * readable.
	 */
	@Test
	fun `every write can name the actor it is acting for`() {
		val writes = everyWrite()
		// Without this the whole test passes on an empty enumeration, which is exactly how a
		// sweep like this rots into a green light that checks nothing.
		assertTrue(
			writes.size > 60,
			"the enumeration found only ${writes.size} unsafe mappings; the application has far more",
		)

		val blind = writes
			.filterNot { (_, controller) -> controller in ACTORLESS }
			.filterNot { (_, controller) -> holdsCurrentUser(controller) }
			.map { (route, controller) -> "$route -> $controller" }
			.distinct()
			.sorted()

		assertEquals(
			emptyList(),
			blind,
			"${blind.size} write(s) are served by a controller that never asked who is calling. Each is " +
				"either a route that has to check the actor, or one somebody has to add to `ACTORLESS` " +
				"with the argument for why it does not:",
		)
	}

	/** `"METHOD /pattern"` to the simple name of the controller serving it. */
	private fun everyWrite(): List<Pair<String, String>> = mappings.handlerMethods.entries
		.flatMap { (info, handler) ->
			patternsOf(info).flatMap { pattern ->
				methodsOf(info).map { "$it $pattern" to handler.beanType.simpleName }
			}
		}
		.distinct()

	private fun patternsOf(info: RequestMappingInfo): Set<String> =
		info.pathPatternsCondition?.patternValues.orEmpty()

	/**
	 * A mapping that declares no method answers all of them, so it expands into the four
	 * unsafe ones rather than being skipped: the permissive case must not be the invisible
	 * one. `ReadOnlySeatLeakTest` reads its enumeration the same way, for the same reason.
	 */
	private fun methodsOf(info: RequestMappingInfo): List<String> =
		info.methodsCondition.methods
			.map { it.name }
			.filter { it in UNSAFE }
			.ifEmpty { if (info.methodsCondition.methods.isEmpty()) UNSAFE.toList() else emptyList() }

	private fun holdsCurrentUser(controller: String): Boolean =
		mappings.handlerMethods.values
			.first { it.beanType.simpleName == controller }
			.beanType
			.declaredFields
			.any { CurrentUser::class.java.isAssignableFrom(it.type) }

	// --- 2. the holes this sweep closed --------------------------------------

	/**
	 * The hole the owner found by accident, and the one the rest of the sweep grew from.
	 *
	 * Two assertions and the second is the one that matters. A 403 says the request was
	 * turned away; the reread says the colleague's identity is still theirs, which is the
	 * thing that was actually at stake — the mirror fills a Notion `people` property from
	 * this column, so rewriting it makes a colleague's work show up under somebody else's
	 * name in a workspace this instance does not control.
	 */
	@Test
	fun `a member cannot rewrite a colleague's Notion identity`() {
		val body = """{"notionPersonId":"11111111-2222-3333-4444-555555555555"}"""

		val refused = fire(member, "PUT", "/api/users/${colleague.id}/notion-person", body)
		assertEquals(403, refused.status, "a colleague's Notion identity is not a member's to set: ${refused.contentAsString}")
		assertEquals(
			null,
			users.findById(colleague.id)?.notionPersonId,
			"the request was refused and the column was written anyway, which is the worse half of the bug",
		)

		// The other side of it: the rule is `NotionPeople.link`'s, so the people who own
		// that table still own it here. A guard that refused everybody would be a removal
		// dressed as a fix.
		val allowed = fire(admin, "PUT", "/api/users/${colleague.id}/notion-person", body)
		assertEquals(200, allowed.status, "an admin matches Notion people to accounts: ${allowed.contentAsString}")
		assertEquals(
			"11111111-2222-3333-4444-555555555555",
			users.findById(colleague.id)?.notionPersonId,
			"and the match is stored, not swallowed",
		)
	}

	/**
	 * The mirror's two instance-configuration levers, which `/api/admin` named before any
	 * of them checked anybody.
	 *
	 * `reconcile` is asserted to succeed for an admin because it is the one that has to
	 * work with no Notion configured — it only enqueues. `bootstrap` is asserted merely not
	 * to be a 403: with no token in the test instance it refuses on its own terms, which is
	 * the proof that the request got past this guard and was judged on its merits.
	 */
	@Test
	fun `a member cannot bootstrap or reconcile the mirror`() {
		val bootstrap = fire(member, "POST", "/api/admin/notion/bootstrap")
		assertEquals(403, bootstrap.status, "creating the mirror's databases is configuration: ${bootstrap.contentAsString}")

		val reconcile = fire(member, "POST", "/api/admin/notion/reconcile")
		assertEquals(403, reconcile.status, "rewriting every page in the instance is too: ${reconcile.contentAsString}")

		assertNotEquals(
			403,
			fire(admin, "POST", "/api/admin/notion/bootstrap").status,
			"an admin reaches the bootstrap and is refused by Notion's absence, not by this guard",
		)
		assertEquals(
			200,
			fire(admin, "POST", "/api/admin/notion/reconcile").status,
			"and reconcile needs no Notion at all: it only fills the outbox",
		)
	}

	/**
	 * The docs index, whose upsert key is a Notion page id and not a row id — so a member
	 * who could reach it could repoint the title and the URL of a page every ticket in the
	 * instance links to, at a target of their choosing.
	 */
	@Test
	fun `a member cannot repoint a doc the whole instance links to`() {
		val page = UUID.randomUUID().toString()
		val body = { url: String -> """{"notionPageId":"$page","title":"Runbook","url":"$url"}""" }

		val refused = fire(member, "POST", "/api/docs", body("https://example.invalid/elsewhere"))
		assertEquals(403, refused.status, "registering a Notion page binds the instance: ${refused.contentAsString}")

		val allowed = fire(admin, "POST", "/api/docs", body("https://notion.so/runbook"))
		assertEquals(201, allowed.status, "an admin binds one: ${allowed.contentAsString}")
	}

	// --- 3. what stays open, on purpose --------------------------------------

	/**
	 * Neither of these is an oversight, and the point of asserting them is that the next
	 * reader tightening `SyncAdminController` has to argue with a test rather than find out
	 * from a screenshot.
	 *
	 * The badge, because every shell in the web app reads it every ten seconds for every
	 * member. The retry, because it is drawn on the inbox every member has, and requeuing
	 * work that already failed creates nothing, destroys nothing and discloses nothing.
	 *
	 * `KAN-53` narrowed *what* the badge answers without narrowing *who* may ask, so the
	 * open access asserted here is the same claim it always was and the second half is new:
	 * the shape a member receives holds no Notion identifier. Both halves have to be
	 * asserted together, because either one alone is satisfied by the bug the other
	 * catches — a guard on this route passes "no ids reached a member", and the old
	 * over-full response passes "a member may ask".
	 */
	@Test
	fun `the mirror's badge and its retry button stay open to every member`() {
		val badge = mvc.perform(
			MockMvcRequestBuilders.get("/api/admin/sync").header(DevAuthenticationFilter.HEADER, member.email),
		).andReturn().response
		assertEquals(200, badge.status, "the status bar's sync badge is every member's: ${badge.contentAsString}")

		// Asserted as absent fields rather than by matching id-shaped strings: the summary is
		// a closed shape, and naming the keys is what makes a reader who adds one back read
		// this test as being about them.
		val summary = badge.contentAsString
		for (leak in listOf("databases", "databaseId", "dataSourceId", "cursors", "lastError")) {
			assertTrue(
				leak !in summary,
				"`$leak` is in the badge's answer: the ids name pages in a workspace Kanso does not " +
					"own, and the errors quote Notion about them — both are `/sync/detail`'s. $summary",
			)
		}

		val retry = fire(member, "POST", "/api/admin/sync/retry-failed")
		assertEquals(200, retry.status, "Retry on a failure row is every member's too: ${retry.contentAsString}")
	}

	/**
	 * The other side of that split, which is what keeps it from being a deletion: the ids
	 * and the mirror's error strings still have a reader, and it is the person who connected
	 * Notion.
	 *
	 * Fired as a GET rather than through [fire] because this is a read — the only guard in
	 * this file that is, and it is here rather than with the read-leak tests because it is
	 * the same sweep's decision as the badge above it and reads with it or not at all.
	 */
	@Test
	fun `the mirror's identifiers are the configurator's alone`() {
		val refused = mvc.perform(
			MockMvcRequestBuilders.get("/api/admin/sync/detail").header(DevAuthenticationFilter.HEADER, member.email),
		).andReturn().response
		assertEquals(403, refused.status, "a member has no use for the database ids: ${refused.contentAsString}")

		val allowed = mvc.perform(
			MockMvcRequestBuilders.get("/api/admin/sync/detail").header(DevAuthenticationFilter.HEADER, admin.email),
		).andReturn().response
		assertEquals(200, allowed.status, "and an admin diagnosing the mirror does: ${allowed.contentAsString}")
		assertTrue(
			"databases" in allowed.contentAsString,
			"the detailed shape is what an admin gets, or the guard bought nothing: ${allowed.contentAsString}",
		)
	}

	private companion object {
		val UNSAFE = setOf("POST", "PUT", "PATCH", "DELETE")

		/**
		 * Controllers whose writes correctly never ask who is calling, and why.
		 *
		 * Three arguments, not one, which is why they are listed rather than pattern-matched:
		 *
		 * `PublicController` and `ClientRegistrationController` answer callers with no
		 * session by construction — `PublicRoutes.OPEN_POST` and `OAuthRoutes.OPEN_POST` open
		 * them in `SecurityConfig`, and the roadmap vote is keyed on an anonymous voter key
		 * rather than on a Kanso account at all. There is no actor to name.
		 *
		 * `GrantsController` has one and refuses to hold it, which is stronger than holding
		 * it: `GrantService` reads the member off the security context itself and takes no
		 * principal parameter on either method, so there is no argument any caller could pass
		 * that would point it at somebody else's connected applications. The isolation is a
		 * property of the signature. Naming it here rather than loosening the check to "or a
		 * service that holds one" keeps that argument visible.
		 *
		 * `ApiTokenController` is that same argument, on the credential this time rather than
		 * on the grant, and it is the case where it matters most: `ApiTokenService.list`,
		 * `create` and `revoke` take no user id, so nothing a controller could pass — a path
		 * variable, a body field, a query parameter — could aim one of them at somebody
		 * else's API tokens. `revoke` goes further and puts the owner inside the `DELETE`'s
		 * own predicate, so there is not even a window between "is this yours" and "delete
		 * it". A `CurrentUser` field here would be an unused dependency whose only effect is
		 * to satisfy this sweep, which is the wrong direction to move a guard in.
		 *
		 * `BasicErrorController` is Spring's, serves `/error`, and is reached by a forward
		 * rather than by a client.
		 */
		val ACTORLESS = setOf(
			"PublicController",
			"ClientRegistrationController",
			"GrantsController",
			"ApiTokenController",
			"BasicErrorController",
		)
	}
}

package dev.kanso.api

import dev.kanso.MockMvcTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.RequestBaseRepository
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who may point the instance at a Notion base of requests.
 *
 * Through the filter chain, because the question is one `RequestBaseServiceTest` cannot
 * ask: that one calls the service directly, and a route left off `SecurityConfig` or a
 * guard the controller forgot are both already behind it.
 *
 * The rule is deliberately *not* the one `KAN-55` settled for Notion discovery. That
 * arbitration kept `sources`, `schema`, `preview` and `people-seen` open to any member,
 * because a non-configurator running an import is the flow they were written for.
 * Registering a base is the other act: it wires the instance permanently and files
 * somebody else's pages into a team the registrant need not belong to. So the split lives
 * here, asserted, rather than being a difference a later reader has to guess the reason
 * for — which is exactly the silence `KAN-55` said was worse than either answer.
 */
@Transactional
class RequestBaseRoutesTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var bases: RequestBaseRepository

	private val member: User by lazy { person("An ordinary member", InstanceRole.MEMBER) }
	private val admin: User by lazy { person("An administrator", InstanceRole.ADMIN) }

	private fun person(name: String, role: InstanceRole) = users.createLocalUser(
		email = "reqroutes-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	private val team by lazy {
		teams.create(admin, "Requests", "T${UUID.randomUUID().toString().take(4).uppercase()}", null)
	}

	private fun fire(who: User, method: String, path: String, body: String = "{}") = mvc.perform(
		MockMvcRequestBuilders.request(HttpMethod.valueOf(method), URI.create(path))
			.header(DevAuthenticationFilter.HEADER, who.email)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body),
	).andReturn().response

	@Test
	fun `only a configurator may register a requests base, and nothing is written when refused`() {
		val dataSourceId = "ds-${UUID.randomUUID()}"
		val body = """{"dataSourceId":"$dataSourceId","databaseId":"db-x","teamId":"${team.id}"}"""

		val refused = fire(member, "POST", "/api/admin/notion/requests", body)
		assertEquals(403, refused.status, "a member does not wire the instance: ${refused.contentAsString}")
		// The second claim, which the status code alone does not make: "it was refused" and
		// "nothing was written" are two facts, and only the second is the one that matters.
		assertNull(bases.find(dataSourceId), "and the refusal wrote nothing")

		val allowed = fire(admin, "POST", "/api/admin/notion/requests", body)
		assertEquals(200, allowed.status, "and a configurator does: ${allowed.contentAsString}")
		assertEquals(team.id, bases.find(dataSourceId)?.teamId)
	}

	@Test
	fun `reading and unregistering are the configurator's too`() {
		assertEquals(403, fire(member, "GET", "/api/admin/notion/requests").status)

		val listed = fire(admin, "GET", "/api/admin/notion/requests")
		assertEquals(200, listed.status, listed.contentAsString)
		assertTrue(listed.contentAsString.startsWith("["), "a list: ${listed.contentAsString}")

		val dataSourceId = "ds-${UUID.randomUUID()}"
		fire(admin, "POST", "/api/admin/notion/requests", """{"dataSourceId":"$dataSourceId","databaseId":"db-y","teamId":"${team.id}"}""")

		assertEquals(403, fire(member, "DELETE", "/api/admin/notion/requests/$dataSourceId").status)
		assertEquals(team.id, bases.find(dataSourceId)?.teamId, "a member's DELETE stopped no siphon")

		/*
		 * `204`, and the number is the assertion rather than a detail of it.
		 *
		 * This read `200` — not because anybody chose it, but because a `Unit`-returning
		 * handler with no status annotation answers that, and the test was written against
		 * what the route did. It made the test agree with a defect: `lib/api/core.ts`'s
		 * `request` short-circuits on `204` alone and calls `response.json()` otherwise,
		 * which throws on an empty body, so the `Stop` button on `request-bases.tsx` deleted
		 * the row and was told it had failed. Every other delete in this package already
		 * carried `@ResponseStatus(NO_CONTENT)`.
		 */
		assertEquals(204, fire(admin, "DELETE", "/api/admin/notion/requests/$dataSourceId").status)
		assertNull(bases.find(dataSourceId))
	}
}

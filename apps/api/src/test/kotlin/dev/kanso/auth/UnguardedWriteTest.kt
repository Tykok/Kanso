package dev.kanso.auth

import dev.kanso.MockMvcTest
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
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
 */
@Transactional
class UnguardedWriteTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository

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
		assertEquals(
			403,
			refused.status,
			"a colleague's Notion identity is not a member's to set: ${refused.contentAsString}",
		)
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
}

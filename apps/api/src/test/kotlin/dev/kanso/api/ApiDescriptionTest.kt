package dev.kanso.api

import dev.kanso.MockMvcTest
import dev.kanso.auth.KansoLocalUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.oauth.OAuthRoutes
import dev.kanso.publik.PublicRoutes
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The description a third party reads instead of the Kotlin — `KAN-95`.
 *
 * Written after `KAN-29`'s toy integration, where the absence was measured rather than
 * supposed: three request bodies had to be learnt by opening controllers, and two of the
 * refusals that sent me there did not name the field they were refusing.
 *
 * What is asserted is deliberately not the whole document — that would be a snapshot of a
 * generator's output, red on every upgrade and never read. It is the three claims that
 * make the document worth serving: that it exists, that it describes routes this
 * application actually has, and that it carries the shape of a body a caller has to
 * *send*, which is the half a route list alone never gives.
 */
@Transactional
class ApiDescriptionTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val reader: User by lazy {
		users.createLocalUser(
			email = "describe-${UUID.randomUUID()}@kanso.test",
			displayName = "A reader",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.MEMBER,
		)
	}

	private fun asReader() = authentication(
		KansoLocalUser(reader.id, reader.email, reader.displayName).let {
			UsernamePasswordAuthenticationToken(it, null, it.authorities)
		},
	)

	@Test
	fun `it serves a description of itself`() {
		mvc.perform(get("/v3/api-docs").with(asReader()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.openapi").exists())
	}

	@Test
	fun `it describes routes this application actually has`() {
		mvc.perform(get("/v3/api-docs").with(asReader()))
			.andExpect(jsonPath("$.paths./api/tickets").exists())
			.andExpect(jsonPath("$.paths./api/webhooks").exists())
			.andExpect(jsonPath("$.paths./api/teams/{teamId}/fields").exists())
	}

	/**
	 * The half that would have saved `KAN-29` its three trips into the source: what a body
	 * is *called*. `CustomFieldRequest` takes `type`, and sending `kind` answers 400
	 * "Failed to read request" without naming the field — so the schema is the only place
	 * the word appears outside the Kotlin.
	 */
	@Test
	fun `it names the fields a request body takes`() {
		mvc.perform(get("/v3/api-docs").with(asReader()))
			.andExpect(jsonPath("$.components.schemas.CustomFieldRequest.properties.type").exists())
			.andExpect(jsonPath("$.components.schemas.CustomFieldRequest.properties.kind").doesNotExist())
	}

	/**
	 * It is in no permit list, so it answers only a session — and stays that way.
	 *
	 * Asserted against the lists rather than by firing an anonymous request, because this
	 * profile runs `KANSO_AUTH_MODE=dev`: identity comes from an unverified header there
	 * and a request without one is not anonymous, so *every* path answers 200 and the
	 * question cannot be asked. `SecurityConfig`'s `anyRequest().authenticated()` is what
	 * actually holds this path shut; what could still go wrong is somebody adding it to a
	 * permit list, and that is exactly what this catches.
	 *
	 * The document names every route and every request body this instance has. Not a
	 * secret — the source is public — but this instance's surface is not something an
	 * anonymous caller has any business enumerating, and a third party building an
	 * integration holds a token before it ever needs the description.
	 */
	@Test
	fun `it is opened by no permit list`() {
		val opened = PublicRoutes.ALL + OAuthRoutes.ALL
		assertTrue(opened.none { it.startsWith("/v3/api-docs") }, "opened: $opened")
	}
}

package dev.kanso.tokens

import dev.kanso.MockMvcTest
import dev.kanso.auth.DevAuthenticationFilter
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a revocation leaves behind, and who is allowed to read it.
 *
 * Two halves that have to ship together, which is the whole argument of `V30`. The event
 * is the feature; the guard is what stops the feature being a disclosure. A suite that
 * asserted only the first would go green on a version of this that hands every member a
 * list of everybody else's credentials.
 *
 * Through the filter chain rather than against the services, for the reason
 * `ApiTokenLeakTest` gives: the leak that matters is the one a later refactor introduces
 * without anybody opening this file, so what is asserted is what reaches the wire.
 */
@Transactional
class ApiTokenRevocationTraceTest : MockMvcTest() {

	@Autowired lateinit var mvc: MockMvc
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var json: ObjectMapper

	private fun person(name: String, role: InstanceRole = InstanceRole.MEMBER) = users.createLocalUser(
		email = "trace-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = "not-a-real-hash",
		role = role,
	)

	/** A token made the way a person makes one, returning its id and its plaintext. */
	private fun issue(who: User, name: String): Pair<UUID, String> {
		val body = json.writeValueAsString(mapOf("name" to name, "scopes" to listOf(OAuthScopes.READ)))
		val response = mvc.perform(
			MockMvcRequestBuilders.post("/api/me/tokens")
				.header(DevAuthenticationFilter.HEADER, who.email)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body),
		).andReturn().response
		assertEquals(201, response.status, "a member has to be able to make one: ${response.contentAsString}")
		val made = json.readTree(response.contentAsString)
		return UUID.fromString(made["token"]["id"].asText()) to made["secret"].asText()
	}

	private fun revoke(who: User, id: UUID) = mvc.perform(
		MockMvcRequestBuilders.delete("/api/me/tokens/$id")
			.header(DevAuthenticationFilter.HEADER, who.email),
	).andReturn().response

	private fun feedOf(subject: User, reader: User) = mvc.perform(
		MockMvcRequestBuilders.get("/api/activity")
			.param("entityType", "user")
			.param("entityId", subject.id.toString())
			.header(DevAuthenticationFilter.HEADER, reader.email),
	).andReturn().response

	@Test
	fun `a revocation names the token by name and prefix`() {
		val member = person("Revoker")
		val (id, secret) = issue(member, "prod deploy")

		assertEquals(204, revoke(member, id).status)

		val response = feedOf(subject = member, reader = member)
		assertEquals(200, response.status, "the owner has to be able to read their own history")

		val rows = json.readTree(response.contentAsString)
		val revoked = rows.firstOrNull { it["kind"].asText() == "token_revoked" }
		assertNotNull(revoked, "the revocation left no trace: ${response.contentAsString}")

		assertEquals("prod deploy", revoked["payload"]["name"].asText(), "the name is what a person recognises")
		assertEquals(
			secret.take(ApiTokenSecret.PREFIX_LENGTH),
			revoked["payload"]["prefix"].asText(),
			"the prefix has to be the one the settings screen printed, or it matches nothing",
		)
		assertEquals(
			member.id.toString(),
			revoked["entityId"].asText(),
			"the event belongs on the account whose credential it was",
		)
		assertEquals(
			member.id.toString(),
			revoked["actor"]["id"].asText(),
			"who revoked it is half of what the ticket asked for",
		)
	}

	/**
	 * The one thing the row must never carry.
	 *
	 * Asserted against the bytes rather than the payload map, because a digest arriving
	 * under some *other* key would satisfy a check that only looked where it expected one.
	 */
	@Test
	fun `the trace never carries the digest or the secret`() {
		val member = person("Careful")
		val (id, secret) = issue(member, "laptop CLI")
		assertEquals(204, revoke(member, id).status)

		val body = feedOf(subject = member, reader = member).contentAsString
		assertTrue(body.contains("token_revoked"), "nothing was recorded, so this proves nothing")
		assertFalse(body.contains(ApiTokenSecret.hash(secret)), "the SHA-256 digest reached a feed")
		assertFalse(body.contains(secret), "the plaintext reached a feed")
	}

	/**
	 * **The guard, and the reason `V30` could not ship the vocabulary alone.**
	 *
	 * `entityId` is a free query parameter, so this is one request away from being a list of
	 * somebody else's integrations by the names they gave them. Without the clause in
	 * `ActivityController.list` this returns 200 and the payload below.
	 */
	@Test
	fun `a member cannot read another member's account history`() {
		val member = person("Owner of the token")
		val stranger = person("Stranger")
		val (id, _) = issue(member, "prod deploy")
		assertEquals(204, revoke(member, id).status)

		val response = feedOf(subject = member, reader = stranger)
		assertEquals(
			403,
			response.status,
			"a member read another account's token history — the name of a credential is a " +
				"map of what somebody connects: ${response.contentAsString}",
		)
		assertFalse(
			response.contentAsString.contains("prod deploy"),
			"refused with the payload in the body, which refuses nothing",
		)
	}

	/** A configurator can, which is the rule the account itself already uses. */
	@Test
	fun `a configurator reads it, because they can already see the account`() {
		val member = person("Owner of the token")
		val admin = person("Admin", InstanceRole.ADMIN)
		val (id, _) = issue(member, "prod deploy")
		assertEquals(204, revoke(member, id).status)

		val response = feedOf(subject = member, reader = admin)
		assertEquals(200, response.status, "an admin who can deactivate the account can read its history")
		assertTrue(response.contentAsString.contains("prod deploy"))
	}

	/**
	 * The other three entity types are unchanged, which is what keeps this a new rule rather
	 * than a narrowing of the old one. A team's feed is readable by a member who is not in
	 * it, exactly as it was before `USER` existed.
	 */
	@Test
	fun `the guard is about accounts and nothing else`() {
		val stranger = person("Stranger")
		val response = mvc.perform(
			MockMvcRequestBuilders.get("/api/activity")
				.param("entityType", "team")
				.param("entityId", UUID.randomUUID().toString())
				.header(DevAuthenticationFilter.HEADER, stranger.email),
		).andReturn().response
		assertEquals(200, response.status, "the new clause caught an entity type it was not about")
	}

	/** A revocation that did not happen records nothing — the 404 path writes no history. */
	@Test
	fun `an id that revokes nothing leaves no trace`() {
		val member = person("Guesser")
		assertEquals(404, revoke(member, UUID.randomUUID()).status)

		val body = feedOf(subject = member, reader = member).contentAsString
		assertFalse(body.contains("token_revoked"), "a failed revocation wrote an event: $body")
	}
}

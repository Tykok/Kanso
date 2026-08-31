package dev.kanso.sync.notion

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The standing correspondence between Notion people and Kanso accounts — screen
 * "who is who" needs before [NotionSchema.people] can write anything real.
 *
 * `@Transactional`: every test writes real rows through the real [UserRepository],
 * and rolling back is what lets them share one Postgres without cleaning up by hand.
 */
@Transactional
class NotionPeopleTest : PostgresTest() {

	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	@Test
	fun `a workspace that refuses the members list becomes a sentence, not an empty page`() {
		// `NoopNotionClient` is final and answers no members; the refusal is the thing under
		// test, so the fake is one `NotionClient` method and `TODO()` for the rest — see
		// `clientReturning` below, which both tests share.
		val people = NotionPeople(client = clientRefusing("API token does not have access to user information"), users = users)

		val view = people.view()
		assertFalse(view.available)
		assertTrue(view.reason!!.contains("read user information"), "the sentence says which box to tick")
		assertTrue(view.people.isEmpty())
	}

	@Test
	fun `an email that matches a Kanso account is suggested but not applied`() {
		val kanso = users.createLocalUser("m.rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "M. Rey", "m.rey@kanso.test")), users = users)

		val match = people.view().people.single()
		assertEquals(kanso.id, match.suggestedUserId)
		assertNull(match.userId, "suggested is not linked: an homonym must not silently own the work")
	}

	@Test
	fun `linking writes notion_person_id, and unlinking clears it`() {
		val kanso = users.createLocalUser("m.rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "M. Rey", null)), users = users)

		people.link(mapOf("u-1" to kanso.id))
		assertEquals("u-1", users.findById(kanso.id)!!.notionPersonId)

		people.link(mapOf("u-1" to null))
		assertNull(users.findById(kanso.id)!!.notionPersonId)
	}

	@Test
	fun `linking the same Notion id to a second account clears the first`() {
		val a = users.createLocalUser("account-a@kanso.test", "Account A", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val b = users.createLocalUser("account-b@kanso.test", "Account B", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "Shared Name", null)), users = users)

		people.link(mapOf("u-1" to a.id))
		people.link(mapOf("u-1" to b.id))

		assertNull(users.findById(a.id)!!.notionPersonId, "two accounts claiming one Notion person is how the mirror writes the wrong `people` value")
		assertEquals("u-1", users.findById(b.id)!!.notionPersonId)
	}

	@Test
	fun `a non-configurator is refused the write`() {
		val member = users.createLocalUser("just-a-member@kanso.test", "Just A Member", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val kanso = users.createLocalUser("m.rey@kanso.test", "M. Rey", encoder.hash("correct-horse-battery"), InstanceRole.MEMBER)
		val people = NotionPeople(client = clientReturning(NotionMember("u-1", "M. Rey", null)), users = users)

		assertFailsWith<AccessDeniedException> {
			people.requireConfigurator(member)
			people.link(mapOf("u-1" to kanso.id))
		}

		assertNull(users.findById(kanso.id)!!.notionPersonId, "the refusal happened before any write")
	}
}

private fun clientReturning(vararg members: NotionMember): NotionClient =
	object : NotionClient {
		override val enabled = true
		override suspend fun listUsers(): List<NotionMember> = members.toList()
		override suspend fun botUserId(): String? = throw UnsupportedOperationException()
		override suspend fun searchDatabases(startCursor: String?, pageSize: Int): NotionWorkspaceSearch =
			throw UnsupportedOperationException()
		override suspend fun searchPages(startCursor: String?, pageSize: Int): NotionPageSearch =
			throw UnsupportedOperationException()
		override suspend fun createDatabase(
			parentPageId: String,
			title: String,
			properties: Map<String, Any?>,
		): NotionDatabase = throw UnsupportedOperationException()
		override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? =
			throw UnsupportedOperationException()
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
			throw UnsupportedOperationException()
		override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
			throw UnsupportedOperationException()
		override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun updatePage(
			pageId: String,
			properties: Map<String, Any?>?,
			archived: Boolean?,
		): NotionPage = throw UnsupportedOperationException()
		override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
		override suspend fun queryDataSource(
			dataSourceId: String,
			editedOnOrAfter: OffsetDateTime?,
			startCursor: String?,
			pageSize: Int,
			includeArchived: Boolean,
		): NotionQueryPage = throw UnsupportedOperationException()
	}

private fun clientRefusing(message: String): NotionClient =
	object : NotionClient {
		override val enabled = true
		override suspend fun listUsers(): List<NotionMember> = throw NotionApiException(403, "restricted_resource", message)
		override suspend fun botUserId(): String? = throw UnsupportedOperationException()
		override suspend fun searchDatabases(startCursor: String?, pageSize: Int): NotionWorkspaceSearch =
			throw UnsupportedOperationException()
		override suspend fun searchPages(startCursor: String?, pageSize: Int): NotionPageSearch =
			throw UnsupportedOperationException()
		override suspend fun createDatabase(
			parentPageId: String,
			title: String,
			properties: Map<String, Any?>,
		): NotionDatabase = throw UnsupportedOperationException()
		override suspend fun retrieveDatabase(databaseId: String): NotionDatabase? =
			throw UnsupportedOperationException()
		override suspend fun retrieveDataSource(dataSourceId: String): NotionDataSource? =
			throw UnsupportedOperationException()
		override suspend fun updateDataSourceSchema(dataSourceId: String, properties: Map<String, Any?>) =
			throw UnsupportedOperationException()
		override suspend fun createPage(dataSourceId: String, properties: Map<String, Any?>): NotionPage =
			throw UnsupportedOperationException()
		override suspend fun updatePage(
			pageId: String,
			properties: Map<String, Any?>?,
			archived: Boolean?,
		): NotionPage = throw UnsupportedOperationException()
		override suspend fun retrievePage(pageId: String): NotionPage? = throw UnsupportedOperationException()
		override suspend fun queryDataSource(
			dataSourceId: String,
			editedOnOrAfter: OffsetDateTime?,
			startCursor: String?,
			pageSize: Int,
			includeArchived: Boolean,
		): NotionQueryPage = throw UnsupportedOperationException()
	}

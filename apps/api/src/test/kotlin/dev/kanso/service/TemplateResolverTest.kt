package dev.kanso.service

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.MemberRole
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.Team
import dev.kanso.domain.TicketTemplate
import dev.kanso.domain.User
import dev.kanso.repo.TeamRepository
import dev.kanso.repo.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a name becomes a row, and where it fails to.
 *
 * The failures are the point of this file. A resolver that quietly dropped what it could not
 * match would make a template that half-works indistinguishable from one that works, and the
 * composer would have nothing to print.
 */
@Transactional
class TemplateResolverTest : PostgresTest() {

	@Autowired lateinit var resolver: TemplateResolver
	@Autowired lateinit var labels: LabelService
	@Autowired lateinit var fields: CustomFieldService
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var teamRepo: TeamRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private val admin: User by lazy {
		users.createLocalUser(
			email = "resolver-${UUID.randomUUID()}@kanso.test",
			displayName = "Resolver",
			passwordHash = encoder.hash("correct-horse-battery"),
			role = InstanceRole.ADMIN,
		)
	}

	private fun key() = "R${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun team(): Team = teams.create(admin, "Resolve", key(), null)
		.also { teamRepo.addMember(it.id, admin.id, MemberRole.MEMBER) }

	private fun template(body: TemplateBody) = TicketTemplate(
		id = UUID.randomUUID(), teamId = null, name = "T${UUID.randomUUID()}",
		summary = null, body = body, categories = emptyList(),
		createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(),
	)

	@Test
	fun `a label that exists resolves to its id`() {
		val team = team()
		val bug = labels.create(admin, team.id, "bug", "rose")

		val resolved = resolver.resolve(template(TemplateBody(labels = listOf("bug"))), team.id)

		assertEquals(listOf(bug.id), resolved.labelIds)
		assertEquals(emptyList(), resolved.unresolved.labels)
	}

	@Test
	fun `case does not decide whether a label resolves`() {
		val team = team()
		val bug = labels.create(admin, team.id, "Bug", "rose")

		val resolved = resolver.resolve(template(TemplateBody(labels = listOf("bug"))), team.id)

		assertEquals(listOf(bug.id), resolved.labelIds)
	}

	@Test
	fun `a label the team does not have is reported, not dropped`() {
		val team = team()

		val resolved =
			resolver.resolve(template(TemplateBody(labels = listOf("regression"))), team.id)

		assertEquals(emptyList(), resolved.labelIds)
		assertEquals(listOf("regression"), resolved.unresolved.labels)
	}

	@Test
	fun `a value that does not fit its field's type is reported under the field's name`() {
		val team = team()
		fields.define(admin, team.id, "Weight", "number", false, emptyList())

		val resolved = resolver.resolve(
			template(TemplateBody(fields = mapOf("Weight" to "heavy"))),
			team.id,
		)

		assertEquals(emptyMap(), resolved.fieldValues)
		assertEquals(listOf("Weight"), resolved.unresolved.fields)
	}

	@Test
	fun `a checkbox can be ticked by a template`() {
		val team = team()
		val blocking = fields.define(admin, team.id, "Blocking", "boolean", false, emptyList())

		val resolved = resolver.resolve(
			template(TemplateBody(fields = mapOf("Blocking" to true))),
			team.id,
		)

		assertEquals(mapOf(blocking.id to true), resolved.fieldValues)
		assertEquals(emptyList(), resolved.unresolved.fields)
	}

	@Test
	fun `a draft resolves the scalars and reports everything else`() {
		val body = TemplateBody(
			title = "[Bug] ",
			description = "## What happens\n",
			labels = listOf("bug"),
			fields = mapOf("Severity" to "major"),
		)

		val resolved = resolver.resolve(template(body), teamId = null)

		assertEquals("[Bug] ", resolved.title)
		assertEquals("## What happens\n", resolved.description)
		assertTrue(resolved.labelIds.isEmpty())
		assertEquals(listOf("bug"), resolved.unresolved.labels)
		assertEquals(listOf("Severity"), resolved.unresolved.fields)
	}
}

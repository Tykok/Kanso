package dev.kanso.repo

import dev.kanso.PostgresTest
import dev.kanso.db.TicketTemplates
import dev.kanso.domain.TemplateBody
import dev.kanso.domain.TicketTemplate
import org.jetbrains.exposed.v1.core.isNull
import org.springframework.beans.factory.annotation.Autowired
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two partial unique indexes, which are the whole reason this file exists.
 *
 * A plain `UNIQUE (team_id, name)` would pass every test anybody thinks to write about a
 * team's templates and silently allow four instance templates called `Bug`, because in SQL
 * `NULL` is not equal to `NULL`. That case is the first test below, and it is the one a
 * reviewer should look for.
 */
@Transactional
class TicketTemplateSchemaTest : PostgresTest() {

	@Autowired lateinit var templates: TicketTemplateRepository

	private fun insert(teamId: UUID?, name: String) = TicketTemplates.insert {
		it[TicketTemplates.id] = UUID.randomUUID()
		it[TicketTemplates.teamId] = teamId
		it[TicketTemplates.name] = name
		it[TicketTemplates.summary] = null
		it[TicketTemplates.body] = "{}"
		it[TicketTemplates.createdAt] = OffsetDateTime.now()
		it[TicketTemplates.updatedAt] = OffsetDateTime.now()
	}

	@Test
	fun `two instance templates cannot share a name`() {
		val name = "Duplicate ${UUID.randomUUID()}"
		insert(null, name)
		assertFailsWith<Exception> { insert(null, name) }
	}

	@Test
	fun `Kanso ships four instance templates`() {
		val shipped = TicketTemplates.selectAll()
			.where { TicketTemplates.teamId.isNull() }
			.map { it[TicketTemplates.name] }
		assertEquals(
			listOf("Bug", "Chore", "Customer request", "Feature"),
			shipped.filter { it in setOf("Bug", "Chore", "Customer request", "Feature") }.sorted(),
		)
	}

	@Test
	fun `available returns the instance templates when there is no team`() {
		val offered = templates.available(null)
		assertTrue(offered.map { it.name }.contains("Bug"))
		assertTrue(offered.all { it.teamId == null })
	}

	@Test
	fun `a category travels with its template and is replaced wholesale on update`() {
		val made = templates.insert(
			TicketTemplate(
				id = UUID.randomUUID(), teamId = null,
				name = "Spike ${UUID.randomUUID()}", summary = null,
				body = TemplateBody(description = "## Question\n"),
				categories = listOf("Engineering", "Research"),
				createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(),
			),
		)
		assertEquals(listOf("Engineering", "Research"), made.categories)

		val edited = templates.update(
			made.id, made.name, "now with a summary", made.body, listOf("Research"),
		)
		assertEquals(listOf("Research"), edited.categories)
		assertEquals(listOf("Research"), templates.findById(made.id)!!.categories)
		assertEquals("now with a summary", templates.findById(made.id)!!.summary)
	}
}

package dev.kanso.sync.importer

import dev.kanso.api.ImportPlanRow
import dev.kanso.api.ImportPreviewRequest
import dev.kanso.api.NotionImportController
import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The mapping on the wire, end to end: a request naming its own columns and its own
 * options reaches [MappedPageReader] through [ImportPlanEntry], not just through a
 * mapping built by hand in a test.
 */
@Transactional
class NotionImportMappingTest : ImportTestBase() {

	@Autowired lateinit var controller: NotionImportController
	@Autowired lateinit var currentUser: CurrentUser

	/**
	 * The other two tests hand a [ColumnMapping] to [ImportPlanEntry] directly, which
	 * proves the reader honours a mapping but not that [ImportPlanRow]'s wire strings
	 * reach one. This drives the actual controller, built with a workspace of its own so
	 * the wire form has something real to name a column in.
	 */
	@Test
	fun `a valid field name on the wire reaches the mapping`() {
		val tasks = FakeDatabase("Tâches", listOf(fakePage("Livrer", mapOf("Etat" to notionSelect("En cours")))))
		val wired = NotionImportController(importerFor(tasks), currentUser)

		val preview = wired.preview(
			ImportPreviewRequest(
				plan = listOf(ImportPlanRow(sourceId = tasks.dataSourceId, target = "tickets", columns = mapOf("status" to "Etat")))
			)
		)

		assertEquals(
			emptyList(), preview.unmappedProperties,
			"`Etat` was claimed, not left to the imported-from-Notion section",
		)
	}

	@Test
	fun `a French base imports with its own columns and its own options`() {
		val tasks = FakeDatabase(
			"Tâches",
			listOf(
				fakePage(
					"Livrer",
					mapOf(
						"Etat" to notionSelect("En cours"),
						"Urgence" to notionSelect("Haute"),
						"Détail" to notionText("Ce qu'il reste"),
						"Échéance" to notionDate("2026-09-01"),
					),
					titleProperty = "Nom",
				)
			),
		)

		importerFor(tasks).perform(
			admin, team.id,
			listOf(
				ImportPlanEntry(
					sourceId = tasks.dataSourceId,
					target = ImportTarget.TICKETS,
					mapping = ColumnMapping(
						columns = mapOf(
							ImportField.STATUS to "Etat",
							ImportField.PRIORITY to "Urgence",
							ImportField.DESCRIPTION to "Détail",
							ImportField.DUE to "Échéance",
						),
						values = mapOf(
							ImportField.STATUS to mapOf("En cours" to "in_progress"),
							ImportField.PRIORITY to mapOf("Haute" to "high"),
						),
					),
				)
			),
		)

		val ticket = ticketRows.search(includeArchived = false, limit = 50).single()
		assertEquals("Livrer", ticket.title)
		assertEquals(DefaultStatus.IN_PROGRESS, ticket.status)
		assertEquals(TicketPriority.HIGH, ticket.priority)
		assertEquals("Ce qu'il reste", ticket.description?.lines()?.first())
		// `KansoInstant.at` is the field the domain model carries; the brief's `.value` names
		// no property this codebase has.
		assertEquals(LocalDate.parse("2026-09-01"), ticket.due?.at?.toLocalDate())
	}

	@Test
	fun `the preview lists the properties nothing claimed`() {
		val tasks = FakeDatabase("Tâches", listOf(fakePage("Livrer", mapOf("Sprint" to notionText("S12")))))
		val preview = importerFor(tasks).preview(plan(tasks to ImportTarget.TICKETS))

		assertEquals(listOf("Sprint"), preview.unmappedProperties)
	}

	/**
	 * `NotionImportController.entry` parses a wire field name through [ImportField.from]
	 * before the request ever reaches [NotionImportService] — a field outside the closed
	 * vocabulary never gets as far as a Notion read. `ImportField.from` (through the shared
	 * `parse` helper) is what raises `IllegalArgumentException` here; `ApiExceptionHandler`
	 * is the layer that turns that into the 400 a real request sees.
	 */
	@Test
	fun `an unknown field name on the wire is refused`() {
		val request = ImportPreviewRequest(
			plan = listOf(
				ImportPlanRow(sourceId = "any-source", target = "tickets", columns = mapOf("bogus" to "Some Column"))
			)
		)

		assertFailsWith<IllegalArgumentException> { controller.preview(request) }
	}
}

package dev.kanso.sync.importer

import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Step 2 → 3: what *would* happen.
 *
 * "Rien n'est écrit avant validation" is the sentence the drawing puts under the mapping
 * table, and it is the only promise on screen 24 that cannot be recovered from if it is
 * broken. So the first test here counts rows on both sides of the call, and the rest are
 * about the two things step 3 prints on the strength of this answer.
 */
@Transactional
class NotionImportPreviewTest : ImportTestBase() {

	/** Three bases pointing at each other, plus one nobody keeps. */
	private val specs = FakeDatabase(
		"Product specs",
		pages = listOf(fakePage("Search spec", id = "page-spec-1")),
	)

	private val meetings = FakeDatabase(
		"Meeting notes",
		pages = listOf(fakePage("Kickoff", id = "page-meeting-1", properties = mapOf("Spec" to notionRelation("page-spec-1")))),
	)

	private val engineering = FakeDatabase(
		"Engineering tasks",
		pages = listOf(
			fakePage(
				"Index the archive",
				id = "page-eng-1",
				properties = mapOf(
					"Status" to notionSelect("In Progress"),
					"Priority" to notionSelect("High"),
					"Due" to notionDate("2026-09-01"),
					"Sprint" to notionNumber(12),
					"Squad" to notionSelect("Platform"),
					"Spec" to notionRelation("page-spec-1"),
				),
			),
			fakePage("Paginate the walk", id = "page-eng-2"),
		),
	)

	private val design = FakeDatabase("Design docs", pages = listOf(fakePage("Type scale", id = "page-design-1")))

	private val archive = FakeDatabase("Archive 2023", pages = List(4) { fakePage("Old $it") })

	private fun importer() = importerFor(engineering, specs, meetings, design, archive)

	private fun drawnPlan() = plan(
		engineering to ImportTarget.TICKETS,
		specs to ImportTarget.TICKETS,
		meetings to ImportTarget.TICKETS,
		design to ImportTarget.DOCUMENTS,
	)

	@Test
	fun `writes nothing, which is the whole promise of the step`() {
		val before = rowCounts()

		val preview = importer().preview(drawnPlan())

		assertEquals(5, preview.projects.sumOf { it.pages } + preview.folders.sumOf { it.pages }, "it did read them")
		assertEquals(before, rowCounts(), "and wrote not one row")
	}

	@Test
	fun `says what each base becomes and how much of it there is`() {
		val preview = importer().preview(drawnPlan())

		assertEquals(
			listOf("Engineering tasks" to 2, "Product specs" to 1, "Meeting notes" to 1),
			preview.projects.map { it.name to it.pages },
		)
		assertEquals(listOf("Design docs" to 1), preview.folders.map { it.name to it.pages })
		assertEquals(0, preview.skipped, "nothing in this workspace is unadoptable")
	}

	@Test
	fun `a relation nobody mapped links nothing`() {
		// Engineering and Meeting notes both point into Product specs, and all three bases
		// are kept — but the request carries no mapping, so no column has been named as a
		// link and there is nothing to cross between bases. The counted-links case is
		// [ImportPlannerTest], which can give a mapping by hand.
		val preview = importer().preview(drawnPlan())

		assertEquals(0, preview.linkedSources)
		assertEquals(0, preview.linkedByRelation)
	}

	@Test
	fun `names the properties nothing claimed, which without a mapping is all of them`() {
		val preview = importer().preview(drawnPlan())

		// Nothing here mapped a column, so nothing has a column of its own and everything
		// readable is preserved verbatim — which is the honest answer for a base whose
		// meaning nobody has stated yet. `Spec` is absent because it is a relation: it holds
		// page ids, which are not content anybody can read.
		assertEquals(listOf("Due", "Priority", "Sprint", "Squad", "Status"), preview.unmappedProperties)
	}

	@Test
	fun `counts the pages it could not adopt rather than leaving them out of the total`() {
		val awkward = FakeDatabase(
			"Field notes",
			pages = listOf(fakePage("Readable", id = "page-ok"), untitledPage("page-nameless")),
		)
		val preview = importerFor(awkward).preview(plan(awkward to ImportTarget.TICKETS))

		assertEquals(1, preview.skipped, "a page with no title has nothing to make a row out of")
		assertEquals(listOf("Field notes" to 1), preview.projects.map { it.name to it.pages })
	}

	@Test
	fun `ignores a plan row naming a base the workspace no longer holds`() {
		// The workspace is searched again between the steps, so a mapping can name a base
		// that has since been deleted. Counting it would put a number on step 3 with
		// nothing behind it — `import-map.ts` refuses the same thing on its own side.
		val preview = importer().preview(
			drawnPlan() + ImportPlanEntry("ds-deleted-yesterday", ImportTarget.TICKETS)
		)

		assertEquals(3, preview.projects.size)
	}
}

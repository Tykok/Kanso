package dev.kanso.domain

import dev.kanso.service.CycleService
import dev.kanso.service.CycleTimeService
import dev.kanso.service.WorkloadService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No database and no Spring: the category is arithmetic on an enum, and a test that
 * needed a container to prove it would be measuring the container.
 */
class StatusCategoryTest {

	@Test
	fun `every status is filed under exactly one category`() {
		assertEquals(
			mapOf(
				DefaultStatus.BACKLOG to StatusCategory.BACKLOG,
				DefaultStatus.TODO to StatusCategory.UNSTARTED,
				DefaultStatus.IN_PROGRESS to StatusCategory.STARTED,
				DefaultStatus.IN_REVIEW to StatusCategory.STARTED,
				DefaultStatus.DONE to StatusCategory.COMPLETED,
				DefaultStatus.CANCELED to StatusCategory.CANCELED,
			),
			DefaultStatus.entries.associateWith { it.category },
		)
	}

	@Test
	fun `review is started work, which is the whole reason the category exists`() {
		assertEquals(DefaultStatus.IN_PROGRESS.category, DefaultStatus.IN_REVIEW.category)
	}

	@Test
	fun `every category is reachable, so none is a vocabulary nothing can be in`() {
		assertEquals(StatusCategory.entries.toSet(), DefaultStatus.entries.map { it.category }.toSet())
	}

	/**
	 * The lists this refactor rewrote, pinned against what they meant before it.
	 *
	 * They used to be lists of statuses, derived from the categories and asserted against
	 * the literals they had replaced. `KAN-90` turned them into the categories themselves,
	 * because a list of Kanso's own six statuses cannot say what a team's seventh means —
	 * so what is pinned now is the set of meanings, and `StatusCategoryTest` above already
	 * pins which of the six falls in each. Together those two still say exactly which
	 * seeded statuses the burndown and the workload chart plot, which is what this test
	 * was for: nothing on screen moves for a team that invented nothing.
	 *
	 * (The roadmap's own list is private to it and stays pinned by `PublicRoadmapTest`,
	 * which asserts the columns themselves.)
	 */
	@Test
	fun `the derived lists still mean exactly what they were written out as`() {
		// Everything but a decision not to do the work.
		assertEquals(
			listOf(
				StatusCategory.BACKLOG,
				StatusCategory.UNSTARTED,
				StatusCategory.STARTED,
				StatusCategory.COMPLETED,
			),
			StatusCategory.entries.filter { it != StatusCategory.CANCELED },
		)
		// Not settled either way — the charge somebody is answerable for.
		assertEquals(
			listOf(StatusCategory.BACKLOG, StatusCategory.UNSTARTED, StatusCategory.STARTED),
			WorkloadService.OPEN_CATEGORIES,
		)
		// In flight, which is narrower than open: `todo` is a load, not work in progress.
		assertEquals(listOf(StatusCategory.STARTED), CycleTimeService.IN_FLIGHT_CATEGORIES)
		// Somebody has decided about it, whichever way — the rollover's rule.
		assertEquals(
			setOf(StatusCategory.COMPLETED, StatusCategory.CANCELED),
			CycleService.FINISHED_CATEGORIES,
		)
	}
}

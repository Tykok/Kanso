package dev.kanso.domain

import dev.kanso.service.CycleService
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
	 * The two lists this refactor rewrote, pinned against the literals they held before
	 * it. They are what the burndown and the workload chart plot, so a category that
	 * quietly admits or drops a status changes a drawing, and the point of the ticket was
	 * that nothing on screen moves. (The roadmap's own list is private to it and stays
	 * pinned by `PublicRoadmapTest`, which asserts the columns themselves.)
	 */
	@Test
	fun `the derived lists still hold exactly what they were written out as`() {
		assertEquals(
			listOf(
				DefaultStatus.BACKLOG,
				DefaultStatus.TODO,
				DefaultStatus.IN_PROGRESS,
				DefaultStatus.IN_REVIEW,
				DefaultStatus.DONE,
			),
			CycleService.COUNTED_STATUSES,
		)
		assertEquals(
			listOf(
				DefaultStatus.BACKLOG,
				DefaultStatus.TODO,
				DefaultStatus.IN_PROGRESS,
				DefaultStatus.IN_REVIEW,
			),
			WorkloadService.OPEN_STATUSES,
		)
	}
}

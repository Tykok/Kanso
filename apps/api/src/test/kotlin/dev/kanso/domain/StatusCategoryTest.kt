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
				TicketStatus.BACKLOG to StatusCategory.BACKLOG,
				TicketStatus.TODO to StatusCategory.UNSTARTED,
				TicketStatus.IN_PROGRESS to StatusCategory.STARTED,
				TicketStatus.IN_REVIEW to StatusCategory.STARTED,
				TicketStatus.DONE to StatusCategory.COMPLETED,
				TicketStatus.CANCELED to StatusCategory.CANCELED,
			),
			TicketStatus.entries.associateWith { it.category },
		)
	}

	@Test
	fun `review is started work, which is the whole reason the category exists`() {
		assertEquals(TicketStatus.IN_PROGRESS.category, TicketStatus.IN_REVIEW.category)
	}

	@Test
	fun `every category is reachable, so none is a vocabulary nothing can be in`() {
		assertEquals(StatusCategory.entries.toSet(), TicketStatus.entries.map { it.category }.toSet())
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
				TicketStatus.BACKLOG,
				TicketStatus.TODO,
				TicketStatus.IN_PROGRESS,
				TicketStatus.IN_REVIEW,
				TicketStatus.DONE,
			),
			CycleService.COUNTED_STATUSES,
		)
		assertEquals(
			listOf(
				TicketStatus.BACKLOG,
				TicketStatus.TODO,
				TicketStatus.IN_PROGRESS,
				TicketStatus.IN_REVIEW,
			),
			WorkloadService.OPEN_STATUSES,
		)
	}
}

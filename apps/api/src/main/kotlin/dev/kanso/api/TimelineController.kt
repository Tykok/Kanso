package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.TimelineService
import dev.kanso.service.TimelineSort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One GET for the whole timeline. Both filters are optional and neither is a page:
 * a Gantt is drawn all at once, so bounds, slack and the arrows have to agree with
 * each other in a single response or the screen would show two different plans.
 *
 * The reader is part of the question, not only of the authorization: the response says
 * which rows they may move, and that answer differs per request.
 */
@RestController
@RequestMapping("/api/timeline")
class TimelineController(
	private val timeline: TimelineService,
	private val currentUser: CurrentUser,
) {

	/**
	 * `page` and `pageSize` bound the **column**, never the drawing.
	 *
	 * The class header above used to say a Gantt is drawn all at once and is not a page, and
	 * that is still true of the chart: bounds, slack and arrows agree inside one response or
	 * the screen shows two plans. What it did not anticipate is the list beside the chart,
	 * which holds every ticket in scope including finished work and pages the way every
	 * other list in Kanso does.
	 */
	@GetMapping
	fun load(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(required = false) projectId: UUID?,
		@RequestParam(required = false) sort: String?,
		@RequestParam(required = false, defaultValue = "0") page: Int,
		@RequestParam(required = false) pageSize: Int?,
		@RequestParam(required = false, defaultValue = "false") hideCompleted: Boolean,
	): TimelineResponse = TimelineResponse.of(
		timeline.load(
			actor = currentUser.require(),
			teamId = teamId,
			projectId = projectId,
			sort = TimelineSort.from(sort),
			page = page.coerceAtLeast(0),
			pageSize = pageSize,
			hideCompleted = hideCompleted,
		),
	)
}

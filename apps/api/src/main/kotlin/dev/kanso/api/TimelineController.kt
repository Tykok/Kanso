package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.TimelineService
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

	@GetMapping
	fun load(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(required = false) projectId: UUID?,
	): TimelineResponse = TimelineResponse.of(timeline.load(currentUser.require(), teamId, projectId))
}

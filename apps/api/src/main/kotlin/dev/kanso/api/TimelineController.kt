package dev.kanso.api

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
 */
@RestController
@RequestMapping("/api/timeline")
class TimelineController(private val timeline: TimelineService) {

	@GetMapping
	fun load(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(required = false) projectId: UUID?,
	): TimelineResponse = TimelineResponse.of(timeline.load(teamId, projectId))
}

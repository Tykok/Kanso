package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.EffectiveVelocity
import dev.kanso.service.EffectiveVelocityService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One person's pace, and which of the two numbers is in force.
 *
 * [source] is the field this response exists for. A client handed [declared] and
 * [measured] would have to re-derive the arbitration to caption the screen, and the
 * caption is half the feature: a date whose origin nobody can name is a date nobody
 * should act on. So the rule runs once, on the server, and its verdict travels.
 *
 * [cyclesUntilMeasured] is what makes the caption a sentence somebody can act on rather
 * than a status — "two more cycles and Kanso will use the measured one" instead of
 * "estimated". Zero once the measurement is in force.
 */
data class EffectiveVelocityResponse(
	val perWorkingDay: Double?,
	/** `declared`, `measured` or `none`, lowercased for the wire like every other enum here. */
	val source: String,
	val declared: Double?,
	val measured: Double?,
	val measuredCycles: Int,
	val cyclesUntilMeasured: Int,
) {
	companion object {
		fun of(effective: EffectiveVelocity) = EffectiveVelocityResponse(
			perWorkingDay = effective.perWorkingDay,
			source = effective.source.name.lowercase(),
			declared = effective.declared,
			measured = effective.measured,
			measuredCycles = effective.measuredCycles,
			cyclesUntilMeasured = effective.cyclesUntilMeasured,
		)
	}
}

/**
 * `/api/me/velocity`, and deliberately nothing wider.
 *
 * `VelocityService` refused to ship a controller until somebody had decided who may read
 * one person's output. This is the narrowest answer to that: you may read your own. The
 * settings screen needs exactly this — the number it will plan your dates with, and the
 * sentence saying where it came from — and nothing about it requires seeing anybody else's.
 *
 * A team-wide route would be a league table on a URL, which the service's own `forTeam`
 * comment already argues against for the one screen that has to draw it. When a screen
 * needs it, it can ask for it then, with whatever rule about who may look somebody will
 * have decided by then.
 *
 * [teamId] is required rather than defaulted to the caller's team. A cycle is one team's
 * calendar, so "your velocity" is not a question with one answer for somebody in two
 * teams, and picking one for them would silently show a number measured against the wrong
 * fortnight.
 */
@RestController
class VelocityController(
	private val currentUser: CurrentUser,
	private val velocity: EffectiveVelocityService,
) {

	@GetMapping("/api/me/velocity")
	fun mine(@RequestParam teamId: UUID): EffectiveVelocityResponse =
		EffectiveVelocityResponse.of(velocity.forPerson(currentUser.require(), teamId))
}

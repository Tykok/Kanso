package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.TicketAccess
import dev.kanso.service.EffectiveVelocity
import dev.kanso.service.EffectiveVelocityService
import dev.kanso.service.TicketDuration
import dev.kanso.service.TicketDurationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

/**
 * How long a ticket should take, or which of the three reasons it cannot be said.
 *
 * Flat, with [basis] as the discriminator, rather than the sealed hierarchy the service
 * returns: Jackson has no shape for a sealed interface that a TypeScript union reads
 * cleanly, and the client's job here is a `switch` over four cases. The compiler enforces
 * the totality on the Kotlin side, which is where the branch is easy to forget.
 *
 * No point estimate is carried, only the two ends. A field holding the un-widened number
 * would be printed by somebody, and a bare date computed off a three-cycle mean is exactly
 * what the range exists to prevent.
 */
data class TicketDurationResponse(
	/** `estimated`, `no_estimate`, `no_assignee` or `no_velocity`. */
	val basis: String,
	val lowWorkingDays: Int?,
	val highWorkingDays: Int?,
	val points: Int?,
	val assignees: Int?,
	/**
	 * Assignees this range could not account for, and so the amount it overstates by. Travels
	 * with the number the way `unestimated` travels with a points total.
	 */
	val withoutVelocity: Int?,
) {
	companion object {
		fun of(duration: TicketDuration) = when (duration) {
			is TicketDuration.Estimated -> TicketDurationResponse(
				basis = "estimated",
				lowWorkingDays = duration.lowWorkingDays,
				highWorkingDays = duration.highWorkingDays,
				points = duration.points,
				assignees = duration.assignees,
				withoutVelocity = duration.withoutVelocity,
			)
			TicketDuration.NoEstimate -> absent("no_estimate")
			TicketDuration.NoAssignee -> absent("no_assignee")
			TicketDuration.NoVelocity -> absent("no_velocity")
		}

		private fun absent(basis: String) = TicketDurationResponse(basis, null, null, null, null, null)
	}
}

/**
 * `/api/tickets/{id}/duration`, beside the ticket rather than inside it.
 *
 * Not a field on `TicketResponse`: computing it costs a walk of the team's closed cycles
 * and a preferences read per assignee, which every list of two hundred rows would then pay
 * for to render a number only the detail view draws. A second request from the one screen
 * that wants it is the cheaper shape by a wide margin.
 *
 * It does leak something the velocity route deliberately would not: a ticket with one
 * assignee and a known size divides to that person's pace. That is inherent in the feature
 * rather than an oversight of this route — a duration derived from somebody's velocity is
 * a fact about their velocity — and it is bounded by the same rule as the ticket itself,
 * which anyone who can read the ticket can already read.
 */
@RestController
class TicketDurationController(
	private val durations: TicketDurationService,
	private val currentUser: CurrentUser,
	private val access: TicketAccess,
) {

	@GetMapping("/api/tickets/{id}/duration")
	fun forTicket(@PathVariable id: UUID): TicketDurationResponse {
		// A duration is read off its assignees' pace, so answering for a draft would also
		// disclose whose it is.
		access.requireReadable(currentUser.require(), id)
		return TicketDurationResponse.of(durations.forTicket(id))
	}
}

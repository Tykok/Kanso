package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.service.DeliveredCycle
import dev.kanso.service.LoadSlice
import dev.kanso.service.OpenLoad
import dev.kanso.service.Progress
import dev.kanso.service.ProgressService
import dev.kanso.service.ProjectLoad
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * One bar, flat, and deliberately not a [CycleResponse].
 *
 * That DTO carries a `ticketCount` this read would have to go and count — one query per
 * bar — for a number no bar draws. The four fields a bar needs are its identity, its two
 * dates and its height.
 */
data class DeliveredCycleResponse(
	val cycleId: UUID,
	val number: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	val points: Double,
	val workingDays: Int,
	val unestimated: Int,
	/** Whether the velocity in force was measured over this cycle. See [DeliveredCycle]. */
	val countedTowardsVelocity: Boolean,
) {
	companion object {
		fun of(delivered: DeliveredCycle) = DeliveredCycleResponse(
			cycleId = delivered.cycle.id,
			number = delivered.cycle.number,
			startsOn = delivered.cycle.startsOn,
			endsOn = delivered.cycle.endsOn,
			points = delivered.points,
			workingDays = delivered.workingDays,
			unestimated = delivered.unestimated,
			countedTowardsVelocity = delivered.countedTowardsVelocity,
		)
	}
}

data class LoadSliceResponse(val tickets: Int, val points: Int, val unestimated: Int) {
	companion object {
		fun of(slice: LoadSlice) = LoadSliceResponse(slice.tickets, slice.points, slice.unestimated)
	}
}

/** [projectId] and [projectName] are null together: the tickets filed under no project. */
data class ProjectLoadResponse(
	val projectId: UUID?,
	val projectName: String?,
	val load: LoadSliceResponse,
) {
	companion object {
		fun of(load: ProjectLoad) = ProjectLoadResponse(
			projectId = load.project?.id,
			projectName = load.project?.name,
			load = LoadSliceResponse.of(load.load),
		)
	}
}

data class OpenLoadResponse(
	val load: LoadSliceResponse,
	/** Keyed by the status wire value, and every open status is present, including zeros. */
	val byStatus: Map<String, LoadSliceResponse>,
	val byProject: List<ProjectLoadResponse>,
	/** Points divided by the pace in force. Null — never `0` — when there is no pace. */
	val workingDays: Double?,
) {
	companion object {
		fun of(load: OpenLoad) = OpenLoadResponse(
			load = LoadSliceResponse.of(load.load),
			byStatus = load.byStatus
				.mapKeys { it.key.wire }
				.mapValues { LoadSliceResponse.of(it.value) },
			byProject = load.byProject.map(ProjectLoadResponse::of),
			workingDays = load.workingDays,
		)
	}
}

/**
 * One person's progress, with the arbitration already done and the subject named.
 *
 * [person] is on the response even though this route only ever answers for the caller. The
 * shape is the one the other-person view will serve, and a page that had to be told who it
 * is about by its own URL could not put a name in its heading without a second request.
 */
data class ProgressResponse(
	val person: PersonResponse,
	val velocity: EffectiveVelocityResponse,
	/** Oldest first — the order the chart draws. */
	val delivered: List<DeliveredCycleResponse>,
	val load: OpenLoadResponse,
) {
	companion object {
		fun of(progress: Progress) = ProgressResponse(
			person = PersonResponse.of(progress.person),
			// The same DTO the settings screen already reads, so the sentence naming the
			// source in force is written once on the client and drawn on both screens.
			velocity = EffectiveVelocityResponse.of(progress.velocity),
			delivered = progress.delivered.map(DeliveredCycleResponse::of),
			load = OpenLoadResponse.of(progress.load),
		)
	}
}

/**
 * `/api/me/progress`, and nothing wider.
 *
 * **There is no authorisation check in here beyond having a session, and that is the
 * feature.** The screen is the one a person opens on themselves, and it is readable by its
 * owner whatever their seat: a `VIEWER` demoted this morning still gets their own numbers.
 * `ReadOnlySeat` turns writes away at the door and this is a `GET`, so the read-only seat
 * has nothing to say about it — the only identity this route can serve is the one on the
 * request.
 *
 * The other-person and team views are a separate ticket, and they are the ones that need a
 * rule about who may look. When they arrive they add a route beside this one and pass a
 * different subject to the same `ProgressService.forPerson`; nothing in this file or that
 * service has to move, and no computation is duplicated. The reason that works is that the
 * subject is an argument all the way down.
 *
 * [teamId] is required rather than defaulted to the caller's team, for the reason
 * `/api/me/velocity` requires it: a cycle is one team's calendar, so somebody in two teams
 * has two paces and two plates, and picking one for them would draw a chart measured
 * against the wrong fortnight.
 */
@RestController
class ProgressController(
	private val currentUser: CurrentUser,
	private val progress: ProgressService,
) {

	@GetMapping("/api/me/progress")
	fun mine(@RequestParam teamId: UUID): ProgressResponse =
		ProgressResponse.of(progress.forPerson(currentUser.require(), teamId))
}

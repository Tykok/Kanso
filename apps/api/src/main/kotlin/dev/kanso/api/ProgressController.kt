package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.Team
import dev.kanso.repo.UserRepository
import dev.kanso.service.DeliveredCycle
import dev.kanso.service.LoadSlice
import dev.kanso.service.NotFoundException
import dev.kanso.service.OpenLoad
import dev.kanso.service.Progress
import dev.kanso.service.ProgressAccess
import dev.kanso.service.ProgressReaders
import dev.kanso.service.ProgressService
import dev.kanso.service.ProjectLoad
import dev.kanso.service.TeamPace
import dev.kanso.service.TeamProgress
import dev.kanso.service.TeamService
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
 * A team named, and nothing a `TeamResponse` would have to go and count.
 *
 * That DTO carries a `ticketCount` and an `editable` flag, each of which costs a query, for
 * a heading and a sentence that want a name. `DeliveredCycleResponse` refuses
 * `CycleResponse` one screen up for the same reason.
 */
data class TeamRefResponse(val id: UUID, val name: String, val key: String) {
	companion object {
		fun of(team: Team) = TeamRefResponse(team.id, team.name, team.key)
	}
}

/**
 * Who else can read this page — on the response, so the page can say it.
 *
 * Empty lists are the common answer on a small instance and mean exactly what they say:
 * nobody but you. The client's sentence is built from these two rather than from a rule
 * restated on the client, which is the only version of this that cannot drift.
 */
data class ProgressReadersResponse(
	val instanceAdmins: List<PersonResponse>,
	val teams: List<TeamRefResponse>,
) {
	companion object {
		fun of(readers: ProgressReaders) = ProgressReadersResponse(
			instanceAdmins = readers.instanceAdmins.map(PersonResponse::of),
			teams = readers.teams.map(TeamRefResponse::of),
		)
	}
}

/**
 * One person's progress, with the arbitration already done and the subject named.
 *
 * [person] is what lets one shape serve three routes. It was on the response before there
 * was anything but the caller to name, on the argument that a page told who it is about
 * only by its own URL could not put a name in its heading without a second request — and
 * that is precisely what `/api/people/{id}/progress` now reads it for.
 */
data class ProgressResponse(
	val person: PersonResponse,
	val velocity: EffectiveVelocityResponse,
	/** Oldest first — the order the chart draws. */
	val delivered: List<DeliveredCycleResponse>,
	val load: OpenLoadResponse,
	/**
	 * Who else can read this, on every version of this page including the caller's own.
	 *
	 * Especially the caller's own. The other-person view is the one that makes this
	 * necessary, and the personal page is the only place the subject will ever look for it.
	 */
	val readers: ProgressReadersResponse,
) {
	companion object {
		fun of(progress: Progress, readers: ProgressReaders) = ProgressResponse(
			person = PersonResponse.of(progress.person),
			// The same DTO the settings screen already reads, so the sentence naming the
			// source in force is written once on the client and drawn on both screens.
			velocity = EffectiveVelocityResponse.of(progress.velocity),
			delivered = progress.delivered.map(DeliveredCycleResponse::of),
			load = OpenLoadResponse.of(progress.load),
			readers = ProgressReadersResponse.of(readers),
		)
	}
}

/**
 * A team's pace, with no `source` and no arbitration, because a team declares nothing.
 *
 * Not an [EffectiveVelocityResponse] with the fields left null: that type's whole subject
 * is which of two numbers won, and three permanently absent fields on it would invite a
 * client to caption a team's number with a rule that was never applied to it.
 */
data class TeamPaceResponse(
	val perWorkingDay: Double?,
	/** How many closed cycles the mean stands on. Zero means there was nothing to measure. */
	val measuredCycles: Int,
) {
	companion object {
		fun of(pace: TeamPace) = TeamPaceResponse(pace.perWorkingDay, pace.cycles.size)
	}
}

/**
 * A team's figures. Aggregates, and no field that names a person.
 *
 * The ranking is out of scope by the ticket, and the way to keep it out of scope is for the
 * response to have no row to sort — see [TeamProgress]. `byProject` and `byStatus` cut the
 * plate by things, never by people; the per-person cut of the same plate is the workload
 * screen, which has been open to every reader since screen 23 and is not repeated here.
 */
data class TeamProgressResponse(
	val team: TeamRefResponse,
	val pace: TeamPaceResponse,
	/** Oldest first — the order the chart draws. */
	val delivered: List<DeliveredCycleResponse>,
	val load: OpenLoadResponse,
) {
	companion object {
		fun of(progress: TeamProgress) = TeamProgressResponse(
			team = TeamRefResponse.of(progress.team),
			pace = TeamPaceResponse.of(progress.pace),
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
 * The wider views arrived in [AdminProgressController], beside this one, exactly as this
 * file predicted: they pass a different subject to the same `ProgressService.forPerson`,
 * nothing in this class or that service moved, and no computation was duplicated. The
 * reason that worked is that the subject is an argument all the way down.
 *
 * What did change here is [ProgressResponse.readers]. The moment somebody else can read a
 * person's figures, the person's own page is the only place they will ever look to find
 * that out, so it is answered on this route too and not only on the wide one.
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
	private val readable: ProgressAccess,
) {

	@GetMapping("/api/me/progress")
	@Transactional(readOnly = true)
	fun mine(@RequestParam teamId: UUID): ProgressResponse {
		val me = currentUser.require()
		return ProgressResponse.of(progress.forPerson(me, teamId), readable.readers(me, teamId))
	}
}

/**
 * The same page aimed at somebody else, and a team's version of it.
 *
 * **The rule is applied here, on the server, and that is the whole of this ticket.** A page
 * the front-end declines to draw is not a permission — the endpoint would answer anybody
 * who typed the URL — so the two routes below reach [ProgressAccess] before they reach a
 * service, and `ProgressLeakTest` drives all three branches of it through the filter chain
 * rather than calling the rule directly.
 *
 * **Both routes resolve their arguments before they ask about rights**, which is the
 * opposite of the order a private draft uses and is deliberate. A team and a person are
 * already public to any authenticated reader — `/api/teams` lists every team, `/api/people`
 * every account with its instance role — so there is nothing for a permission check to
 * protect by refusing first, and this way a caller who mistyped a UUID is told they
 * mistyped a UUID whatever their rights, instead of being told they are not an
 * administrator of a team that does not exist.
 */
@RestController
class AdminProgressController(
	private val currentUser: CurrentUser,
	private val progress: ProgressService,
	private val readable: ProgressAccess,
	private val teams: TeamService,
	private val users: UserRepository,
) {

	/**
	 * Somebody else's figures, in one team.
	 *
	 * The response is the very same [ProgressResponse] the personal page reads, which is
	 * what keeps KAN-40's two refusals — a waiting message instead of a lone bar, a stub
	 * instead of a missing bar — in force for a reader who is not the subject. They matter
	 * more there, not less: the person reading has no other way to know that the flat chart
	 * in front of them is one closed cycle rather than a quiet quarter.
	 */
	@GetMapping("/api/people/{userId}/progress")
	@Transactional(readOnly = true)
	fun person(@PathVariable userId: UUID, @RequestParam teamId: UUID): ProgressResponse {
		val team = teams.get(teamId)
		val subject = users.findById(userId) ?: throw NotFoundException("No user $userId")
		readable.requireReadable(currentUser.require(), subject, team.id)
		return ProgressResponse.of(
			progress.forPerson(subject, team.id),
			readable.readers(subject, team.id),
		)
	}

	/**
	 * A team's figures, and no `userId` anywhere on the way in or out.
	 *
	 * `?userId=` is not accepted and could not be honoured if it were: the response has no
	 * per-person field for it to fill. That is what keeps the ranking out of scope by
	 * construction rather than by a decision the next person to touch this file has to
	 * rediscover.
	 */
	@GetMapping("/api/teams/{teamId}/progress")
	@Transactional(readOnly = true)
	fun team(@PathVariable teamId: UUID): TeamProgressResponse {
		val team = teams.get(teamId)
		readable.requireTeamReadable(currentUser.require(), team.id)
		return TeamProgressResponse.of(progress.forTeam(team))
	}
}

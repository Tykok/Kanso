package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.service.BulkEdit
import dev.kanso.service.BulkEditService
import dev.kanso.service.CycleService
import dev.kanso.service.CycleState
import dev.kanso.service.SavedViewService
import dev.kanso.service.TriageDecision
import dev.kanso.service.TriageService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import dev.kanso.service.WorkloadService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * The four organising surfaces, screens 19 to 23.
 *
 * Reads are open and writes are scoped, as `architecture.md` states. Nothing here scopes a
 * `GET` by team: a cycle report and a workload chart are exactly the kind of "who else is
 * doing what" the timeline's context rows already answer for anyone.
 *
 * Some routes hang under `/api/teams/{teamId}` without being `TeamController`'s. A cycle is
 * a team's plan and a saved view is a team's question, so that is where they live; the
 * controller they belong to is the one that owns their service.
 */

@RestController
@RequestMapping("/api")
class CycleController(
	private val cycles: CycleService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/teams/{teamId}/cycles")
	fun list(@PathVariable teamId: UUID): List<CycleResponse> =
		cycles.list(teamId).map(CycleResponse::of)

	/**
	 * The sidebar's `Cycle` row points at `/cycles/current`, so the client resolves the
	 * word "current" through this rather than guessing which number is in progress.
	 */
	@GetMapping("/teams/{teamId}/cycles/current")
	fun current(@PathVariable teamId: UUID): CycleReportResponse =
		CycleReportResponse.of(cycles.report(cycles.active(teamId).id))

	/**
	 * A literal segment, as `/api/tickets/by-key/{teamKey}/{number}` already does.
	 * `/cycles/{number}` beside `/cycles/current` would leave the word `current` resolving
	 * by Spring's pattern precedence rather than by anything written down here.
	 */
	@GetMapping("/teams/{teamId}/cycles/by-number/{number}")
	fun byNumber(@PathVariable teamId: UUID, @PathVariable number: Int): CycleReportResponse =
		CycleReportResponse.of(cycles.report(cycles.byNumber(teamId, number).id))

	@GetMapping("/cycles/{id}")
	fun report(@PathVariable id: UUID): CycleReportResponse =
		CycleReportResponse.of(cycles.report(id))

	@PostMapping("/teams/{teamId}/cycles")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@PathVariable teamId: UUID,
		@RequestBody request: CycleCreateRequest,
	): CycleResponse = CycleResponse.of(
		cycles.create(
			actor = currentUser.require(),
			teamId = teamId,
			number = request.number,
			startsOn = request.startsOn,
			endsOn = request.endsOn,
			state = CycleState.from(request.state),
		),
		ticketCount = 0,
	)

	@PutMapping("/cycles/{id}/state")
	fun setState(@PathVariable id: UUID, @RequestBody request: CycleStateRequest): CycleResponse =
		CycleResponse.of(
			cycles.setState(currentUser.require(), id, CycleState.from(request.state)),
			ticketCount = cycles.report(id).total,
		)

	/**
	 * `PUT` rather than `POST`: putting a ticket in a cycle is idempotent and takes it out
	 * of whichever one it was in, so sending it twice is not two moves.
	 */
	@PutMapping("/cycles/{id}/tickets")
	fun addTickets(@PathVariable id: UUID, @RequestBody request: TicketIdsRequest): CycleReportResponse {
		cycles.addTickets(currentUser.require(), id, request.ticketIds)
		return CycleReportResponse.of(cycles.report(id))
	}

	@DeleteMapping("/cycles/{id}/tickets")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeTickets(@PathVariable id: UUID, @RequestBody request: TicketIdsRequest) {
		cycles.removeTickets(currentUser.require(), id, request.ticketIds)
	}
}

@RestController
@RequestMapping("/api")
class TriageController(
	private val triage: TriageService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/teams/{teamId}/triage")
	fun queue(
		@PathVariable teamId: UUID,
		@RequestParam(defaultValue = "50") limit: Int,
	): TriageQueueResponse = TriageQueueResponse.of(triage.queue(teamId, limit.coerceIn(1, 200)))

	/** The trace. "Nothing is lost" is only true if it can be read back. */
	@GetMapping("/teams/{teamId}/triage/decisions")
	fun decisions(
		@PathVariable teamId: UUID,
		@RequestParam(defaultValue = "100") limit: Int,
	): List<TriageRulingResponse> =
		triage.decisions(teamId, limit.coerceIn(1, 500)).map(TriageRulingResponse::of)

	/**
	 * Lives here rather than on `TicketController` because it is triage's question, and
	 * `TriageService` is what answers it. The URL is the ticket's because that is what it
	 * is about.
	 */
	@GetMapping("/tickets/{id}/similar")
	fun similar(
		@PathVariable id: UUID,
		@RequestParam(defaultValue = "5") limit: Int,
	): List<SimilarTicketResponse> =
		triage.similar(id, limit.coerceIn(1, 20)).map(SimilarTicketResponse::of)

	@PostMapping("/triage/decisions")
	@ResponseStatus(HttpStatus.CREATED)
	fun decide(@RequestBody request: TriageDecisionRequest): TriageRulingResponse =
		TriageRulingResponse.of(
			triage.decide(
				actor = currentUser.require(),
				ticketId = request.ticketId,
				decision = TriageDecision.from(request.decision),
				duplicateOf = request.duplicateOfId,
			)
		)
}

@RestController
@RequestMapping("/api")
class SavedViewController(
	private val views: SavedViewService,
	private val bulk: BulkEditService,
	private val currentUser: CurrentUser,
) {

	@GetMapping("/teams/{teamId}/views")
	fun list(@PathVariable teamId: UUID): List<SavedViewResponse> =
		views.list(currentUser.require(), teamId).map(SavedViewResponse::of)

	@GetMapping("/views/{id}")
	fun get(@PathVariable id: UUID): SavedViewResponse {
		val actor = currentUser.require()
		val view = views.get(actor, id)
		// The count is what the sidebar row shows, and asking for the view without it would
		// make the header and the sidebar disagree the moment one was refetched alone. It is
		// `views.count`, not the size of a page of rows: the page stops at 200 and the
		// sidebar's own number never did, so the two used to part company on a large view.
		return SavedViewResponse.of(view, views.count(actor, id))
	}

	/**
	 * The same view, stacked into the buckets it stores a `groupBy` for — and the reason
	 * a group header can say `Todo · 29` while carrying twenty rows.
	 *
	 * The stacking and the sort are the view's own and are not parameters here: they are
	 * the stored question, and a caller that could override them would be asking a
	 * different one under this view's name.
	 */
	@GetMapping("/views/{id}/grouped")
	fun grouped(
		@PathVariable id: UUID,
		@RequestParam(defaultValue = "200") limit: Int,
		@RequestParam(defaultValue = "0") offset: Long,
	): TicketGroupsResponse {
		val actor = currentUser.require()
		val view = views.get(actor, id)
		return TicketGroupsResponse.of(
			groupBy = view.groupBy,
			sortBy = view.sortBy,
			groups = views.grouped(actor, id, limit.coerceIn(1, 500), offset.coerceAtLeast(0)),
		)
	}

	@PostMapping("/teams/{teamId}/views")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@PathVariable teamId: UUID,
		@Valid @RequestBody request: SavedViewCreateRequest,
	): SavedViewResponse {
		val actor = currentUser.require()
		val created = views.create(
			actor = actor,
			teamId = teamId,
			name = request.name,
			shared = request.shared,
			filters = request.filters,
			groupBy = ViewGroupBy.from(request.groupBy),
			sortBy = ViewSortBy.from(request.sortBy),
		)
		return SavedViewResponse.of(created, views.count(actor, created.id))
	}

	@PatchMapping("/views/{id}")
	fun patch(@PathVariable id: UUID, @RequestBody request: SavedViewPatchRequest): SavedViewResponse {
		val actor = currentUser.require()
		val updated = views.update(
			actor = actor,
			id = id,
			name = request.name,
			shared = request.shared,
			filters = request.filters,
			groupBy = request.groupBy?.let(ViewGroupBy::from),
			sortBy = request.sortBy?.let(ViewSortBy::from),
		)
		return SavedViewResponse.of(updated, views.count(actor, id))
	}

	@DeleteMapping("/views/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = views.delete(currentUser.require(), id)

	/**
	 * The strip at the bottom of screen 21. One request for the whole selection, because
	 * one refused row has to mean nothing changed — which N requests cannot promise.
	 */
	@PostMapping("/tickets/bulk")
	fun bulkEdit(@RequestBody request: BulkEditRequest): BulkEditResponse = BulkEditResponse(
		bulk.apply(
			actor = currentUser.require(),
			edit = BulkEdit(
				ticketIds = request.ticketIds,
				status = request.status,
				priority = request.priority?.let(TicketPriority::from),
				assigneeIds = request.assigneeIds,
				cycleId = request.cycleId,
				labelId = request.labelId,
			),
		)
	)

	/**
	 * A separate route from the edit rather than a `delete: true` flag on it: a body that
	 * can mean "set the status" or "destroy these" is one typo away from the wrong one.
	 */
	@PostMapping("/tickets/bulk/delete")
	fun bulkDelete(@RequestBody request: TicketIdsRequest): BulkEditResponse =
		BulkEditResponse(bulk.delete(currentUser.require(), request.ticketIds))
}

@RestController
@RequestMapping("/api")
class WorkloadController(private val workload: WorkloadService) {

	@GetMapping("/teams/{teamId}/workload")
	fun forTeam(
		@PathVariable teamId: UUID,
		@RequestParam(required = false) cycleId: UUID?,
	): WorkloadResponse = WorkloadResponse.of(workload.forTeam(teamId, cycleId))
}

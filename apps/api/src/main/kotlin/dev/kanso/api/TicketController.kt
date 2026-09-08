package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.DefaultStatus
import dev.kanso.repo.TicketFilters
import dev.kanso.service.ScheduleService
import dev.kanso.service.TicketFilterVocabulary
import dev.kanso.service.TicketPatch
import dev.kanso.service.TicketService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/tickets")
class TicketController(
	private val tickets: TicketService,
	private val schedule: ScheduleService,
	private val currentUser: CurrentUser,
) {

	/**
	 * The main list, which is a saved view nobody saved.
	 *
	 * The filters arrive as the raw query string rather than as one typed parameter each,
	 * because the vocabulary they are checked against is
	 * [TicketFilterVocabulary.SERVED] and a typed parameter list cannot be checked
	 * against anything — Spring would bind the names it knows and drop the rest, so
	 * `?labelColour=indigo` would answer with a page that looks filtered and is not. That
	 * is the failure the saved view's gate has always refused, and this endpoint had no
	 * way to refuse until it shared the gate.
	 *
	 * Everything named as a parameter here is scope rather than filter: which room the
	 * question is asked in, and how much of the answer to hand back. `SCOPE_PARAMS` is
	 * what gets subtracted before the rest goes through the gate, so a name added to one
	 * of the two lists and not the other is either refused or silently ignored — both of
	 * which show up immediately.
	 */
	@GetMapping
	fun list(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(defaultValue = "false") includeDescendants: Boolean,
		@RequestParam(defaultValue = "false") includeArchived: Boolean,
		@RequestParam(defaultValue = "200") limit: Int,
		@RequestParam(defaultValue = "0") offset: Long,
		@RequestParam(defaultValue = "updated") sort: String,
		@RequestParam query: MultiValueMap<String, String>,
	): List<TicketResponse> = tickets.list(
		teamId = teamId,
		includeDescendants = includeDescendants,
		includeArchived = includeArchived,
		filters = filtersFrom(query),
		sortBy = ViewSortBy.from(sort),
		limit = limit.coerceIn(1, 500),
		offset = offset.coerceAtLeast(0),
	).map(TicketResponse::of)

	/**
	 * The query string, minus the scope, through the same gate a saved view is written
	 * through — and with `projectId`/`assigneeId` folded onto the names they are the
	 * singular of, so the shape the web app sends today keeps answering what it always did.
	 */
	private fun filtersFrom(query: MultiValueMap<String, String>): TicketFilters {
		val asked = buildMap<String, MutableList<String>> {
			query.forEach { (name, values) ->
				if (name in SCOPE_PARAMS) return@forEach
				getOrPut(TicketFilterVocabulary.ALIASES[name] ?: name) { mutableListOf() }.addAll(values)
			}
		}
		return TicketFilterVocabulary.parseServed(asked)
	}

	/**
	 * The vocabulary itself, published — the list [list] and a saved view are both held to.
	 *
	 * It exists because the only other way for a client to know which facets can be asked
	 * for is to write them down a second time, and a second copy of a closed vocabulary is
	 * the thing the gate below was built to stop being necessary. A web app that retyped
	 * these twelve names would drift from them, and the symptom would be a chip drawn on
	 * screen that the server answers with a 400.
	 *
	 * Names only. What a facet *means* to a reader — that `assignee` is picked from the
	 * team's people and `estimateMin` is a bound on the points — is a question about a
	 * control, and the server has no controls; it would be publishing a guess about a
	 * screen it cannot see. What it does own is which questions it will answer, and that
	 * is exactly what is here.
	 *
	 * Before `/{id}` because Spring matches a literal segment ahead of a template, and
	 * `filters` is not a UUID: the two cannot collide.
	 */
	@GetMapping("/filters")
	fun filters(): ServedFiltersResponse = ServedFiltersResponse(TicketFilterVocabulary.SERVED.sorted())

	/**
	 * The same list, stacked into its buckets — and the reason the counts on a group
	 * header are true.
	 *
	 * Every parameter [list] takes, plus `groupBy`, and the filters go through the same
	 * gate: two doors onto one question that disagreed about which questions exist would
	 * be exactly the divergence the shared vocabulary was built to end. `groupBy` joins
	 * `SCOPE_PARAMS` for the same reason `sort` is in it — how an answer is stacked is a
	 * wall of the room, not a chip in it.
	 *
	 * A route of its own rather than a shape [list] switches into when `groupBy` is
	 * present: a response whose type depends on a query parameter is one every caller has
	 * to branch on, and every other screen in this app reads the flat one.
	 *
	 * Before `/{id}` for the reason [filters] gives — a literal segment beats a template,
	 * and `grouped` is not a UUID.
	 */
	@GetMapping("/grouped")
	fun grouped(
		@RequestParam(required = false) teamId: UUID?,
		@RequestParam(defaultValue = "false") includeDescendants: Boolean,
		@RequestParam(defaultValue = "false") includeArchived: Boolean,
		@RequestParam(defaultValue = "status") groupBy: String,
		@RequestParam(defaultValue = "updated") sort: String,
		@RequestParam(defaultValue = "200") limit: Int,
		@RequestParam(defaultValue = "0") offset: Long,
		@RequestParam query: MultiValueMap<String, String>,
	): TicketGroupsResponse {
		val stacking = ViewGroupBy.from(groupBy)
		val sorting = ViewSortBy.from(sort)
		return TicketGroupsResponse.of(
			groupBy = stacking,
			sortBy = sorting,
			groups = tickets.grouped(
				teamId = teamId,
				includeDescendants = includeDescendants,
				includeArchived = includeArchived,
				filters = filtersFrom(query),
				groupBy = stacking,
				sortBy = sorting,
				// The same clamp the flat list applies, and it has to be the same number:
				// `limit` here bounds the rows, not the buckets, so a grouped page and a flat
				// one asked with the same limit carry the same amount of the answer.
				limit = limit.coerceIn(1, 500),
				offset = offset.coerceAtLeast(0),
			),
		)
	}

	/**
	 * The tickets no team has claimed, which are in no other list this API serves.
	 *
	 * Before `/{id}` for the reason `filters` is: Spring matches a literal segment ahead of
	 * a template, and `drafts` is not a UUID, so the two cannot collide.
	 */
	@GetMapping("/drafts")
	fun drafts(@RequestParam(defaultValue = "200") limit: Int): List<TicketResponse> =
		tickets.drafts(currentUser.require(), limit.coerceIn(1, 500)).map(TicketResponse::of)

	/**
	 * By id, which is the only address a ticket with no team has — and the one address that
	 * survives it gaining one, since the identifier is minted at that moment.
	 */
	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TicketResponse =
		TicketResponse.of(tickets.get(currentUser.require(), id))

	/** Lookup by the identifier people actually use: `/api/tickets/by-key/KAN/142`. */
	@GetMapping("/by-key/{teamKey}/{number}")
	fun getByKey(@PathVariable teamKey: String, @PathVariable number: Int): TicketResponse =
		TicketResponse.of(tickets.getByIdentifier(teamKey, number))

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: TicketCreateRequest): TicketResponse = TicketResponse.of(
		tickets.create(
			actor = currentUser.require(),
			teamId = request.teamId,
			title = request.title,
			description = request.description,
			status = request.status,
			priority = TicketPriority.from(request.priority),
			estimate = request.estimate,
			start = request.start?.toDomain(),
			due = request.due?.toDomain(),
			projectId = request.projectId,
			assigneeIds = request.assigneeIds,
			docIds = request.docIds,
		)
	)

	@PatchMapping("/{id}")
	fun patch(@PathVariable id: UUID, @RequestBody request: TicketPatchRequest): TicketResponse {
		val patch = request.validated().let {
			TicketPatch(
				title = it.title,
				description = it.description,
				status = it.status,
				priority = it.priority?.let(TicketPriority::from),
				estimate = it.estimate,
				start = it.start?.toDomain(),
				due = it.due?.toDomain(),
				projectId = it.projectId,
				teamId = it.teamId,
				archived = it.archived,
				assigneeIds = it.assigneeIds,
				docIds = it.docIds,
				unset = it.unset,
			)
		}
		return TicketResponse.of(tickets.patch(currentUser.require(), id, patch))
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun delete(@PathVariable id: UUID) = tickets.delete(currentUser.require(), id)

	@PutMapping("/{id}/assignees")
	fun setAssignees(@PathVariable id: UUID, @RequestBody userIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setAssignees(currentUser.require(), id, userIds))

	@PutMapping("/{id}/docs")
	fun setDocs(@PathVariable id: UUID, @RequestBody docIds: List<UUID>): TicketResponse =
		TicketResponse.of(tickets.setDocs(currentUser.require(), id, docIds))

	/**
	 * `POST /api/tickets/{id}/dependencies` — `{id}` is the successor, the body names
	 * the predecessor, which is the direction the arrow is drawn in the timeline.
	 */
	@PostMapping("/{id}/dependencies")
	@ResponseStatus(HttpStatus.CREATED)
	fun addDependency(
		@PathVariable id: UUID,
		@RequestBody request: DependencyRequest,
	): CascadeResponse = CascadeResponse(schedule.link(currentUser.require(), request.predecessorId, id))

	@DeleteMapping("/{id}/dependencies/{predecessorId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun removeDependency(@PathVariable id: UUID, @PathVariable predecessorId: UUID) {
		schedule.unlink(currentUser.require(), predecessorId, id)
	}

	companion object {
		/**
		 * The names [list] answers to that are not filters, and so must not reach the gate.
		 *
		 * `projectId` and `assigneeId` are deliberately absent: they *are* filters, spelled
		 * the way this endpoint has always spelled them, and [TicketFilterVocabulary.ALIASES]
		 * renames them on the way through rather than dropping them here.
		 */
		private val SCOPE_PARAMS = setOf(
			"teamId",
			"includeDescendants",
			"includeArchived",
			"limit",
			"offset",
			"sort",
			// How the answer is stacked, which `/grouped` takes and the flat list does not.
			// It sits in the one list both routes subtract, because a name that is a filter
			// on one door and scope on the other is the divergence this set exists to stop.
			"groupBy",
		)
	}
}

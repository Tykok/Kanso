package dev.kanso.api

import dev.kanso.domain.User
import dev.kanso.service.Cycle
import dev.kanso.service.CycleReport
import dev.kanso.service.CycleSummary
import dev.kanso.service.SavedView
import dev.kanso.service.SavedViewSummary
import dev.kanso.service.SimilarTicket
import dev.kanso.service.TriageQueue
import dev.kanso.service.TriageRuling
import dev.kanso.service.Workload
import dev.kanso.service.WorkloadRow
import jakarta.validation.constraints.NotBlank
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The wire shapes for screens 19 to 23.
 *
 * Declared here rather than appended to `Dtos.kt`, which every branch of the fan-out would
 * otherwise be editing at once. The precedent is `PreferencesResponse`, which lives beside
 * its own controller for the same reason.
 *
 * Every status-keyed map is sent with its keys as the wire strings, so the client reads
 * `byStatus.in_progress` and not an index into an order it would have to know.
 */

// --- cycles ---------------------------------------------------------------

data class CycleResponse(
	val id: UUID,
	val teamId: UUID,
	val number: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	val state: String,
	val ticketCount: Int,
) {
	companion object {
		fun of(summary: CycleSummary) = of(summary.cycle, summary.ticketCount)

		fun of(cycle: Cycle, ticketCount: Int) = CycleResponse(
			id = cycle.id,
			teamId = cycle.teamId,
			number = cycle.number,
			startsOn = cycle.startsOn,
			endsOn = cycle.endsOn,
			state = cycle.state.wire,
			ticketCount = ticketCount,
		)
	}
}

data class RemainingDayResponse(
	val day: LocalDate,
	val open: Int,
	val openPoints: Int,
	val projected: Boolean,
)

/** The cycle's points, and how many of its tickets they cannot speak for. */
data class CyclePointsResponse(
	val total: Int,
	val done: Int,
	val percent: Int,
	val unestimated: Int,
)

data class CycleReportResponse(
	val cycle: CycleResponse,
	val total: Int,
	val done: Int,
	val percent: Int,
	val points: CyclePointsResponse,
	val byStatus: Map<String, Int>,
	val daysLeft: Int,
	val remaining: List<RemainingDayResponse>,
	val slipping: List<TicketResponse>,
	val tickets: List<TicketResponse>,
) {
	companion object {
		fun of(report: CycleReport) = CycleReportResponse(
			cycle = CycleResponse.of(report.cycle, report.total),
			total = report.total,
			done = report.done,
			percent = report.percent,
			points = CyclePointsResponse(
				total = report.points.total,
				done = report.points.done,
				percent = report.points.percent,
				unestimated = report.points.unestimated,
			),
			byStatus = report.byStatus.mapKeys { it.key.wire },
			daysLeft = report.daysLeft,
			remaining = report.remaining.map {
				RemainingDayResponse(it.day, it.open, it.openPoints, it.projected)
			},
			slipping = report.slipping.map(TicketResponse::of),
			tickets = report.tickets.map(TicketResponse::of),
		)
	}
}

data class CycleCreateRequest(
	val number: Int,
	val startsOn: LocalDate,
	val endsOn: LocalDate,
	val state: String = "upcoming",
)

data class CycleStateRequest(val state: String)

data class TicketIdsRequest(val ticketIds: List<UUID> = emptyList())

// --- triage ---------------------------------------------------------------

data class TriageQueueResponse(val total: Int, val items: List<TicketResponse>) {
	companion object {
		fun of(queue: TriageQueue) =
			TriageQueueResponse(queue.total, queue.items.map(TicketResponse::of))
	}
}

data class SimilarTicketResponse(
	val ticket: TicketResponse,
	/** A whole percent: the drawing prints "68 %". */
	val similarity: Int,
) {
	companion object {
		fun of(similar: SimilarTicket) =
			SimilarTicketResponse(TicketResponse.of(similar.detail), similar.similarity)
	}
}

data class TriageDecisionRequest(
	val ticketId: UUID,
	val decision: String,
	/** Required by `duplicate`, refused by everything else. */
	val duplicateOfId: UUID? = null,
)

data class TriageRulingResponse(
	val ticket: TicketResponse,
	val decision: String,
	val duplicateOf: TicketResponse?,
	val decidedBy: PersonResponse?,
	val decidedAt: OffsetDateTime,
) {
	companion object {
		fun of(ruling: TriageRuling) = TriageRulingResponse(
			ticket = TicketResponse.of(ruling.ticket),
			decision = ruling.decision.wire,
			duplicateOf = ruling.duplicateOf?.let(TicketResponse::of),
			decidedBy = ruling.decidedBy?.let(PersonResponse::of),
			decidedAt = ruling.decidedAt,
		)
	}
}

/**
 * Just enough of a person to draw initials in a circle.
 *
 * Deliberately not the account: this is on a screen anyone in the instance can open, and
 * a workload chart has no use for an email address.
 */
data class PersonResponse(val id: UUID, val displayName: String, val avatarUrl: String?) {
	companion object {
		fun of(user: User) = PersonResponse(user.id, user.displayName, user.avatarUrl)
	}
}

// --- saved views ----------------------------------------------------------

data class SavedViewResponse(
	val id: UUID,
	val teamId: UUID,
	val name: String,
	val shared: Boolean,
	val filters: Map<String, Any?>,
	val groupBy: String,
	val sortBy: String,
	val createdBy: UUID?,
	val count: Int,
) {
	companion object {
		fun of(summary: SavedViewSummary) = of(summary.view, summary.count)

		fun of(view: SavedView, count: Int) = SavedViewResponse(
			id = view.id,
			teamId = view.teamId,
			name = view.name,
			shared = view.shared,
			filters = view.filters,
			groupBy = view.groupBy.wire,
			sortBy = view.sortBy.wire,
			createdBy = view.createdBy,
			count = count,
		)
	}
}

data class SavedViewCreateRequest(
	@field:NotBlank val name: String,
	val shared: Boolean = false,
	val filters: Map<String, Any?> = emptyMap(),
	val groupBy: String = "status",
	val sortBy: String = "priority",
)

/**
 * Absent means unchanged. `filters` is the one field whose empty value is meaningful —
 * removing the last chip leaves `{}` — so it is sent whole rather than merged.
 */
data class SavedViewPatchRequest(
	val name: String? = null,
	val shared: Boolean? = null,
	val filters: Map<String, Any?>? = null,
	val groupBy: String? = null,
	val sortBy: String? = null,
)

// --- bulk edit ------------------------------------------------------------

data class BulkEditRequest(
	val ticketIds: List<UUID> = emptyList(),
	val status: String? = null,
	val priority: String? = null,
	val assigneeIds: List<UUID>? = null,
	val cycleId: UUID? = null,
	/** Added to every selected row, not replacing what they wear — see [BulkEdit]. */
	val labelId: UUID? = null,
)

data class BulkEditResponse(val changed: Int)

// --- workload -------------------------------------------------------------

data class WorkloadRowResponse(
	val person: PersonResponse?,
	val total: Int,
	val points: Int,
	val unestimated: Int,
	val byStatus: Map<String, Int>,
	val urgentOverThreeDays: Int,
	val oldestOpenDays: Int,
) {
	companion object {
		fun of(row: WorkloadRow) = WorkloadRowResponse(
			person = row.person?.let(PersonResponse::of),
			total = row.total,
			points = row.points,
			unestimated = row.unestimated,
			byStatus = row.byStatus.mapKeys { it.key.wire },
			urgentOverThreeDays = row.urgentOverThreeDays,
			oldestOpenDays = row.oldestOpenDays,
		)
	}
}

data class WorkloadResponse(val cycleId: UUID?, val rows: List<WorkloadRowResponse>) {
	companion object {
		fun of(workload: Workload) =
			WorkloadResponse(workload.cycleId, workload.rows.map(WorkloadRowResponse::of))
	}
}

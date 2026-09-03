package dev.kanso.api

import dev.kanso.service.CycleTime
import dev.kanso.service.CycleTimePoint
import dev.kanso.service.Insights
import dev.kanso.service.Wip
import java.util.UUID

/**
 * KAN-23 on the wire, in its own file rather than at the foot of `ProgressController`.
 *
 * That file is already the length one person can re-read in a sitting, and these four types
 * are read by two responses on it. `OrganiseDtos.kt` is the precedent: the DTOs of a feature
 * live beside each other, and the controller stays the list of routes.
 *
 * **Every nullable field below is ABSENT from the JSON, not `null`.** Jackson is configured
 * to omit nulls here, so a client type claiming `medianHours: number | null` would read
 * `undefined` and print it — which is how the `Last used Invalid Date` on the tokens screen
 * happened. `lib/api/core.ts` spells all four of them `?:` and the components compare
 * against `undefined`.
 */

/**
 * A median in hours, and how much of the sample it is standing on.
 *
 * Hours and not working days, which is the one thing a reader of this type has to know: the
 * pace on the same screen is per working day and this is not. Elapsed time is what somebody
 * waiting for a ticket experienced, weekend included — see [dev.kanso.service.CycleTimeService].
 */
data class CycleTimeResponse(
	/** Absent — never `0` — when nothing in the sample could be measured. */
	val medianHours: Double?,
	val measured: Int,
	/** Delivered with no recorded start. Absent from the median, never zero. */
	val unmeasured: Int,
) {
	companion object {
		fun of(cycleTime: CycleTime) =
			CycleTimeResponse(cycleTime.medianHours, cycleTime.measured, cycleTime.unmeasured)
	}
}

/**
 * One point of the trend: a cycle named, and what its tickets took.
 *
 * [cycleId] and [number] and no dates, unlike [DeliveredCycleResponse] one file over. This
 * series is drawn against the very same closed cycles as that one and labelled by the same
 * numbers, so its two dates would be two fields no axis reads — the argument that DTO makes
 * for refusing a `CycleResponse`, one level smaller.
 */
data class CycleTimePointResponse(
	val cycleId: UUID,
	val number: Int,
	val cycleTime: CycleTimeResponse,
) {
	companion object {
		fun of(point: CycleTimePoint) = CycleTimePointResponse(
			cycleId = point.cycle.id,
			number = point.cycle.number,
			cycleTime = CycleTimeResponse.of(point.cycleTime),
		)
	}
}

/**
 * What is in flight now, and since when.
 *
 * [load] is a [LoadSliceResponse] and not three loose fields, because it *is* the same cut
 * of the same plate the progress response already draws — the started part of it. The
 * screens can say "5 of your 12 open tickets are in flight" without adding two numbers the
 * server did not agree to.
 */
data class WipResponse(
	val load: LoadSliceResponse,
	/** Both absent together when nothing in flight has a recorded start. */
	val medianAgeHours: Double?,
	val oldestAgeHours: Double?,
	val unmeasured: Int,
) {
	companion object {
		fun of(wip: Wip) = WipResponse(
			load = LoadSliceResponse.of(wip.load),
			medianAgeHours = wip.medianAgeHours,
			oldestAgeHours = wip.oldestAgeHours,
			unmeasured = wip.unmeasured,
		)
	}
}

data class InsightsResponse(
	val cycleTime: CycleTimeResponse,
	/** Oldest first — the order the chart draws, decided here so no caller can forget. */
	val trend: List<CycleTimePointResponse>,
	val wip: WipResponse,
) {
	companion object {
		fun of(insights: Insights) = InsightsResponse(
			cycleTime = CycleTimeResponse.of(insights.cycleTime),
			trend = insights.trend.map(CycleTimePointResponse::of),
			wip = WipResponse.of(insights.wip),
		)
	}
}

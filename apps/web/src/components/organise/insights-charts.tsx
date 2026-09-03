"use client";

import { GroupLabel } from "@/components/ui/group-label";
import type { CycleTimePoint, Insights } from "@/lib/api";
import { cycleTimeTrend, duration, durationScale } from "@/lib/insights";
import { heights } from "./burndown";

/**
 * KAN-23's two drawings, shared by the personal page and the team page.
 *
 * `progress-charts.tsx` is the neighbour and this file copies three of its decisions rather
 * than reinventing them, because they were all bought with a bug.
 *
 * **The geometry is `burndown.ts`.** `heights` again — there is one chart engine in this app
 * and this file did not add a second. A cycle-time series scaled against its own tallest bar
 * for the same reason a burn-down is: the question is the *shape*, and dividing by a total
 * nobody ever waited would flatten it.
 *
 * **Three sibling rows over one flex template, never one column per cycle.** A bar's
 * `height: %` resolves against its flex parent's content box, so a column that also held two
 * lines of text gave the tallest bars a basis two text lines short of the chart — they hit
 * the clamp together and two different values drew the same height. That bug is invisible in
 * a test suite and obvious on screen, and it is the reason the middle row below holds the
 * chart and nothing else.
 *
 * **A cycle with no measurable median is hatched, not stubbed.** This is the one place where
 * this file deliberately differs from its neighbour. The delivered chart gives a cycle
 * somebody shipped nothing in a visible *stub*, because zero points is a true reading of that
 * cycle. Zero hours is not: a cycle with nothing delivered has no cycle time at all, and a
 * two-pixel bar at the bottom of it would read as "instant" — the one misreading this chart
 * must not permit. So it takes the burn-down's existing vocabulary for exactly this, where a
 * hatch already means "nobody measured this".
 */

/**
 * Median cycle time, and the trend across the same closed cycles as the bars above.
 *
 * The refusal to draw a lone bar is `cycleTimeTrend`, in `lib/insights.ts`, so it is asserted
 * rather than eyeballed — and it reuses screen 40's threshold rather than declaring a second
 * one, so this chart and the one above it can never disagree about whether two is enough.
 */
export function CycleTimeTrend({ trend }: { trend: CycleTimePoint[] }) {
  const verdict = cycleTimeTrend(trend);

  return (
    <section className="flex flex-col gap-3">
      <GroupLabel className="px-0 pt-0">Cycle time per cycle</GroupLabel>

      {!verdict.drawable ? (
        <p
          className="m-0 max-w-[620px] text-12 text-muted-foreground"
          data-testid="cycle-time-waiting"
        >
          {verdict.waiting}
        </p>
      ) : (
        <Chart trend={trend} />
      )}
    </section>
  );
}

function Chart({ trend }: { trend: CycleTimePoint[] }) {
  // Absent medians count as zero for the *scale* only — they draw a hatch and not a bar, so
  // no height is ever derived from a number the server did not send. `heights` already
  // answers a series of nothing but zeros with zeros rather than the NaN a browser drops
  // silently, which is why an all-hatched chart still renders as an empty frame.
  const tall = heights(trend.map((point) => point.cycleTime.medianHours ?? 0));
  const measured = trend.filter((point) => point.cycleTime.medianHours !== undefined);
  const unmeasured = trend.reduce((sum, point) => sum + point.cycleTime.unmeasured, 0);
  // One unit across the whole axis, off the tallest bar. Absent medians are not in the
  // choice: a cycle that delivered nothing must not decide what unit the others read in.
  const scale = durationScale(measured.map((point) => point.cycleTime.medianHours ?? 0));

  return (
    <>
      <div
        className="flex flex-col gap-1.5"
        role="img"
        aria-label={trend
          .map((point) =>
            point.cycleTime.medianHours === undefined
              ? `cycle ${point.number}: not measurable`
              : `cycle ${point.number}: ${duration(point.cycleTime.medianHours)}`,
          )
          .join(", ")}
      >
        <div className="flex items-end gap-2">
          {trend.map((point) => (
            <span
              key={point.cycleId}
              className="flex-1 text-center font-mono text-11 text-muted-foreground"
            >
              {/* A bare number in the axis's one unit — see `durationScale`. An em dash, not
                  a 0, where there is no median: a cycle with nothing delivered took no time
                  because nothing happened in it, which is not the same as instantly. */}
              {point.cycleTime.medianHours === undefined
                ? "—"
                : scale.format(point.cycleTime.medianHours)}
            </span>
          ))}
        </div>

        <div className="flex h-[120px] items-end gap-2">
          {trend.map((point, at) => (
            <div key={point.cycleId} className="flex h-full flex-1 items-end justify-center">
              {point.cycleTime.medianHours === undefined ? (
                // Full height and hatched: the frame says "this cycle is in the series" and
                // the hatch says "nothing measured it". A short hatch would be read as a
                // small number.
                <span
                  title={`Cycle ${point.number}: nothing delivered with a recorded start`}
                  className="h-full w-full max-w-[64px] rounded-t-[2px] bg-[repeating-linear-gradient(135deg,var(--rule)_0_3px,transparent_3px_6px)]"
                />
              ) : (
                <span
                  title={
                    `Cycle ${point.number}: median ${duration(point.cycleTime.medianHours)}` +
                    ` over ${point.cycleTime.measured} delivered ticket` +
                    `${point.cycleTime.measured === 1 ? "" : "s"}`
                  }
                  className="w-full max-w-[64px] rounded-t-[2px] bg-primary"
                  // Clamped to a visible stub, as the delivered chart clamps its own: a
                  // cycle whose median rounds to a pixel is still a measurement, and here
                  // the label above it says which.
                  style={{ height: `${Math.max(tall[at], 2)}%` }}
                />
              )}
            </div>
          ))}
        </div>

        <div className="flex gap-2">
          {trend.map((point) => (
            <span key={point.cycleId} className="flex-1 text-center text-11 text-faint">
              {point.number}
            </span>
          ))}
        </div>
      </div>

      {/* "Taller is slower", never "worse". A cycle where the team took on two hard tickets
          is not a cycle where they did badly, and this number cannot tell the difference —
          see the head of `lib/insights.ts`. */}
      <p className="m-0 max-w-[620px] text-11 text-muted-foreground">
        Median {scale.unit} per cycle · taller is slower · elapsed time, weekends included ·{" "}
        {measured.length} of {trend.length} closed cycles could be measured
        {unmeasured > 0
          ? ` · ${unmeasured} delivered ticket${unmeasured === 1 ? "" : "s"} recorded no start`
          : ""}
      </p>
    </>
  );
}

/**
 * The two numbers side by side, with their units spelled out on the page.
 *
 * The units are printed rather than assumed because this screen carries two that look
 * interchangeable and are not: the pace above is points per *working* day and both figures
 * here are elapsed wall-clock. A reader who multiplied one by the other would get a number
 * with no meaning, and the only defence against that is saying so where they are drawn.
 */
export function InsightsFigures({ insights }: { insights: Insights }) {
  return (
    <div className="flex flex-wrap gap-8">
      <Figure
        label="Median cycle time"
        hours={insights.cycleTime.medianHours}
        note={
          insights.cycleTime.medianHours === undefined
            ? "nothing measurable yet"
            : "start to done, elapsed"
        }
        testId="median-cycle-time"
      />
      <Figure
        label="Oldest in flight"
        hours={insights.wip.oldestAgeHours}
        note={
          insights.wip.oldestAgeHours === undefined
            ? "nothing in flight with a start"
            : "since work started"
        }
        testId="oldest-in-flight"
      />
    </div>
  );
}

function Figure({
  label,
  hours,
  note,
  testId,
}: {
  label: string;
  hours: number | undefined;
  note: string;
  testId: string;
}) {
  return (
    <div className="flex flex-col gap-1.5" data-testid={testId}>
      <GroupLabel className="px-0 pt-0">{label}</GroupLabel>
      {/* An em dash, not a 0, as every other figure on this screen does it: a thing Kanso
          has never measured is not a thing that took no time. */}
      {/* `text-21` and not the pace's `text-30`: these two are the second thing on the
          page, and three figures at one size would make the reader hunt for the headline.
          The scale is 11/12/13/15/21/30 in `tokens.css` and has no step between. */}
      <span className="text-21 font-medium leading-none tracking-[-0.02em]">
        {hours === undefined ? "—" : duration(hours)}
      </span>
      <span className="text-11 text-faint">{note}</span>
    </div>
  );
}

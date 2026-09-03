"use client";

import { GroupLabel } from "@/components/ui/group-label";
import type { DeliveredCycle, OpenLoad } from "@/lib/api";
import { trend } from "@/lib/progress";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import { formatRate } from "@/lib/velocity";
import { heights, LOAD_BAR_ORDER, progressSegments } from "./burndown";

/**
 * The three drawings screens 40 and 41 share, moved here unchanged.
 *
 * Not tidiness. The delivered-points chart carries two decisions KAN-40 named as design
 * traps — a waiting message rather than a lone bar, and a visible stub rather than a missing
 * bar for a cycle somebody delivered nothing in — and both matter *more* when the reader is
 * not the subject: somebody looking at a colleague has no other way to know that the flat
 * chart in front of them is one closed cycle rather than a quiet quarter. A second copy for
 * the admin view is how one of the two copies quietly loses them.
 *
 * The geometry is still `burndown.ts` — the same `heights` the cycle burn-down is drawn
 * with and the same `progressSegments` over the load vocabulary. There is one chart engine
 * in this app and this file did not add a second.
 */
/**
 * Points delivered per closed cycle — or the sentence saying what the chart is waiting for.
 *
 * The refusal to draw a lone bar is `trend`, in `lib/progress.ts`, so it is asserted rather
 * than eyeballed.
 */
export function Delivered({
  delivered,
  paceLabel,
}: {
  delivered: DeliveredCycle[];
  /** `your pace` / `their pace` / `this team's pace` — see [Chart]'s caption. */
  paceLabel: string;
}) {
  const verdict = trend(delivered);

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">Delivered per cycle</GroupLabel>

      {!verdict.drawable ? (
        <p className="m-0 max-w-[620px] text-12 text-muted-foreground" data-testid="trend-waiting">
          {verdict.waiting}
        </p>
      ) : (
        <Chart delivered={delivered} paceLabel={paceLabel} />
      )}
    </section>
  );
}

/**
 * [paceLabel] is the only thing the three callers differ by: a possessive, not a name.
 *
 * The name is already in the heading, and the caption is the second line of prose about the
 * same subject — "the darker 3 are the ones Ana Ruiz's pace is measured over" reads as a
 * different person from the one the heading just named.
 */
function Chart({ delivered, paceLabel }: { delivered: DeliveredCycle[]; paceLabel: string }) {
  // The same scaling the cycle burn-down uses, against the busiest cycle rather than
  // against a total: a rise from 5 to 14 points has to read as a rise.
  const tall = heights(delivered.map((cycle) => cycle.points));
  const total = delivered.reduce((sum, cycle) => sum + cycle.points, 0);
  const measured = delivered.filter((cycle) => cycle.countedTowardsVelocity).length;
  const unestimated = delivered.reduce((sum, cycle) => sum + cycle.unestimated, 0);

  return (
    <>
      {/* Three sibling rows over one flex template, and not one column per cycle holding
          its own label, bar and number. A bar's `height: %` resolves against its flex
          parent's content box, so a column that also held two lines of text gave the tallest
          bars a basis two text lines short of the chart — they hit the clamp together and 21
          points drew the same height as 26. The bug is invisible in a suite and obvious on
          screen, which is exactly the reason `burndown.ts` keeps the arithmetic out here: the
          numbers were right and the box was wrong. So the middle row is the chart and nothing
          else is in it. */}
      <div
        className="flex flex-col gap-1.5"
        role="img"
        aria-label={delivered
          .map((cycle) => `cycle ${cycle.number}: ${formatRate(cycle.points)} points`)
          .join(", ")}
      >
        <div className="flex items-end gap-2">
          {delivered.map((cycle) => (
            <span
              key={cycle.cycleId}
              className="flex-1 text-center font-mono text-11 text-muted-foreground"
            >
              {formatRate(cycle.points)}
            </span>
          ))}
        </div>

        <div className="flex h-[120px] items-end gap-2">
          {delivered.map((cycle, at) => (
            // The column is what the gaps sit between; the bar inside it is capped so a
            // person with two closed cycles gets two bars and not two slabs half the width
            // of the page.
            <div key={cycle.cycleId} className="flex h-full flex-1 items-end justify-center">
              <span
                title={
                  `Cycle ${cycle.number}: ${formatRate(cycle.points)} points over` +
                  ` ${cycle.workingDays} working days` +
                  `${cycle.countedTowardsVelocity ? "" : " (outside the measured window)"}`
                }
                // Two fills, not a hatch. A hatch means "nobody measured this day" on the
                // burn-down next door, and every one of these cycles is measured — the
                // distinction here is whether the *pace above* was averaged over it, which
                // is a weaker statement and gets the quieter of two solid colours.
                className={
                  cycle.countedTowardsVelocity
                    ? "w-full max-w-[64px] rounded-t-[2px] bg-primary"
                    : "w-full max-w-[64px] rounded-t-[2px] bg-primary/30"
                }
                // A cycle somebody delivered nothing in keeps a visible stub rather than
                // vanishing: the gap in the series is part of their own trend, and a
                // missing bar would compress it out and flatter the line.
                style={{ height: `${Math.max(tall[at], 2)}%` }}
              />
            </div>
          ))}
        </div>

        <div className="flex gap-2">
          {delivered.map((cycle) => (
            <span key={cycle.cycleId} className="flex-1 text-center text-11 text-faint">
              {cycle.number}
            </span>
          ))}
        </div>
      </div>

      {/* The caption says what the two fills mean and what the sum cannot see. A chart with
          two colours and no legend is a chart two people read two ways. */}
      <p className="m-0 max-w-[620px] text-11 text-muted-foreground">
        {formatRate(total)} points over {delivered.length} closed cycles
        {measured > 0 && measured < delivered.length
          ? ` · the darker ${measured} are the ones ${paceLabel} is measured over`
          : ""}
        {measured === 0 ? ` · ${paceLabel} is not measured over any of them yet` : ""}
        {unestimated > 0
          ? ` · ${unestimated} delivered ticket${unestimated === 1 ? "" : "s"} carried no estimate`
          : ""}
      </p>
    </>
  );
}

export function StatusBar({ load }: { load: OpenLoad }) {
  // The bar is cut by *rows*, not points: every open ticket has a status and only some have
  // an estimate, so a segmented bar drawn in points would silently omit the unsized ones
  // and stop adding up to the plate the sentence above just counted.
  const counts = Object.fromEntries(
    Object.entries(load.byStatus).map(([status, slice]) => [status, slice.tickets]),
  );
  const segments = progressSegments(counts, load.load.tickets, LOAD_BAR_ORDER);

  return (
    <div className="flex flex-col gap-2">
      <div
        className="flex h-2 overflow-hidden rounded-[4px] bg-accent"
        role="img"
        aria-label={segments
          .map((segment) => `${STATUS_LABELS[segment.status]}: ${segment.count}`)
          .join(", ")}
      >
        {segments.map((segment) => (
          <span
            key={segment.status}
            style={{ width: `${segment.width}%`, background: STATUS_COLORS[segment.status] }}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-3.5 text-11 text-muted-foreground">
        {segments.map((segment) => (
          <span key={segment.status} className="flex items-center gap-1.5">
            <span
              aria-hidden
              className="size-[7px] rounded-sm"
              style={{ background: STATUS_COLORS[segment.status] }}
            />
            {STATUS_LABELS[segment.status]} {segment.count}
            <span className="text-faint">
              {load.byStatus[segment.status]?.points ? ` · ${load.byStatus[segment.status].points} pts` : ""}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}

export function Projects({ load }: { load: OpenLoad }) {
  const heaviest = Math.max(1, ...load.byProject.map((row) => row.load.points));

  return (
    <div className="flex flex-col gap-1.5 pt-1.5">
      <GroupLabel className="px-0 pt-0">By project</GroupLabel>
      {load.byProject.map((row) => (
        <div
          key={row.projectId ?? "unfiled"}
          className="grid grid-cols-[160px_1fr_92px] items-center gap-3 text-12 max-[720px]:grid-cols-[110px_1fr_72px]"
          data-testid="progress-project-row"
        >
          <span className={row.projectId === undefined ? "truncate text-faint" : "truncate"}>
            {row.projectName ?? "No project"}
          </span>
          <div className="flex h-2.5 overflow-hidden rounded-sm bg-accent">
            <span
              // Scaled against the heaviest project rather than the plate's total, for the
              // reason `heights` gives: the question is which of these is the big one.
              style={{ width: `${(row.load.points / heaviest) * 100}%` }}
              className={
                row.projectId === undefined
                  ? // The unfiled pile is hatched, as the workload chart hatches its
                    // unassigned column: it is not a project, and a colour would put it on
                    // the same footing as one.
                    "bg-[repeating-linear-gradient(135deg,var(--rule)_0_3px,transparent_3px_6px)]"
                  : "bg-primary"
              }
            />
          </div>
          {/* The count and the weight, because they are two different claims — and `+2 ?` is
              what the weight could not see, never a zero. */}
          <span className="flex justify-end gap-1.5 font-mono text-11 text-muted-foreground">
            <span>{row.load.tickets}</span>
            <span className="text-faint">
              {row.load.points > 0 ? `${row.load.points} pts` : "—"}
              {row.load.unestimated > 0 ? ` +${row.load.unestimated} ?` : ""}
            </span>
          </span>
        </div>
      ))}
    </div>
  );
}

"use client";

import { GroupLabel } from "@/components/ui/group-label";
import type { DeliveredCycle, OpenLoad, Progress } from "@/lib/api";
import { loadSentence, trend } from "@/lib/progress";
import { useProgress } from "@/lib/queries";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import { formatRate, velocityCaption } from "@/lib/velocity";
import { heights, LOAD_BAR_ORDER, progressSegments } from "./burndown";
import { useOrganiseTeam } from "./team";

/**
 * Screen 40 — the pace half of the page a person opens on themselves.
 *
 * Four things in descending order of usefulness: the pace in force and which of the two
 * numbers it is, what was delivered per closed cycle, what is being carried right now
 * against that pace, and how that load is cut. Nothing on it is comparative and nothing on
 * it is a grade — see the head of `lib/progress.ts` for why that is a design constraint
 * rather than a preference.
 *
 * Every number arrives from `GET /api/me/progress` already derived, in one request, so the
 * chart and the sentence above it cannot be two different moments. The geometry is
 * `burndown.ts` — the same `heights` the cycle burn-down is drawn with, and the same
 * `progressSegments`, over the load vocabulary instead of the cycle one. There is no
 * second chart engine here, which is what the ticket asked for.
 *
 * Median cycle time is deliberately absent. It waits on the insights ticket that reads
 * `activity` for time-in-status, and a plausible-looking stub is worse than a gap: nobody
 * ever goes back and checks a number that is already on the screen.
 *
 * **It draws no chrome of its own, and used to.** It arrived on `main` as a page rendering
 * `OrganiseShell`, with a `Team / My progress` crumb and the reader's own name at the right
 * of the bar. That shell is gone — one shell now, in `app/(app)/layout.tsx` — and this is
 * no longer a page: it is `/me`'s **Progress** tab, so the crumb belongs to `/me` and the
 * name is redundant on a screen that is by definition about the person reading it.
 * `/progress` survives as a redirect, because a link somebody pasted last week is not a
 * thing to break in a refactor.
 *
 * What did *not* change is the arbitration. It still reads `GET /api/me/progress`, still a
 * per-team rate resolved through `useOrganiseTeam` — which is why it is one tab beside four
 * that count across every team, rather than merged into their request. `me/progress-tab.tsx`
 * writes that difference down at more length.
 */
export function ProgressView() {
  const { team } = useOrganiseTeam();
  const progress = useProgress(team?.id);

  return (
    <>
      {progress.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {/* A team with no cycles and a person with nothing assigned still renders the whole
          page — every section below has a branch for its own absence. So the only empty
          state here is the one where the request itself failed. */}
      {progress.error !== null && !progress.isPending && (
        <div className="empty flex-col gap-1">
          <span className="text-13 text-foreground">Your progress is not available</span>
          <span className="text-12 text-faint">
            {team === undefined
              ? "This instance has no team to measure you against yet."
              : "Kanso could not read your figures for this team."}
          </span>
        </div>
      )}

      {progress.data && <Body progress={progress.data} />}
    </>
  );
}

function Body({ progress }: { progress: Progress }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-6">
      <Pace progress={progress} />
      <Delivered delivered={progress.delivered} />
      <Load progress={progress} />
    </div>
  );
}

/**
 * The pace in force, and — the half of the feature that is not a number — which of the two
 * it is.
 *
 * `velocityCaption` is the settings screen's sentence, reused rather than reworded. The
 * arbitration runs once on the server and is put into words once here, so the two screens
 * that show this number cannot explain it differently.
 */
function Pace({ progress }: { progress: Progress }) {
  const caption = velocityCaption(progress.velocity);
  const rate = progress.velocity.perWorkingDay;

  return (
    <section className="flex flex-col gap-3">
      <GroupLabel className="px-0 pt-0">Your pace</GroupLabel>
      <div className="flex items-end gap-2.5">
        {/* An em dash, not a 0. A person Kanso has never measured does not deliver nothing. */}
        <span className="text-30 font-medium leading-none tracking-[-0.02em]">
          {rate === undefined ? "—" : formatRate(rate)}
        </span>
        <span className="pb-1 text-12 text-muted-foreground">
          {rate === undefined ? "no pace yet" : "points per working day"}
        </span>
      </div>
      <p className="m-0 max-w-[620px] text-12 text-muted-foreground">{caption.inForce}</p>
      {/* The losing number, kept beside the winner: watching the two converge, or not, is
          the most useful thing this pair of numbers produces. */}
      {caption.reference !== null && (
        <p className="m-0 max-w-[620px] text-11 text-faint">{caption.reference}</p>
      )}
    </section>
  );
}

/**
 * Points delivered per closed cycle — or the sentence saying what the chart is waiting for.
 *
 * The refusal to draw a lone bar is `trend`, in `lib/progress.ts`, so it is asserted rather
 * than eyeballed.
 */
function Delivered({ delivered }: { delivered: DeliveredCycle[] }) {
  const verdict = trend(delivered);

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">Delivered per cycle</GroupLabel>

      {!verdict.drawable ? (
        <p className="m-0 max-w-[620px] text-12 text-muted-foreground" data-testid="trend-waiting">
          {verdict.waiting}
        </p>
      ) : (
        <Chart delivered={delivered} />
      )}
    </section>
  );
}

function Chart({ delivered }: { delivered: DeliveredCycle[] }) {
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
          ? ` · the darker ${measured} are the ones your pace is measured over`
          : ""}
        {measured === 0 ? " · your pace is not measured over any of them yet" : ""}
        {unestimated > 0
          ? ` · ${unestimated} delivered ticket${unestimated === 1 ? "" : "s"} carried no estimate`
          : ""}
      </p>
    </>
  );
}

/** What is on the plate now, in days, then cut by status and by project. */
function Load({ progress }: { progress: Progress }) {
  const { load } = progress;

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">Carrying now</GroupLabel>
      <p className="m-0 max-w-[620px] text-13" data-testid="load-sentence">
        {loadSentence(load, progress.velocity)}
      </p>
      {load.load.tickets > 0 && (
        <>
          <StatusBar load={load} />
          <Projects load={load} />
        </>
      )}
    </section>
  );
}

function StatusBar({ load }: { load: OpenLoad }) {
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

function Projects({ load }: { load: OpenLoad }) {
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

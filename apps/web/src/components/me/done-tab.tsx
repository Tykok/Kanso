"use client";

import { useState } from "react";
import Link from "next/link";
import { GroupLabel } from "@/components/ui/group-label";
import {
  ticketAddress,
  ticketHref,
  type MyCommitment,
  type MyFinishedTicket,
  type MyStats,
} from "@/lib/api";
import { useMyStats } from "@/lib/queries";
import { formatRate } from "@/lib/velocity";
import { cn } from "@/lib/utils";
import { doneBars, doneTotals, type DoneUnit } from "./done-bars";

/**
 * What I finished: twelve weeks of it, the total, and the part the total cannot weigh.
 *
 * **What a bar here may claim.** `organise/workload-view.tsx` argues this at length about
 * its own bars and the argument transfers whole: a sum that leaves out the unsized half of
 * a plate is worse than a count that never claimed to weigh anything. So the twelve bars
 * are drawn in *rows* by default — every finished ticket has a completion date and only
 * some have an estimate — and the points are the other reading, offered beside them and
 * never instead of them. Whichever is on screen, the caption says how much of the window
 * the points could not speak for.
 *
 * Two figures ride at the head rather than getting tabs of their own:
 *
 *  - **The cycle commitment**, because it is the only number on this screen that can be
 *    read *during* a cycle. A pace is measured over closed cycles by construction, so
 *    somebody three days into a fortnight has nothing else to look at.
 *  - **Estimate hygiene** — how many of my open tickets nobody sized. It is not a count
 *    of work; it is the reason a measured pace understates, so it belongs beside the
 *    points it explains. The spec put it at the head of the pace tab, and it moved here
 *    when that tab became screen 40's host: both figures here are about work in flight,
 *    and the argument for keeping it next to what it explains is unchanged.
 *
 * Nothing here recounts anything. `strip.finishedThisWeek` *is* the last bucket's
 * `finished` — the strip above reads it off the strip, the chart reads it off the buckets,
 * and neither offers a second opinion.
 */

export function DoneTab() {
  const stats = useMyStats();
  const [unit, setUnit] = useState<DoneUnit>("tickets");

  if (stats.isPending) return <div className="px-4 py-12 text-center text-faint">Loading…</div>;

  if (stats.data === undefined) {
    return (
      <div className="empty flex-col gap-1">
        <span className="text-13 text-foreground">Your figures are not available</span>
        <span className="text-12 text-faint">Kanso could not read what you have finished.</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6 px-6 pt-4 pb-6">
      <Head stats={stats.data} />
      <Chart stats={stats.data} unit={unit} onUnit={setUnit} />
      <Recent tickets={stats.data.recentlyFinished} total={doneTotals(stats.data.weeks).finished} />
    </div>
  );
}

/** The commitment rows, then the hygiene sentence. Both about work still in flight. */
function Head({ stats }: { stats: MyStats }) {
  return (
    <section className="flex flex-col gap-3">
      <GroupLabel className="px-0 pt-0">In flight</GroupLabel>

      {/* A list and not a figure. Somebody in two teams gets two rows, and somebody with
          no running cycle gets none rather than a zero — a cycle nobody is in the middle
          of says nothing about them at all. */}
      {stats.commitments.length === 0 ? (
        <p className="m-0 max-w-[620px] text-12 text-muted-foreground">
          No cycle is in progress in the teams you hold work in, so there is no commitment
          to read yet.
        </p>
      ) : (
        <div className="flex flex-col gap-2.5">
          {stats.commitments.map((commitment) => (
            <Commitment key={commitment.cycleId} commitment={commitment} />
          ))}
        </div>
      )}

      <Hygiene open={stats.strip.open} unestimated={stats.openUnestimated} />
    </section>
  );
}

/**
 * One running cycle: how much of what I signed up for I have closed.
 *
 * **The bar is drawn in rows, and the points sit beside it.** Every ticket in a cycle has
 * a status and only some have an estimate, so a bar drawn in points would silently omit
 * the unsized ones and stop adding up to the commitment the numbers beside it just
 * counted — the exact failure `workload-view.tsx`'s docstring refuses. The points are the
 * more interesting number and they are printed, in full, as a ratio the reader can divide
 * for themselves; what they may not do is set the width of something claiming to be the
 * whole commitment.
 *
 * Both halves are split between assignees by the server, which is what makes the ratio
 * about one person: a whole commitment over a halved delivery would describe two.
 */
function Commitment({ commitment }: { commitment: MyCommitment }) {
  const { committed, finished, committedPoints, finishedPoints, unestimated } = commitment;
  const share = committed === 0 ? 0 : (finished / committed) * 100;

  return (
    <div className="flex flex-col gap-1.5" data-testid="me-commitment">
      <div className="flex items-baseline gap-2 text-12">
        <span className="font-mono text-11 text-faint">{commitment.teamKey}</span>
        <span>Cycle {commitment.cycleNumber}</span>
        <span className="text-faint">
          {commitment.startsOn} → {commitment.endsOn}
        </span>
        <span className="flex-1" />
        <span className="font-mono text-11 text-muted-foreground">
          {finished}/{committed}
        </span>
        {/* `+2 ?` is what the points cannot see, and never a zero — which would read as
            "two tickets worth nothing" rather than "two nobody sized". */}
        <span className="font-mono text-11 text-faint">
          {committedPoints > 0
            ? `${formatRate(finishedPoints)}/${formatRate(committedPoints)} pts`
            : "—"}
          {unestimated > 0 ? ` +${unestimated} ?` : ""}
        </span>
      </div>
      <div
        className="flex h-2 overflow-hidden rounded-[4px] bg-accent"
        role="img"
        aria-label={
          `Cycle ${commitment.cycleNumber} in ${commitment.teamName}: ${finished} of ${committed}` +
          ` closed${unestimated > 0 ? `, ${unestimated} of them unsized` : ""}`
        }
      >
        <span style={{ width: `${share}%` }} className="bg-primary" />
      </div>
    </div>
  );
}

/**
 * How much of my open work nobody has sized.
 *
 * Silent when there is none, rather than printing a compliment: "0 of your tickets carry
 * no estimate" is a sentence about the absence of a problem, and this screen's other two
 * unsized figures — a commitment's `+2 ?` and the chart's caption — are drawn the same
 * way. It names the whole so the gap can be sized: "2 of your 3" is a plate the points
 * barely describe, "2 of your 40" is a footnote.
 *
 * Open work only, which is `MyStats.openUnestimated`'s own ruling: a finished ticket
 * nobody sized is reported by the week it closed in, and it is too late to size it.
 */
function Hygiene({ open, unestimated }: { open: number; unestimated: number }) {
  if (unestimated === 0) return null;

  return (
    <p className="m-0 max-w-[620px] text-11 text-muted-foreground" data-testid="me-hygiene">
      {unestimated} of your {open} open tickets carry no estimate, so nothing here can
      weigh them — that is what makes a measured pace understate.
    </p>
  );
}

/** The twelve weeks, and which of the two readings they are drawn in. */
function Chart({
  stats,
  unit,
  onUnit,
}: {
  stats: MyStats;
  unit: DoneUnit;
  onUnit: (unit: DoneUnit) => void;
}) {
  const bars = doneBars(stats.weeks, unit);
  const totals = doneTotals(stats.weeks);
  const weeks = stats.weeks.length;

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <div className="flex flex-wrap items-baseline gap-3">
        <GroupLabel className="px-0 pt-0 pb-0">Finished, by week</GroupLabel>
        <span className="flex-1" />
        <div className="segmented" role="group" aria-label="Reading">
          <button type="button" aria-pressed={unit === "tickets"} onClick={() => onUnit("tickets")}>
            Tickets
          </button>
          <button type="button" aria-pressed={unit === "points"} onClick={() => onUnit("points")}>
            Points
          </button>
        </div>
      </div>

      {/*
        * Three sibling rows over one flex template, and not one column per week holding
        * its own label, bar and number. A bar's `height: %` resolves against its flex
        * parent's content box, so a column that also held two lines of text would give
        * the tallest bars a basis two text lines short of the chart — they would hit the
        * clamp together and eight finished tickets would draw the same height as ten.
        * That bug is invisible in a suite and obvious on screen, which is exactly why
        * `done-bars.ts` keeps the arithmetic out here: the numbers are right and the box
        * would be wrong.
        */}
      <div
        className="flex flex-col gap-1.5"
        role="img"
        aria-label={bars
          .map(
            (bar) =>
              `week ${bar.isoWeek}: ${unit === "points" ? formatRate(bar.value) : bar.value}` +
              `${unit === "points" ? " points" : " finished"}`,
          )
          .join(", ")}
      >
        <div className="flex h-[120px] items-end gap-1.5">
          {bars.map((bar) => (
            <div key={bar.startsOn} className="flex h-full flex-1 items-end justify-center">
              <span
                title={
                  `${bar.startsOn} — W${bar.isoWeek}: ${bar.finished} finished,` +
                  ` ${formatRate(bar.points)} points` +
                  `${bar.unestimated > 0 ? `, ${bar.unestimated} unsized` : ""}` +
                  `${bar.current ? " (this week, still running)" : ""}`
                }
                className={cn(
                  "w-full max-w-[48px] rounded-t-[2px]",
                  // The week in progress in the quieter fill. Not a hatch: a hatch means
                  // "nobody measured this" on the burn-down next door, and this week is
                  // measured — it is simply not over, which is a weaker statement and
                  // gets the weaker mark.
                  bar.current ? "bg-primary/30" : "bg-primary",
                )}
                // A week nothing closed in keeps a visible stub rather than vanishing: the
                // quiet week is part of the series, and a missing bar would compress the
                // twelve into eleven and flatter the line.
                style={{ height: `${Math.max(bar.height, 2)}%` }}
              />
            </div>
          ))}
        </div>

        <div className="flex gap-1.5">
          {bars.map((bar) => (
            <span key={bar.startsOn} className="flex-1 text-center text-11 text-faint">
              {bar.label}
            </span>
          ))}
        </div>
      </div>

      {/* The total, then what the total cannot weigh. A chart with two readings and no
          caption is a chart two people read two ways. */}
      <p className="m-0 max-w-[620px] text-11 text-muted-foreground">
        {totals.finished} finished over {weeks} weeks · {formatRate(totals.points)} points
        {totals.unsizedShare === null
          ? ""
          : totals.unestimated > 0
            ? ` · ${totals.unestimated} of them carried no estimate, so ${Math.round(totals.unsizedShare * 100)}% of what you closed is not in that sum`
            : " · everything you closed was sized"}
      </p>
    </section>
  );
}

/**
 * The head of the same list the bars are drawn from.
 *
 * The cap is the server's and is stated rather than hidden: `weeks` sums to the whole
 * window, so a screen showing twenty rows under a chart of forty can say so without a
 * second field claiming to be a total.
 */
function Recent({ tickets, total }: { tickets: MyFinishedTicket[]; total: number }) {
  if (tickets.length === 0) return null;

  return (
    <section className="flex flex-col gap-2 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">Recently finished</GroupLabel>
      {tickets.map((ticket) => (
        <Link
          key={ticket.id}
          href={ticketHref(ticketAddress(ticket))}
          className="grid grid-cols-[70px_1fr_32px_60px] items-center gap-3 rounded-md px-row-x py-1 text-12 hover:bg-accent"
          data-testid="me-finished-row"
        >
          <span className="font-mono text-11 text-faint">{ticket.identifier ?? "—"}</span>
          <span className="truncate">{ticket.title}</span>
          {/* Blank and never `0`: an unsized ticket has no estimate, and a zero would make
              it read as the smallest work on the list rather than the unmeasured work it is. */}
          <span className="text-right font-mono text-11 text-faint">{ticket.estimate ?? ""}</span>
          {/* The day, sliced off the instant. A completion is a moment, but which day it
              landed on is all this row has room to say. */}
          <span className="text-11 text-faint">{ticket.completedAt.slice(0, 10)}</span>
        </Link>
      ))}
      {total > tickets.length && (
        <p className="m-0 px-row-x pt-1 text-11 text-faint">
          The {tickets.length} most recent of {total} finished in these weeks.
        </p>
      )}
    </section>
  );
}

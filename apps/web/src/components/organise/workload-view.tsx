"use client";

import { useState } from "react";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import type { WorkloadRow } from "@/lib/api";
import { useCycles, useWorkload } from "@/lib/queries";
import { workloadNote } from "./grouping";
import { OrganiseShell, useOrganiseTeam } from "./shell";

/**
 * Screen 23 — open tickets per person, cut by status, counted and weighed.
 *
 * The drawing refused points outright — "la charge se lit au nombre et à l'ancienneté" —
 * and this screen held to it until tickets had an estimate to read. What the refusal was
 * protecting is kept: the bar is still cut by status and still long in proportion to the
 * *heaviest person's count*, so it answers "who is carrying more than whom" rather than
 * pretending to measure hours.
 *
 * The points sit beside the count instead of replacing it, because a sum that leaves out
 * the unsized half of a plate is worse than a count that never claimed to weigh anything.
 * Every row that shows a total in points also shows how many of its tickets are not in it.
 */
const PLOTTED = ["in_progress", "in_review", "todo", "backlog"] as const;

export function WorkloadView() {
  const { team } = useOrganiseTeam();
  const cycles = useCycles(team?.id);
  const [cycleId, setCycleId] = useState<string>();
  const workload = useWorkload(team?.id, cycleId);

  const rows = workload.data?.rows ?? [];
  const heaviest = Math.max(1, ...rows.map((row) => row.total));
  const note = workloadNote(rows);
  const active = cycles.data?.find((cycle) => cycle.state === "active");

  return (
    <OrganiseShell
      breadcrumb={
        <>
          <span>{team?.name ?? "…"}</span>
          <span>/</span>
          <span className="text-muted-foreground">
            Workload{cycleId && active ? ` · cycle ${active.number}` : ""}
          </span>
        </>
      }
      trailing={<span>open only</span>}
    >
      <div className="flex min-h-0 flex-1 flex-col gap-[18px] overflow-y-auto p-5">
        <div className="flex max-w-[560px] flex-col gap-2">
          <p className="m-0 text-12 text-muted-foreground">
            Open tickets per person, cut by status. The points beside each row are the part
            of that load somebody has sized — the rest is counted, not weighed.
          </p>
          {active && (
            <div className="segmented" role="group" aria-label="Scope">
              <button type="button" aria-pressed={cycleId === undefined} onClick={() => setCycleId(undefined)}>
                Everything open
              </button>
              <button type="button" aria-pressed={cycleId === active.id} onClick={() => setCycleId(active.id)}>
                Cycle {active.number}
              </button>
            </div>
          )}
        </div>

        {workload.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

        {!workload.isPending && rows.length === 0 && (
          <div className="empty">Nothing open in this team.</div>
        )}

        <div className="flex flex-col gap-3.5">
          {rows.map((row) => (
            <PersonRow key={row.person?.id ?? "unassigned"} row={row} heaviest={heaviest} />
          ))}
        </div>

        {note && (
          <div className="flex items-center gap-2.5 rounded-lg bg-background p-3 text-12 text-muted-foreground">
            <span
              aria-hidden
              className="size-[7px] shrink-0 rounded-full"
              style={{ background: STATUS_COLORS.in_progress }}
            />
            <span className="flex-1">{note}</span>
          </div>
        )}
      </div>
    </OrganiseShell>
  );
}

function PersonRow({ row, heaviest }: { row: WorkloadRow; heaviest: number }) {
  const name = row.person?.displayName ?? "Unassigned";

  return (
    <div
      className="grid grid-cols-[140px_1fr_82px] items-center gap-3.5 max-[720px]:grid-cols-[110px_1fr_72px]"
      data-testid="workload-row"
    >
      <span className="flex items-center gap-2.5 text-12">
        <Avatar name={row.person?.displayName} />
        <span className="truncate">{name}</span>
      </span>

      <div
        className="flex h-3.5 overflow-hidden rounded-sm bg-accent"
        role="img"
        aria-label={
          `${name}: ${row.total} open, ${row.points} points` +
          `${row.unestimated > 0 ? ` and ${row.unestimated} unestimated` : ""}` +
          `, oldest ${row.oldestOpenDays} days`
        }
      >
        {row.person === undefined
          ? // The unassigned pile is hatched rather than coloured: it is not somebody's load,
            // and giving it a status hue would put it on the same footing as a person's.
            [
              <span
                key="unowned"
                style={{ width: `${(row.total / heaviest) * 100}%` }}
                className="bg-[repeating-linear-gradient(135deg,var(--rule)_0_3px,transparent_3px_6px)]"
              />,
            ]
          : PLOTTED.flatMap((status) => {
              const count = row.byStatus[status] ?? 0;
              return count === 0
                ? []
                : [
                    <span
                      key={status}
                      title={`${STATUS_LABELS[status]}: ${count}`}
                      style={{
                        width: `${(count / heaviest) * 100}%`,
                        background: STATUS_COLORS[status],
                      }}
                    />,
                  ];
            })}
      </div>

      {/* The count, then the points under it. Two lines rather than one number, because
          they are two different claims: `12` is how many things are on this plate and
          `34 pts` is how big the sized part of it is. A row whose points cannot cover its
          whole load says so — `+2 ?` is the tickets the sum could not see, and it is never
          a zero, which would read as "two tickets worth nothing". */}
      <span className="flex flex-col items-end font-mono text-11 leading-tight text-muted-foreground">
        <span>{row.total}</span>
        <span className="text-faint">
          {row.points > 0 ? `${row.points} pts` : "—"}
          {row.unestimated > 0 ? ` +${row.unestimated} ?` : ""}
        </span>
      </span>
    </div>
  );
}

/** Initials in a circle, as the drawing has them. A dashed ring for the unowned pile. */
function Avatar({ name }: { name?: string }) {
  if (name === undefined) {
    return <span aria-hidden className="size-[22px] shrink-0 rounded-full border border-dashed border-border" />;
  }
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
  return (
    <span
      aria-hidden
      className="grid size-[22px] shrink-0 place-items-center rounded-full bg-accent-soft text-[10px] text-accent-ink"
    >
      {initials}
    </span>
  );
}

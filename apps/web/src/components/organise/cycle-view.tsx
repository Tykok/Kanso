"use client";

import Link from "next/link";
import { GroupLabel } from "@/components/ui/group-label";
import { Row } from "@/components/ui/row";
import { PriorityMark } from "@/components/ui/priority-mark";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import type { Cycle, CycleReport, Ticket, TicketStatus } from "@/lib/api";
import { useCycleReport, useCycles, usePlaceInCycle } from "@/lib/queries";
import { bars, progressSegments } from "./burndown";
import { groupTickets } from "./grouping";
import { OrganiseShell, useOrganiseTeam } from "./shell";

/**
 * Screen 19 — the cycle in progress: how far it has got, what is left, and what will not
 * fit.
 *
 * Every number here arrives from `GET /api/cycles/{id}` already derived. Nothing about a
 * rate is stored or recomputed on this side, so the percentage in the header and the list
 * of slipping tickets underneath cannot disagree with each other.
 */
export function CycleView({ number }: { number: string }) {
  const { team } = useOrganiseTeam();
  const cycles = useCycles(team?.id);
  const report = useCycleReport(team?.id, number);

  return (
    <OrganiseShell
      breadcrumb={
        <>
          <span>{team?.name ?? "…"}</span>
          <span>/</span>
          <span className="text-muted-foreground">
            {report.data ? `Cycle ${report.data.cycle.number}` : "Cycle"}
          </span>
        </>
      }
      trailing={report.data ? <span>{dateRange(report.data.cycle)}</span> : undefined}
      aside={<CycleRail cycles={cycles.data ?? []} current={report.data?.cycle.number} />}
    >
      {report.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {/* A team with no cycle in progress is the common case on a fresh instance, not an
          error: `/cycles/current` 404s and this is what that reads as. */}
      {report.error !== null && !report.isPending && (
        <div className="empty flex-col gap-1">
          <span className="text-13 text-foreground">No cycle here yet</span>
          <span className="text-12 text-faint">
            {number === "current"
              ? "This team has no cycle in progress."
              : `This team has no cycle ${number}.`}
          </span>
        </div>
      )}

      {report.data && <CycleBody report={report.data} nextNumber={report.data.cycle.number + 1} cycles={cycles.data ?? []} />}
    </OrganiseShell>
  );
}

function CycleBody({
  report,
  nextNumber,
  cycles,
}: {
  report: CycleReport;
  nextNumber: number;
  cycles: Cycle[];
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
      <div className="grid grid-cols-3 gap-0 px-6 pt-6 pb-5 max-[720px]:grid-cols-1 max-[720px]:gap-6">
        <Progress report={report} />
        <Remaining report={report} />
        <WillSlip report={report} nextNumber={nextNumber} cycles={cycles} />
      </div>
      <StatusGroups tickets={report.tickets} />
    </div>
  );
}

function Progress({ report }: { report: CycleReport }) {
  const segments = progressSegments(report.byStatus, report.total);

  return (
    <section className="flex flex-col gap-3 border-r border-border pr-7 max-[720px]:border-r-0 max-[720px]:pr-0">
      <GroupLabel className="px-0 pt-0">Progress</GroupLabel>
      <div className="flex items-end gap-2.5">
        <span className="text-30 font-medium leading-none tracking-[-0.02em]">{report.percent} %</span>
        <span className="pb-1 text-12 text-muted-foreground">
          {report.done} of {report.total} tickets
        </span>
      </div>

      <div className="flex h-2 overflow-hidden rounded-[4px] bg-accent" role="img" aria-label={`${report.percent} per cent done`}>
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
          </span>
        ))}
      </div>
    </section>
  );
}

function Remaining({ report }: { report: CycleReport }) {
  const chart = bars(report.remaining);
  const open = report.total - report.done;

  return (
    <section className="flex flex-col gap-3 border-r border-border px-7 max-[720px]:border-r-0 max-[720px]:px-0">
      <GroupLabel className="px-0 pt-0">Remaining</GroupLabel>
      <div className="flex h-[78px] items-end gap-[5px]" role="img" aria-label={`${open} tickets open`}>
        {chart.map((bar) => (
          <span
            key={bar.day}
            title={`${bar.day}: ${bar.open} open${bar.projected ? " (projected)" : ""}`}
            className={
              bar.projected
                ? // The drawing hatches the projection rather than tinting it: a hatch reads
                  // as "not measured" in a way a paler solid bar does not.
                  "flex-1 rounded-t-[2px] bg-[repeating-linear-gradient(135deg,var(--rule)_0_3px,transparent_3px_6px)]"
                : "flex-1 rounded-t-[2px] bg-primary"
            }
            style={{ height: `${Math.max(bar.height, 2)}%` }}
          />
        ))}
      </div>
      <span className="text-11 text-muted-foreground">
        {open} tickets open · projection hatched
      </span>
    </section>
  );
}

function WillSlip({
  report,
  nextNumber,
  cycles,
}: {
  report: CycleReport;
  nextNumber: number;
  cycles: Cycle[];
}) {
  const place = usePlaceInCycle();
  const next = cycles.find((cycle) => cycle.number === nextNumber);
  const count = report.slipping.length;

  return (
    <section className="flex flex-col gap-3 pl-7 max-[720px]:pl-0">
      <GroupLabel className="px-0 pt-0">Will slip</GroupLabel>
      {count === 0 ? (
        <p className="m-0 text-12 text-muted-foreground">
          At the current rate everything in this cycle fits in the {report.daysLeft} days left.
        </p>
      ) : (
        <>
          <p className="m-0 text-12 text-muted-foreground">
            At the current rate, {count} {count === 1 ? "ticket does" : "tickets do"} not fit in the{" "}
            {report.daysLeft} days left.
          </p>
          <div className="flex flex-col gap-0.5 text-12">
            {report.slipping.map((ticket) => (
              <div
                key={ticket.id}
                className="flex h-[26px] items-center gap-2.5 rounded-md bg-card px-2"
                data-testid="slipping-row"
              >
                <span className="font-mono text-11 text-faint">{ticket.identifier}</span>
                <span className="truncate text-muted-foreground">{ticket.title}</span>
              </div>
            ))}
          </div>
          {/* Disabled rather than hidden when there is no next cycle: the button is what
              says a slip is actionable, and hiding it would read as "nothing to be done". */}
          <button
            type="button"
            className="button self-start whitespace-nowrap"
            disabled={next === undefined || place.isPending}
            title={next === undefined ? `Cycle ${nextNumber} does not exist yet` : undefined}
            onClick={() =>
              next &&
              place.mutate({ cycleId: next.id, ticketIds: report.slipping.map((t) => t.id) })
            }
          >
            Move to cycle {nextNumber}
          </button>
        </>
      )}
    </section>
  );
}

/** The drawing's lower half: the cycle's tickets under their status headings. */
function StatusGroups({ tickets }: { tickets: Ticket[] }) {
  const groups = groupTickets(tickets, "status");
  if (groups.length === 0) {
    return <div className="empty">Nothing in this cycle yet.</div>;
  }

  return (
    <div className="flex flex-col gap-row px-6 pb-6">
      {groups.map((group) => (
        <div key={group.key}>
          <GroupLabel>
            {group.label} · {group.count}
          </GroupLabel>
          <div className="flex flex-col gap-row">
            {group.tickets.map((ticket) => (
              <Row
                key={ticket.id}
                className="grid grid-cols-[70px_1fr_20px_96px_74px]"
                data-testid="cycle-row"
              >
                <span className="font-mono text-11 text-faint">{ticket.identifier}</span>
                <span className="truncate">{ticket.title}</span>
                <PriorityMark priority={ticket.priority} />
                <span className="truncate text-11 text-muted-foreground">
                  {ticket.priority === "none" ? "" : ticket.priority}
                </span>
                <span className="text-11 text-faint">{daysOpen(ticket)} d open</span>
              </Row>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

/** The rail: every cycle this team has had, in progress first. */
function CycleRail({ cycles, current }: { cycles: Cycle[]; current?: number }) {
  const STATE_COLOR: Record<Cycle["state"], TicketStatus> = {
    active: "in_progress",
    upcoming: "backlog",
    closed: "done",
  };
  const STATE_LABEL: Record<Cycle["state"], string> = {
    active: "in progress",
    upcoming: "upcoming",
    closed: "closed",
  };

  return (
    <>
      <GroupLabel className="pt-0">Cycles</GroupLabel>
      {cycles.length === 0 && <div className="px-1.5 py-1 text-12 text-faint">No cycle yet</div>}
      {cycles.map((cycle) => (
        <Link
          key={cycle.id}
          href={`/cycles/${cycle.number}`}
          data-testid="cycle-rail-row"
          aria-current={cycle.number === current}
          className={
            cycle.number === current
              ? "flex items-center gap-2 rounded-md bg-accent-soft px-1.5 py-[5px] font-medium text-foreground"
              : "flex items-center gap-2 rounded-md px-1.5 py-[5px] text-muted-foreground hover:bg-accent"
          }
        >
          <span
            aria-hidden
            className="size-1.5 shrink-0 rounded-sm"
            style={{ background: STATUS_COLORS[STATE_COLOR[cycle.state]] }}
          />
          <span className="flex-1 truncate">
            {cycle.number} · {STATE_LABEL[cycle.state]}
          </span>
          <span className="font-mono text-11 text-faint">{cycle.ticketCount}</span>
        </Link>
      ))}
    </>
  );
}

/** `4 Aug → 18 Aug`. The dates are plain days, so they are printed, never converted. */
function dateRange(cycle: Cycle): string {
  return `${day(cycle.startsOn)} → ${day(cycle.endsOn)}`;
}

const day = (iso: string) =>
  new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });

const daysOpen = (ticket: Ticket) =>
  Math.max(0, Math.floor((Date.now() - new Date(ticket.createdAt).getTime()) / 86_400_000));

"use client";

import { TimelineBar, type BarEdit } from "./bar";
import type { Row } from "./view";
import type { TimelineDependency } from "@/lib/api";
import type { Zoom } from "@/lib/timeline-geometry";

// This file reads two of the chart's own custom properties rather than a token:
// --tl-names: the width of the sticky name column.
// --tl-chart: the width of the day grid, which changes with the zoom.
// Both are set once, on the scroll container `view.tsx` renders, and cascade down.

/** What a row needs from the view to make the bar in it answer the pointer. */
export type RowControl = {
  /** The cursor, from the store. A project row is never it: projects are not tickets. */
  selectedId?: string;
  onSelect: (ticketId: string) => void;
  /** `ctx.scope.kind !== "all"` — the pointer half of the rule `canPlan` names for the keyboard. */
  canPlan: boolean;
  onDragStart: () => void;
  onDragEnd: (ticketId: string, edit?: BarEdit) => void;
  /** The link handle was pressed on [predecessorId]: an arrow is being drawn out of it. */
  onLinkStart: (predecessorId: string) => void;
  /** Released at a point on the page. The view decides what, if anything, was under it. */
  onLinkEnd: (x: number, y: number) => void;
  onLinkCancel: () => void;
};

/**
 * Whether the bar for a ticket carrying [context]/[editable] may be dragged or linked
 * from, on a chart where [canPlan] says whether planning is possible at all.
 *
 * ANDs all three reasons a bar can be un-movable, because any one of them alone is
 * enough: the whole chart is read-only, the row is drawn for reading only, or the
 * server itself refused the write. Takes the two fields it needs rather than a whole
 * `TimelineTicket`, so a test can build its eight cases without the rest of the shape.
 */
export function canMoveTicket(ticket: { context: boolean; editable: boolean }, canPlan: boolean) {
  return canPlan && !ticket.context && ticket.editable;
}

/**
 * Whether the cursor may land on a ticket carrying [context] at all — strictly looser
 * than [canMoveTicket], since context is the only one of the three reasons that also
 * closes off selection. `page.tsx` builds its cursor list from the scoped *tickets*
 * query, where a context ticket does not exist: selecting one would set `selectedId`
 * and the cursor-keeping effect would bounce straight back to `visible[0]`. A ticket
 * that is merely not editable, or seen from the read-only chart, stays selectable — it
 * is a member of that list, and the keys that act on it are inert for their own reasons.
 */
export function canSelectTicket(ticket: { context: boolean }) {
  return !ticket.context;
}

/** Whether [row] is drawn for reading only — always false for a project, which the
 * scope filter never excludes in the first place. */
export function isContextRow(row: Row) {
  return row.kind === "ticket" && row.ticket.context;
}

/**
 * The label a row prints: a project by its own name, an ordinary ticket by its
 * identifier, and a context ticket with the team it belongs to named first — the one
 * fact on the chart that says whose work this row is drawing.
 */
export function rowLabel(row: Row): string {
  if (row.kind === "project") return row.project.name;
  const { ticket } = row;
  return ticket.context ? `${ticket.teamKey} · ${ticket.identifier}` : ticket.identifier;
}

/**
 * What the ⚠ on a successor's row says, or nothing when its dependencies all hold.
 *
 * Derived from the edges the response already carries rather than from a field of its
 * own: this is a projection of data in hand, not a second copy of a rule.
 */
export function overlapNotice(
  deps: TimelineDependency[],
  ticketId: string,
  nameOf: (id: string) => string | undefined,
): string | undefined {
  const broken = deps.filter(
    (dep) => dep.successorId === ticketId && (dep.overlap || dep.violated),
  );
  if (broken.length === 0) return undefined;
  if (broken.length > 1) return `${broken.length} dependencies not respected`;
  const name = nameOf(broken[0].predecessorId);
  return name ? `starts before ${name} ends` : "starts before a dependency ends";
}

/**
 * One lane of the chart, name cell included — the name is pinned to the left with
 * `position: sticky`, which only works from inside the full-width row: a sticky element
 * can move within its containing block and nowhere else, and a column of its own would
 * be exactly as wide as it is.
 *
 * The lane exists even when the bar does not, so the names and the chart stay in step:
 * a project with nothing scheduled in it keeps its row rather than shifting everything
 * below it up.
 */
export function TimelineRow({
  row,
  deps,
  nameOf,
  origin,
  zoom,
  timezone,
  control,
}: {
  row: Row;
  deps: TimelineDependency[];
  nameOf: (id: string) => string | undefined;
  origin: string;
  zoom: Zoom;
  timezone: string;
  control: RowControl;
}) {
  const name = rowLabel(row);
  const notice = row.kind === "ticket" ? overlapNotice(deps, row.ticket.id, nameOf) : undefined;
  const context = isContextRow(row);

  // The three name-cell looks — project, ordinary ticket, context ticket — never
  // combine on one row, so they are three whole class strings rather than a pile of
  // ternaries that could quietly turn on two conflicting font sizes at once.
  const nameClass =
    row.kind === "project"
      ? "text-12 font-medium text-foreground"
      : context
        ? "pl-[22px] text-11 italic text-faint"
        : "pl-[22px] text-11 text-muted-foreground";

  return (
    <div className="flex h-row border-b border-border" data-kind={row.kind}>
      <div
        title={row.kind === "project" ? row.project.name : row.ticket.title}
        className={`sticky left-0 z-[2] shrink-0 basis-[var(--tl-names)] overflow-hidden text-ellipsis whitespace-nowrap bg-background px-2.5 leading-[calc(var(--row-h)-1px)] ${nameClass}`}
        style={row.kind === "ticket" ? { fontFamily: "var(--font-mono)" } : undefined}
      >
        {notice && (
          <span role="img" aria-label={notice} title={notice} className="mr-1 text-11 text-warning">
            ⚠
          </span>
        )}
        {name}
      </div>
      <div
        className={`group/lane relative shrink-0 basis-[var(--tl-chart)] ${context ? "opacity-55" : ""}`}
      >
        {bar(row, origin, zoom, timezone, control, Boolean(notice))}
      </div>
    </div>
  );
}

function bar(
  row: Row,
  origin: string,
  zoom: Zoom,
  timezone: string,
  control: RowControl,
  violated: boolean,
) {
  if (row.kind === "project") {
    const { project } = row;
    // Either bound alone is enough to draw: `widthOf` floors at one column, so a project
    // known only by its deadline is a square on that day rather than a hairline.
    const start = project.start ?? project.end;
    const end = project.end ?? project.start;
    if (!start || !end) return null;

    // Read off the bounds actually drawn, not off the two fields: a project known only
    // by its end has both of the bar's edges standing on that one bound.
    const derived =
      start.derived && end.derived
        ? "both"
        : start.derived
          ? "start"
          : end.derived
            ? "end"
            : undefined;

    /*
     * No `drag`, so a project bar answers the pointer with nothing at all — not moved,
     * not resized, not selected. Two separate reasons, and either alone is enough:
     *
     * - A derived bound is a consequence. It is the earliest start and the latest end of
     *   the tickets inside, so dragging it would be editing the answer rather than the
     *   work, and the next refetch would put it straight back.
     * - An explicit bound is editable, but not from here. The only write is
     *   `PUT /api/projects/{id}`, which replaces the row wholesale, and `TimelineProject`
     *   carries a name and two bounds — a PUT built from it would silently clear the
     *   project's status, lead and team.
     *
     * `data-derived` still marks *which* edge was deduced, per edge, so the dashed side
     * says which of the two somebody chose. That is the honest half of the distinction
     * this response can support today.
     */
    return (
      <TimelineBar
        name={`${project.name}: project`}
        kind="project"
        state="normal"
        label={project.name}
        start={start}
        end={end}
        origin={origin}
        zoom={zoom}
        timezone={timezone}
        derived={derived}
      />
    );
  }

  const { ticket } = row;
  const start = ticket.start ?? ticket.due;
  const end = ticket.due ?? ticket.start;
  if (!start || !end) return null;

  const bounds = { start: ticket.start !== undefined, end: ticket.due !== undefined };

  // Three reasons a bar cannot be moved, and any one of them is enough. All three end
  // in the same place — no `drag` prop — which is what a project bar has always done.
  const movable = canMoveTicket(ticket, control.canPlan);

  return (
    <TimelineBar
      name={`${ticket.identifier}: ${ticket.title}`}
      ticketId={ticket.id}
      kind="ticket"
      // Late first: a late ticket is critical too, and the worse of the two is what the
      // reader has to be told.
      state={ticket.late ? "late" : ticket.critical ? "critical" : "normal"}
      label={ticket.title}
      start={start}
      end={end}
      origin={origin}
      zoom={zoom}
      timezone={timezone}
      done={ticket.status === "done"}
      status={ticket.status}
      // The same fact the ⚠ badge next to this row's name reports — see the caller
      // in `TimelineRow`, which computes it once via `overlapNotice` for both.
      violated={violated}
      // See `canSelectTicket`: a context row alone is unreachable by the cursor.
      selected={canSelectTicket(ticket) && ticket.id === control.selectedId}
      onSelect={canSelectTicket(ticket) ? () => control.onSelect(ticket.id) : undefined}
      drag={
        movable
          ? {
              // A one-bound ticket has nothing to resize: both of its drawn edges stand
              // on the same date, so a handle would move the bound the other handle
              // also moves.
              handles: { start: bounds.start && bounds.end, end: bounds.start && bounds.end },
              bounds,
              onStart: control.onDragStart,
              onEnd: (edit) => control.onDragEnd(ticket.id, edit),
            }
          : undefined
      }
      /*
       * Every ticket bar can be the *start* of an arrow, scheduled or not — including a
       * milestone and a done one. Whether the other end is a legal successor is the
       * server's answer: a cycle is a 409 naming the chain, and guessing at it here
       * would be a second copy of a rule the API already holds. But a bar that cannot
       * be moved cannot be linked from either — an arrow is a plan too, and the same
       * three reasons apply.
       */
      link={
        movable
          ? {
              onStart: () => control.onLinkStart(ticket.id),
              onEnd: control.onLinkEnd,
              onCancel: control.onLinkCancel,
            }
          : undefined
      }
    />
  );
}

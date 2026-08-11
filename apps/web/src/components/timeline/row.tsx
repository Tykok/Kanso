"use client";

import { TimelineBar, type BarEdit } from "./bar";
import type { Row } from "./view";
import type { TimelineDependency } from "@/lib/api";
import type { Zoom } from "@/lib/timeline-geometry";

/** What a row needs from the view to make the bar in it answer the pointer. */
export type RowControl = {
  /** The cursor, from the store. A project row is never it: projects are not tickets. */
  selectedId?: string;
  onSelect: (ticketId: string) => void;
  onDragStart: () => void;
  onDragEnd: (ticketId: string, edit?: BarEdit) => void;
  /** The link handle was pressed on [predecessorId]: an arrow is being drawn out of it. */
  onLinkStart: (predecessorId: string) => void;
  /** Released at a point on the page. The view decides what, if anything, was under it. */
  onLinkEnd: (x: number, y: number) => void;
  onLinkCancel: () => void;
};

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
  const name = row.kind === "project" ? row.project.name : row.ticket.identifier;
  const notice = row.kind === "ticket" ? overlapNotice(deps, row.ticket.id, nameOf) : undefined;

  return (
    <div className="tl-row" data-kind={row.kind}>
      <div
        className="tl-name"
        title={row.kind === "project" ? row.project.name : row.ticket.title}
      >
        {notice && (
          <span className="tl-warn" role="img" aria-label={notice} title={notice}>
            ⚠
          </span>
        )}
        {name}
      </div>
      <div className="tl-lane">{bar(row, origin, zoom, timezone, control)}</div>
    </div>
  );
}

function bar(row: Row, origin: string, zoom: Zoom, timezone: string, control: RowControl) {
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
      selected={ticket.id === control.selectedId}
      onSelect={() => control.onSelect(ticket.id)}
      drag={{
        // A one-bound ticket has nothing to resize: both of its drawn edges stand on the
        // same date, so a handle would move the bound the other handle also moves.
        handles: { start: bounds.start && bounds.end, end: bounds.start && bounds.end },
        bounds,
        onStart: control.onDragStart,
        onEnd: (edit) => control.onDragEnd(ticket.id, edit),
      }}
      /*
       * Every ticket bar can be the *start* of an arrow, scheduled or not — including a
       * milestone and a done one. Whether the other end is a legal successor is the
       * server's answer: a cycle is a 409 naming the chain, and guessing at it here
       * would be a second copy of a rule the API already holds.
       */
      link={{
        onStart: () => control.onLinkStart(ticket.id),
        onEnd: control.onLinkEnd,
        onCancel: control.onLinkCancel,
      }}
    />
  );
}

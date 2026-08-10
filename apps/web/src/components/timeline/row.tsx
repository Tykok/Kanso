"use client";

import { TimelineBar } from "./bar";
import type { Row } from "./view";
import type { Zoom } from "@/lib/timeline-geometry";

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
  origin,
  zoom,
  timezone,
}: {
  row: Row;
  origin: string;
  zoom: Zoom;
  timezone: string;
}) {
  const name = row.kind === "project" ? row.project.name : row.ticket.identifier;

  return (
    <div className="tl-row" data-kind={row.kind}>
      <div
        className="tl-name"
        title={row.kind === "project" ? row.project.name : row.ticket.title}
      >
        {name}
      </div>
      <div className="tl-lane">{bar(row, origin, zoom, timezone)}</div>
    </div>
  );
}

function bar(row: Row, origin: string, zoom: Zoom, timezone: string) {
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

  return (
    <TimelineBar
      name={`${ticket.identifier}: ${ticket.title}`}
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
    />
  );
}

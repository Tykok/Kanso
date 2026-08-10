"use client";

import { useMemo, type CSSProperties } from "react";
import { TimelineArrows } from "./arrows";
import { TimelineGrid } from "./grid";
import { TimelineRow } from "./row";
import { TimelineTray } from "./tray";
import type { TimelineProject, TimelineTicket } from "@/lib/api";
import { useTimeline } from "@/lib/queries";
import { addDays, dayKey, daysBetween, PX_PER_DAY } from "@/lib/timeline-geometry";
import { useUi } from "@/store/ui";

/**
 * A lane of the chart. Exported because `row.tsx` draws one and, later, the arrow layer
 * has to find the vertical position of a ticket by walking the same order.
 */
export type Row =
  | { kind: "project"; project: TimelineProject }
  | { kind: "ticket"; ticket: TimelineTicket };

/** Project and ticket ids come from different tables, so the kind is part of the key. */
const rowKey = (row: Row) =>
  row.kind === "project" ? `project:${row.project.id}` : `ticket:${row.ticket.id}`;

/** A week of air either side, so the first bar is not flush against the axis. */
const PADDING_DAYS = 7;

const floating = (day: string) => ({ at: `${day}T00:00:00Z`, hasTime: false });

export function TimelineView() {
  const zoom = useUi((state) => state.zoom);
  const timeline = useTimeline(true);

  /**
   * The reader's zone, resolved once. Only a bound that names a moment is converted
   * with it — a floating day is formatted in UTC by `boundLabel`, which is the whole
   * point of that function.
   */
  const timezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone, []);

  const bounds = useMemo(() => {
    const view = timeline.data;
    // Project bounds count too: an explicit deadline outside every ticket's range is
    // exactly the bar that must not be drawn off the end of the grid.
    const days = [
      ...(view?.tickets ?? []).flatMap((ticket) => [dayKey(ticket.start), dayKey(ticket.due)]),
      ...(view?.projects ?? []).flatMap((project) => [dayKey(project.start), dayKey(project.end)]),
    ].filter((day) => day !== "");

    // An empty timeline still needs an axis, so fall back on a window around today.
    const today = new Date().toISOString().slice(0, 10);
    const first = days.length ? days.reduce((a, b) => (a < b ? a : b)) : today;
    const last = days.length ? days.reduce((a, b) => (a > b ? a : b)) : today;
    const origin = dayKey(addDays(floating(first), -PADDING_DAYS));
    const end = dayKey(addDays(floating(last), PADDING_DAYS));
    // Inclusive of both ends: `dayCount` counts columns, and the last day is one.
    return { origin, dayCount: Math.max(daysBetween(origin, end) + 1, 1) };
  }, [timeline.data]);

  /**
   * Rows in reading order: each project once, its tickets under it, and the
   * project-less tickets last under no heading. A project with no scheduled tickets
   * still gets its row — its bar may come from an explicit bound.
   */
  const rows = useMemo<Row[]>(() => {
    const view = timeline.data;
    if (!view) return [];

    const byProject = new Map<string | undefined, TimelineTicket[]>();
    for (const ticket of view.tickets) {
      const key = ticket.projectId;
      byProject.set(key, [...(byProject.get(key) ?? []), ticket]);
    }

    const grouped = view.projects.flatMap((project): Row[] => [
      { kind: "project", project },
      ...(byProject.get(project.id) ?? []).map((ticket): Row => ({ kind: "ticket", ticket })),
    ]);

    // Whatever is left: no project, or a project the response did not carry a row for
    // — an archived one, say. Falling out of the grouping would drop the ticket from
    // the chart entirely, which is a worse answer than an unheaded row.
    const placed = new Set(view.projects.map((project) => project.id));
    const orphans = view.tickets
      .filter((ticket) => ticket.projectId === undefined || !placed.has(ticket.projectId))
      .map((ticket): Row => ({ kind: "ticket", ticket }));

    return [...grouped, ...orphans];
  }, [timeline.data]);

  if (timeline.error) return <div className="empty error">{(timeline.error as Error).message}</div>;
  if (timeline.isPending) return <div className="empty">Loading…</div>;

  // The chart's own width, in pixels, handed to the stylesheet: the axis, the rules
  // layer and every lane are that wide, and the names column is what the rest of the
  // row is. Writing it three times inline would be three places to disagree.
  const chart = { "--tl-chart": `${bounds.dayCount * PX_PER_DAY[zoom]}px` } as CSSProperties;

  return (
    // The tray is a sibling of the scroller, not something inside it: `.tl` scrolls in
    // both directions, and a strip placed within it would slide out of the corner it is
    // meant to sit in. Both are children of `.main`, which is the column that gives the
    // chart the height left over.
    <>
      <TimelineTray items={timeline.data?.unscheduled ?? []} />

      {/*
       * An empty chart is still a chart with a tray above it — and that is the ordinary
       * first load, where nothing has been scheduled and everything is in the tray.
       */}
      {rows.length === 0 ? (
        <div className="empty">Nothing scheduled here yet.</div>
      ) : (
        // One scroll container, not two. The names are pinned with `position: sticky`
        // per row rather than living in a scroller of their own, so the two halves
        // cannot drift apart vertically and no scroll handler has to hold them together.
        <div className="tl">
          <div className="tl-canvas" style={chart}>
            <TimelineGrid origin={bounds.origin} dayCount={bounds.dayCount} zoom={zoom} />
            {rows.map((row) => (
              <TimelineRow
                key={rowKey(row)}
                row={row}
                origin={bounds.origin}
                zoom={zoom}
                timezone={timezone}
              />
            ))}
            {/* Last, so the arrows are painted over the bars they join. */}
            <TimelineArrows
              rows={rows}
              deps={timeline.data?.dependencies ?? []}
              origin={bounds.origin}
              zoom={zoom}
            />
          </div>
        </div>
      )}
    </>
  );
}

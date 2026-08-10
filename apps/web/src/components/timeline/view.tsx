"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { TimelineArrows } from "./arrows";
import { barAt, type BarEdit } from "./bar";
import { TimelineGrid } from "./grid";
import { TimelineRow } from "./row";
import { TimelineTray } from "./tray";
import type {
  KansoInstant,
  TimelineDependency,
  TimelineProject,
  TimelineTicket,
  TimelineView as TimelineData,
} from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import {
  useLinkDependency,
  usePatchTicket,
  useTimeline,
  useUnlinkDependency,
} from "@/lib/queries";
import {
  addDays,
  boundLabel,
  dayKey,
  daysBetween,
  PX_PER_DAY,
  today,
} from "@/lib/timeline-geometry";
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

/**
 * The response the chart is drawn from while a bar is being moved, and the bounds that
 * bar is being drawn with.
 *
 * A refetch that lands mid-drag repositions what is under the cursor: the tickets query
 * polls, a realtime event invalidates the whole timeline on any ticket edit anywhere,
 * and either one arriving between two pointermoves would move the bar the hand is
 * holding. So the response is copied when the drag begins and the copy is what renders
 * until the drag's own answer comes back.
 *
 * The hold outlives the pointer on purpose. Releasing at pointerup would put the bar
 * back where it started for the length of one round trip — the patch is optimistic on
 * the *tickets* cache, and the chart reads the timeline one, which has no optimistic
 * copy to show. So the drop's own bounds are written into the held snapshot and kept
 * there until data newer than the snapshot arrives. If the patch was refused, the
 * refetch answers with the old dates and the bar visibly snaps back, which is the
 * signal that it did not land.
 */
type Hold = {
  data: TimelineData;
  /** `dataUpdatedAt` when the hold began: anything newer than this replaces it. */
  since: number;
  /** Still under the pointer. */
  live: boolean;
  edit?: { ticketId: string } & BarEdit;
};

export function TimelineView({
  /**
   * Where a refusal goes. The page owns the `topbar-error` strip every other failed
   * action reports into — a cycle refused with a 409 naming the chain is not a different
   * kind of news because a mouse caused it, so it reads in the same place.
   */
  reportError,
}: {
  reportError: (message: string | null) => void;
}) {
  const zoom = useUi((state) => state.zoom);
  const selectedId = useUi((state) => state.selectedId);
  const select = useUi((state) => state.select);
  const linking = useUi((state) => state.linking);
  const startLinking = useUi((state) => state.startLinking);
  const stopLinking = useUi((state) => state.stopLinking);
  const timeline = useTimeline(true);
  const { mutate: patchTicket } = usePatchTicket();
  const { mutate: linkDependency } = useLinkDependency();
  const { mutate: unlinkDependency } = useUnlinkDependency();

  const [held, setHeld] = useState<Hold | null>(null);

  /**
   * The column a chip dragged out of the tray would land on, and the day it names.
   * Painted through these rather than rendered: the pointer moves sixty times a second
   * and the chart it is moving over is the one thing that must not be re-rendered while
   * it does.
   */
  const dropLayer = useRef<HTMLDivElement>(null);
  const dropBand = useRef<HTMLDivElement>(null);
  const dropLabel = useRef<HTMLSpanElement>(null);

  // A chart that goes away mid-gesture — the view switched, the scope changed — leaves
  // a rubber band in the store with nothing drawing it and nothing to end it.
  useEffect(() => stopLinking, [stopLinking]);

  /**
   * The reader's zone, resolved once. Only a bound that names a moment is converted
   * with it — a floating day is formatted in UTC by `boundLabel`, which is the whole
   * point of that function.
   */
  const timezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone, []);

  const { data: fetched, dataUpdatedAt, errorUpdatedAt } = timeline;

  const beginDrag = useCallback(() => {
    if (!fetched) return;
    setHeld({ data: fetched, since: dataUpdatedAt, live: true });
  }, [fetched, dataUpdatedAt]);

  const endDrag = useCallback(
    (ticketId: string, edit?: BarEdit) => {
      // Nothing moved a whole column, so there is nothing to post and nothing to hold.
      if (!edit) {
        setHeld(null);
        return;
      }
      // Re-stamped, not kept from pointerdown: a refetch that landed *during* the drag
      // has already advanced `dataUpdatedAt`, and a hold still measuring from the start
      // of the gesture would count that one and expire the instant the pointer came up
      // — putting the bar back where it was for the length of the round trip.
      setHeld((current) =>
        current
          ? { ...current, live: false, since: dataUpdatedAt, edit: { ticketId, ...edit } }
          : current,
      );
      patchTicket({ id: ticketId, ...edit });
    },
    [patchTicket, dataUpdatedAt],
  );

  /*
   * A hold ends at the next answer, whichever it is: `onSettled` invalidates the
   * timeline whether the patch succeeded or was refused, and a refetch that itself fails
   * still stamps `errorUpdatedAt` — so a hold cannot outlive the round trip that ends
   * it.
   *
   * Read here rather than cleared from an effect. Expiry is a fact about two numbers
   * already in hand, so deriving it needs no second render, and an effect that called
   * `setHeld(null)` would schedule one on every refetch for the length of a session.
   * The expired snapshot stays in state, unread, until the next drag replaces it.
   */
  const hold =
    held && !held.live && (dataUpdatedAt > held.since || errorUpdatedAt > held.since)
      ? null
      : held;

  /** What is drawn: the live response, or the held one with the drop written into it. */
  const view = useMemo(() => {
    if (!hold) return fetched;
    const { data, edit } = hold;
    if (!edit) return data;
    return {
      ...data,
      tickets: data.tickets.map((ticket) =>
        ticket.id === edit.ticketId
          ? {
              ...ticket,
              // Spread each bound only if the drag sent it: a milestone resized by its
              // one bound must not acquire the other here either.
              ...(edit.start ? { start: edit.start } : {}),
              ...(edit.due ? { due: edit.due } : {}),
            }
          : ticket,
      ),
    };
  }, [hold, fetched]);

  const bounds = useMemo(() => {
    // Project bounds count too: an explicit deadline outside every ticket's range is
    // exactly the bar that must not be drawn off the end of the grid.
    const days = [
      ...(view?.tickets ?? []).flatMap((ticket) => [dayKey(ticket.start), dayKey(ticket.due)]),
      ...(view?.projects ?? []).flatMap((project) => [dayKey(project.start), dayKey(project.end)]),
    ].filter((day) => day !== "");

    // An empty timeline still needs an axis, so fall back on a window around today —
    // the reader's own civil day, the same one the marker stands on.
    const now = dayKey(today());
    const first = days.length ? days.reduce((a, b) => (a < b ? a : b)) : now;
    const last = days.length ? days.reduce((a, b) => (a > b ? a : b)) : now;
    const origin = dayKey(addDays(floating(first), -PADDING_DAYS));
    const end = dayKey(addDays(floating(last), PADDING_DAYS));
    // Inclusive of both ends: `dayCount` counts columns, and the last day is one.
    return { origin, dayCount: Math.max(daysBetween(origin, end) + 1, 1) };
  }, [view]);

  /**
   * Rows in reading order: each project once, its tickets under it, and the
   * project-less tickets last under no heading. A project with no scheduled tickets
   * still gets its row — its bar may come from an explicit bound.
   */
  const rows = useMemo<Row[]>(() => {
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
  }, [view]);

  /**
   * The day under a point on the page, or nothing if that point is not over the grid.
   *
   * Measured off the drop layer, which is laid over the chart exactly as the rules are —
   * same origin, same width — rather than found by hit-testing the DOM: the pointer is
   * as often over a bar or an arrow as over bare grid, and the answer must be the same
   * day in all three cases. The rect is a viewport rect, so a scrolled chart needs no
   * correction.
   *
   * `Math.floor`, not the geometry module's `instantAtX`: that rounds to the nearest
   * column boundary, which is the right answer for an edge being dragged and the wrong
   * one for a drop, where the day wanted is the column the pointer is standing *in*.
   */
  const dayUnder = useCallback(
    (x: number, y: number): { column: number; day: KansoInstant } | null => {
      const box = dropLayer.current?.getBoundingClientRect();
      if (!box || x < box.left || x >= box.right || y < box.top || y >= box.bottom) return null;
      const column = Math.floor((x - box.left) / PX_PER_DAY[zoom]);
      return { column, day: addDays(floating(bounds.origin), column) };
    },
    [zoom, bounds.origin],
  );

  /** Shows which column a drop would land on, and says which day that is. */
  const paintDrop = useCallback(
    (at: { column: number; day: KansoInstant } | null) => {
      const band = dropBand.current;
      if (!band) return;
      if (!at) {
        delete band.dataset.active;
        return;
      }
      band.dataset.active = "";
      band.style.transform = `translateX(${at.column * PX_PER_DAY[zoom]}px)`;
      band.style.width = `${PX_PER_DAY[zoom]}px`;
      // The column is three pixels wide at month zoom, so the day is written out as
      // well: a band alone would say "somewhere around here" on the zoom where that is
      // exactly the doubt.
      if (dropLabel.current) dropLabel.current.textContent = boundLabel(at.day, timezone);
    },
    [zoom, timezone],
  );

  /**
   * The end of a rubber band. A bar under the pointer is a successor and the arrow is
   * posted; anything else — bare grid, the tray, the ticket's own bar, the window
   * frame — is a cancelled gesture that writes nothing and says nothing.
   */
  const endLink = useCallback(
    (x: number, y: number) => {
      const predecessorId = linking?.fromId;
      stopLinking();
      if (!predecessorId) return;

      const hit = barAt(x, y);
      if (!hit || hit.id === predecessorId) return;

      linkDependency(
        { successorId: hit.id, predecessorId },
        {
          // A cycle is a 409 naming the chain, and a ticket in another team is a 403.
          // Both belong in the strip the keyboard's own `d` reports into.
          onError: (error) => reportError(actionErrorMessage(error)),
          onSuccess: () => reportError(null),
        },
      );
    },
    [linking, stopLinking, linkDependency, reportError],
  );

  /**
   * Erasing one. Nothing on the chart moves afterwards beyond the line going away: the
   * API does not pull work backwards when slack is freed, so a bar sliding left here
   * would be the browser inventing a schedule the server never agreed to.
   */
  const erase = useCallback(
    (dep: TimelineDependency) =>
      unlinkDependency(
        { successorId: dep.successorId, predecessorId: dep.predecessorId },
        {
          onError: (error) => reportError(actionErrorMessage(error)),
          onSuccess: () => reportError(null),
        },
      ),
    [unlinkDependency, reportError],
  );

  const control = useMemo(
    () => ({
      selectedId,
      onSelect: select,
      onDragStart: beginDrag,
      onDragEnd: endDrag,
      onLinkStart: startLinking,
      onLinkEnd: endLink,
      onLinkCancel: stopLinking,
    }),
    [selectedId, select, beginDrag, endDrag, startLinking, endLink, stopLinking],
  );

  /**
   * Dropping a chip schedules a one-day milestone on the day it landed on. Any other
   * length would be a guess presented as a plan — the tray says a ticket has no dates,
   * not that anyone knows how long it will take.
   *
   * Nothing optimistic: the chart draws the timeline response, and this ticket is not in
   * it yet — it is in `unscheduled`, with no slack and no criticality of its own. Moving
   * it across by hand would mean inventing both. The refetch `onSettled` triggers is
   * what moves the chip onto the grid, and a refusal leaves it in the tray, which is the
   * same signal a bar snapping back gives.
   */
  const trayControl = useMemo(
    () => ({
      selectedId,
      onSelect: select,
      onDragMove: (x: number, y: number) => paintDrop(dayUnder(x, y)),
      onDrop: (ticketId: string, x: number, y: number) => {
        const at = dayUnder(x, y);
        paintDrop(null);
        if (!at) return;
        patchTicket({ id: ticketId, start: at.day, due: at.day });
      },
      onDragEnd: () => paintDrop(null),
    }),
    [selectedId, select, dayUnder, paintDrop, patchTicket],
  );

  // `&& !view`: once there is something to draw, a background refetch that fails must
  // not replace the chart with a sentence — least of all mid-drag, which would unmount
  // the bar the hand is holding.
  if (timeline.error && !view) {
    return <div className="empty error">{(timeline.error as Error).message}</div>;
  }
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
      <TimelineTray items={view?.unscheduled ?? []} control={trayControl} />

      {/*
       * One scroll container, not two. The names are pinned with `position: sticky` per
       * row rather than living in a scroller of their own, so the two halves cannot
       * drift apart vertically and no scroll handler has to hold them together.
       */}
      <div className="tl" data-linking={linking ? "" : undefined}>
        <div className="tl-canvas" style={chart}>
          <TimelineGrid origin={bounds.origin} dayCount={bounds.dayCount} zoom={zoom} />

          {/*
           * An empty chart still draws its calendar, rather than being replaced by a
           * sentence. That is the ordinary first load — everything in the tray, nothing
           * planned — and it is exactly when a chip has to have somewhere to be dropped;
           * a message where the days should be would leave the tray unemptiable by hand
           * on the one screen that is all tray.
           */}
          {rows.length === 0 && (
            <div className="tl-blank">Nothing scheduled here yet — drag a ticket onto a day.</div>
          )}

          {rows.map((row) => (
            <TimelineRow
              key={rowKey(row)}
              row={row}
              origin={bounds.origin}
              zoom={zoom}
              timezone={timezone}
              control={control}
            />
          ))}

          <TimelineArrows
            rows={rows}
            deps={view?.dependencies ?? []}
            origin={bounds.origin}
            zoom={zoom}
            linking={linking}
            onErase={erase}
          />

          {/*
           * The drop layer is the chart's coordinate system made into an element: the
           * same top, left and width as the rules, which is what lets a point on the
           * page be turned into a day by arithmetic instead of by hit-testing. It is
           * therefore rendered whether or not anything is being dragged — `dayUnder`
           * measures it, and a layer that only existed during a gesture would have to be
           * measured after the gesture had already started.
           */}
          <div className="tl-drop-layer" ref={dropLayer} aria-hidden="true">
            <div className="tl-drop" ref={dropBand}>
              <span className="tl-drop-day" ref={dropLabel} />
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

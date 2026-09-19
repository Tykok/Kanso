"use client";

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import type { TimelineSort } from "@/lib/api";
import { TimelineArrows } from "./arrows";
import { barAt, type BarEdit } from "./bar";
import { TimelineGrid } from "./grid";
import { laneOf, TimelineRow } from "./row";
import { buildRows, rowKey, type Row } from "./rows";
import type {
  KansoInstant,
  TimelineDependency,
  TimelineProject,
  TimelineTicket,
  TimelineView as TimelineData,
} from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { useRowMetrics } from "@/lib/row-metrics";
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
  todayInView,
  scrollToToday,
  xOf,
} from "@/lib/timeline-geometry";
import { useUi } from "@/store/ui";

/**
 * Re-exported so the three files that draw a lane keep importing it from here, which is
 * where it lived before the grouping rule moved into its own tested module.
 */
export type { Row } from "./rows";

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
  const timelineSort = useUi((state) => state.timelineSort);
  const setTimelineSort = useUi((state) => state.setTimelineSort);
  const hideCompleted = useUi((state) => state.hideCompleted);
  const setHideCompleted = useUi((state) => state.setHideCompleted);
  const scope = useUi((state) => state.scope);
  // The pointer half of `canPlan` in `actions.ts`: the same rule, so the mouse and the
  // keyboard agree about which chart is read-only without the two importing from
  // each other.
  const canPlan = scope.kind !== "all";
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
   * Rows in reading order. The rule itself lives in `rows.ts` and is tested there: it is a
   * decision about what somebody reads, so it belongs somewhere a table of inputs can
   * assert it rather than inside a component that has to be rendered to ask.
   */
  const rows = useMemo<Row[]>(() => buildRows(view), [view]);

  /**
   * The chart's scroller and the block of lanes inside it.
   *
   * `--row-h` is read off the scroller by the same hook the arrow layer uses, so the
   * height a lane is positioned at and the height an arrow is drawn into are one number.
   */
  const scroller = useRef<HTMLDivElement>(null);
  const lanes = useRef<HTMLDivElement>(null);
  const { height: laneHeight } = useRowMetrics(scroller);

  /**
   * Enough of the scroll position to answer one question: is today's rule on screen.
   *
   * Tracked in state rather than read on click, because the answer is what greys the button
   * out — a control that only discovered it had nowhere to go once pressed would be a
   * control that lies until you use it.
   */
  const [window_, setWindow] = useState({ left: 0, width: 0 });
  useEffect(() => {
    const node = scroller.current;
    if (!node) return;
    const read = () => setWindow({ left: node.scrollLeft, width: node.clientWidth });
    read();
    node.addEventListener("scroll", read, { passive: true });
    return () => node.removeEventListener("scroll", read);
  }, [scroller]);

  /**
   * How far below the top of the chart's content the first lane sits: the axis strip,
   * which is in the flow above them. Measured rather than read back out of `--tl-axis`,
   * because the number the virtualiser needs is where the element actually is.
   */
  const [laneMargin, setLaneMargin] = useState(0);
  // The rule offers `[]`, which would measure once at mount. The chart renders "Loading…"
  // until the query answers, so at mount there are no lanes to measure and the margin
  // would stay zero for good. The updater compares before it writes, so a render that
  // measures the same top schedules nothing.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    const top = lanes.current?.offsetTop ?? 0;
    setLaneMargin((current) => (current === top ? current : top));
  });

  const laneVirtualizer = useVirtualizer({
    // Nothing until `--row-h` is known: a lane of zero height has no place on a chart
    // whose whole geometry is `lane × --row-h`.
    count: laneHeight > 0 ? rows.length : 0,
    getScrollElement: () => scroller.current,
    estimateSize: () => laneHeight,
    scrollMargin: laneMargin,
    // Generous, because the arrows between two lanes are drawn whether or not either is
    // mounted, but the *bars* they point at are not: a wider margin means fewer moments
    // where an arrow's own endpoints have scrolled out from under it.
    overscan: 12,
    getItemKey: (index) => (rows[index] ? rowKey(rows[index]) : index),
  });

  /**
   * The cursor, kept on screen. A lane below the fold has no element, so the bar cannot
   * be scrolled to by looking it up — `laneOf` answers for every lane on the chart, drawn
   * or not, and counts the project headings between them exactly as the arrow layer does.
   */
  const lane = laneOf(rows, selectedId);
  useEffect(() => {
    if (lane >= 0) laneVirtualizer.scrollToIndex(lane, { align: "auto" });
  }, [lane, laneVirtualizer]);

  /** A ticket's identifier by id, for the sentence the ⚠ prints. */
  const nameOf = useMemo(() => {
    const byId = new Map((view?.tickets ?? []).map((ticket) => [ticket.id, ticket.identifier]));
    return (id: string) => byId.get(id);
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
      canPlan,
      onDragStart: beginDrag,
      onDragEnd: endDrag,
      onLinkStart: startLinking,
      onLinkEnd: endLink,
      onLinkCancel: stopLinking,
    }),
    [selectedId, select, canPlan, beginDrag, endDrag, startLinking, endLink, stopLinking],
  );

  /**
   * Dropping an undated row schedules a one-day milestone on the day it landed on. Any
   * other length would be a guess presented as a plan — the row says a ticket has no
   * dates, not that anyone knows how long it will take.
   *
   * Nothing optimistic: the chart draws the timeline response, and this ticket has no
   * slack and no criticality of its own until the server gives it dates. Moving it across
   * by hand would mean inventing both. The refetch `onSettled` triggers is what draws the
   * bar, and a refusal leaves the row undated — the same signal a bar snapping back gives.
   */
  /**
   * What the tray used to carry, kept whole and handed to the rows instead.
   *
   * The guard is still here as well as on the row: a drag begun inside a scope stays a drag
   * if the scope changes under the pointer, and the band must stop painting the instant it
   * does — which the row cannot see.
   */
  const planControl = useMemo(
    () => ({
      // `tray.tsx` already refuses to let a press become a drag once `canPlan` is
      // false, so this fires day to day only inside a scope. It is still guarded here
      // too, for the one gesture that can straddle the boundary: a drag begun inside a
      // scope stays a drag if the scope changes under the pointer, and the band must
      // stop painting the instant it does.
      onPlanDragMove: (x: number, y: number) => paintDrop(canPlan ? dayUnder(x, y) : null),
      onPlanDrop: (ticketId: string, x: number, y: number) => {
        const at = dayUnder(x, y);
        paintDrop(null);
        // The same three-reasons rule the bars answer to: scheduling an undated row is a
        // plan too, and the global chart is read-only for it exactly as it is for a bar.
        if (!canPlan || !at) return;
        patchTicket({ id: ticketId, start: at.day, due: at.day });
      },
    }),
    [canPlan, dayUnder, paintDrop, patchTicket],
  );

  /**
   * One object, memoised once. Spreading the two at the render site would build a new
   * `control` on every frame and defeat the memo each of them exists for.
   */
  const rowControl = useMemo(() => ({ ...control, ...planControl }), [control, planControl]);

  // `&& !view`: once there is something to draw, a background refetch that fails must
  // not replace the chart with a sentence — least of all mid-drag, which would unmount
  // the bar the hand is holding.
  if (timeline.error && !view) {
    return <div className="px-4 py-12 text-center text-urgent">{(timeline.error as Error).message}</div>;
  }
  if (timeline.isPending) {
    return <div className="px-4 py-12 text-center text-faint">Loading…</div>;
  }

  // The chart's own width, in pixels, handed to the stylesheet: the axis, the rules
  // layer and every lane are that wide, and the names column is what the rest of the
  // row is. Writing it three times inline would be three places to disagree.
  //
  // Three custom properties, not tokens — no default lives in tokens.css, because no
  // surface but this one has a use for them:
  //   --tl-names: the width of the sticky name column.
  //   --tl-axis: the height of the axis strip above the grid.
  //   --tl-chart: the one value that changes every render, from the zoom and the day
  //   count. Every descendant that positions itself off the chart's geometry — the
  //   grid's axis and rules, each row's sticky name cell, the drop layer, the arrow
  //   layer — reads its own copy of all three straight off this element, through
  //   `var()`, so there is exactly one place to change the width of the name column
  //   or the height of the axis.
  const chart = {
    "--tl-names": "200px",
    "--tl-axis": "26px",
    "--tl-chart": `${bounds.dayCount * PX_PER_DAY[zoom]}px`,
  } as CSSProperties;

  return (
    // The tray is a sibling of the scroller, not something inside it: the chart
    // scrolls in both directions, and a strip placed within it would slide out of the
    // corner it is meant to sit in. Both are children of `.main`, which is the column
    // that gives the chart the height left over.
    <>


      {/*
       * A cap was hit server-side: the drawing is missing bars and has no next page to
       * offer instead of them. Outside the scroller, like the tray above it, so the
       * warning does not scroll away with the chart it is about.
       */}
      {/*
        * The column's own two controls, above the scroller so they do not scroll away from
        * the list they govern — the same reason the warning below them sits here.
        */}
      <div className="flex items-center gap-3 px-2 py-1 text-11 text-muted-foreground">
        <label className="flex items-center gap-1.5">
          <span className="text-faint">Sort</span>
          <select
            aria-label="Timeline sort"
            className="rounded-md border border-border bg-card px-1.5 py-0.5 text-11"
            value={timelineSort}
            onChange={(event) => setTimelineSort(event.target.value as TimelineSort)}
          >
            <option value="start">Start date</option>
            <option value="priority">Priority</option>
            <option value="due">Due date</option>
          </select>
        </label>
        <label className="flex items-center gap-1.5">
          <input
            type="checkbox"
            aria-label="Hide completed"
            checked={hideCompleted}
            onChange={(event) => setHideCompleted(event.target.checked)}
          />
          <span>Hide completed</span>
        </label>
        {/*
          * Said where the reader is, not in a toast: another page exists and scrolling is
          * what fetches it. Distinct from the warning below, which means bars are missing
          * and no scroll will bring them.
          */}
        {(() => {
          const todayX = xOf(today(), bounds.origin, zoom);
          const here = todayInView(todayX, window_.left, window_.width, zoom);
          return (
            <button
              type="button"
              // Disabled and not hidden: a control that vanishes when satisfied is one
              // people stop looking for, and the moment you reach for it is the moment you
              // cannot see the thing it points at.
              disabled={here}
              className="rounded-md border border-border px-1.5 py-0.5 text-11 disabled:opacity-40"
              onClick={() =>
                scroller.current?.scrollTo({
                  left: scrollToToday(todayX, window_.width),
                  behavior: "smooth",
                })
              }
            >
              Today
            </button>
          );
        })()}
        {view?.hasMore && <span className="text-faint">More rows load as you scroll</span>}
      </div>

      {view?.truncated && (
        <div className="px-2 py-1 text-11 text-warning" role="status">
          This view hit its limit — some bars are not drawn. Narrow the scope to see them all.
        </div>
      )}

      {/*
       * One scroll container, not two. The names are pinned with `position: sticky` per
       * row rather than living in a scroller of their own, so the two halves cannot
       * drift apart vertically and no scroll handler has to hold them together.
       *
       * `group` carries `data-linking` down to every bar: while a dependency is being
       * drawn, the whole chart reads as a drop target, not only the lane under the
       * pointer.
       */}
      <div
        ref={scroller}
        className="group/chart min-h-0 flex-1 overflow-auto"
        data-linking={linking ? "" : undefined}
        style={chart}
      >
        <div
          className="relative min-w-full"
          style={{ width: "calc(var(--tl-names) + var(--tl-chart))" }}
        >
          <TimelineGrid origin={bounds.origin} dayCount={bounds.dayCount} zoom={zoom} />

          {/*
           * An empty chart still draws its calendar, rather than being replaced by a
           * sentence — and the condition now means something different from what it used
           * to. With the tray gone, no rows means **the scope is empty**, not that nobody
           * has scheduled anything: an undated ticket is a row like any other. So the
           * sentence says what is actually true, and the calendar stays under it because
           * an empty timeline is still where you would drop the first thing.
           *
           * `min-h` rather than a fixed 120px: the chart should fill the space it was
           * given whether or not it has bars, which is the whole of what a reader means by
           * "the timeline does not take the screen unless there are tickets".
           */}
          {rows.length === 0 && (
            <div className="pointer-events-none flex min-h-[240px] items-center justify-center text-12 text-muted-foreground">
              Nothing here yet — create a ticket, or widen the scope.
            </div>
          )}

          {/*
           * The lanes, virtualised — and the arrows above them left exactly as they were.
           *
           * That is only safe because of how `arrows.tsx` already worked: it takes the
           * whole `rows` array and turns a ticket's *position in it* into a y coordinate,
           * in one SVG laid over the entire chart. It never asks the DOM where a bar is.
           * So an arrow to a lane nobody has mounted is drawn at exactly the same place
           * it always was, and nothing about an unmounted row can make a line vanish or
           * point at nothing — which is the one outcome that would have made virtualising
           * this chart worse than leaving it alone.
           *
           * What that costs is a rule: a lane is at `index × --row-h` and nowhere else.
           * The arrow layer computes `lane × --row-h` from the same hook, and the spacer
           * below is `rows.length × --row-h` tall, so the chart is the same height it
           * would be with every lane in the document. `measureElement` is deliberately
           * not used — a measured lane one pixel off the arithmetic is an arrow one pixel
           * off its bar, and every lane here is `h-row` by construction anyway.
           */}
          <div
            ref={lanes}
            className="relative w-full"
            style={{ height: laneHeight > 0 ? rows.length * laneHeight : undefined }}
          >
            {laneVirtualizer.getVirtualItems().map((item) => (
              <div
                key={item.key}
                className="absolute left-0 top-0 w-full"
                style={{ transform: `translateY(${item.start - laneMargin}px)` }}
              >
                {/*
                  * A team heading spans the column and draws nothing against the time axis,
                  * so it is rendered here rather than handed to `TimelineRow` — which takes
                  * `LaneRow` precisely so that the case it cannot draw is unrepresentable.
                  * It appears only on a scope spanning more than one team; `rows.ts` says why.
                  */}
                {rows[item.index].kind === "team" ? (
                  <div
                    className="flex h-full items-center px-2 text-11 font-medium text-faint"
                    style={{ width: "var(--tl-names)" }}
                  >
                    {(rows[item.index] as { kind: "team"; teamKey: string }).teamKey}
                  </div>
                ) : (
                  <TimelineRow
                    row={rows[item.index] as Exclude<Row, { kind: "team" }>}
                    deps={view?.dependencies ?? []}
                    nameOf={nameOf}
                    origin={bounds.origin}
                    zoom={zoom}
                    timezone={timezone}
                    control={rowControl}
                  />
                )}
              </div>
            ))}
          </div>

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
          <div
            ref={dropLayer}
            className="pointer-events-none absolute bottom-0 left-[var(--tl-names)] top-[var(--tl-axis)] w-[var(--tl-chart)]"
            aria-hidden="true"
          >
            <div
              ref={dropBand}
              className="absolute top-0 bottom-0 hidden border-l-2 border-primary bg-primary/20 data-[active]:block"
            >
              <span
                ref={dropLabel}
                className="absolute left-1 top-0.5 whitespace-nowrap rounded-sm bg-primary px-1 text-11 text-primary-foreground tabular-nums"
              />
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

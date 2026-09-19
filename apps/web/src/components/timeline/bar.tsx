"use client";

import { colourOfKey } from "@/lib/statuses";
import { useRef, type PointerEvent as ReactPointerEvent } from "react";
import type { KansoInstant, TicketStatus } from "@/lib/api";
import { STATUS_COLORS } from "@/lib/status";
import {
  barAccessibleName,
  derivedBorderClass,
  SLIPPING_STYLE,
  SLACK_STYLE,
  slackTitle,
  slackWidthPx,
  ticketTint,
  type BarState,
} from "./bar-style";
import {
  boundLabel,
  dayKey,
  daysBetween,
  laterBy,
  PX_PER_DAY,
  snapDays,
  widthOf,
  xOf,
  type Zoom,
} from "@/lib/timeline-geometry";

export type { BarState };

/**
 * What a finished gesture asks the API for: only the bounds the ticket already carried,
 * and only the ones this gesture moved. A milestone dragged sideways sends its one
 * bound; sending both would give it a start it never had, and the API deliberately
 * keeps the shape it was given.
 */
export type BarEdit = { start?: KansoInstant; due?: KansoInstant };

/** Which of the bar's two edges may be taken hold of and moved on its own. */
export type BarHandles = { start: boolean; end: boolean };

/**
 * Drawing an arrow out of this bar. Three callbacks rather than one, because the
 * gesture has three endings and only one of them writes anything.
 */
export type BarLink = {
  /** The handle was pressed: the rubber band starts here. */
  onStart: () => void;
  /** Released, wherever that was. The view decides whether a bar was under it. */
  onEnd: (x: number, y: number) => void;
  /** The system took the pointer away. Nothing is drawn and nothing is posted. */
  onCancel: () => void;
};

/**
 * The ticket whose bar lies under a point, if any.
 *
 * Lives here because this file is what writes `data-ticket-id` and `.tl-bar` — the two
 * facts the lookup depends on — and it is read by both gestures that end on a bar: the
 * rubber band's drop, and the highlight that says which bar it would land on.
 *
 * `elementsFromPoint`, not `elementFromPoint`: the topmost element over a bar may be an
 * arrow's path, which answers the pointer and is not in the bar's ancestry, so the
 * singular call would report "nothing here" over a perfectly good drop target.
 */
export function barAt(x: number, y: number): { id: string; element: HTMLElement } | undefined {
  for (const hit of document.elementsFromPoint(x, y)) {
    const bar = hit.closest<HTMLElement>(".tl-bar");
    const id = bar?.dataset.ticketId;
    // A project bar carries no id: it is not a ticket, so nothing can depend on it.
    if (bar && id) return { id, element: bar };
  }
  return undefined;
}

type BarDrag = {
  handles: BarHandles;
  /**
   * The bounds the ticket actually has. Read separately from `start`/`end`, which are
   * what is *drawn*: a ticket known only by its due date is drawn as a square standing
   * on that day, with the same bound at both edges.
   */
  bounds: { start: boolean; end: boolean };
  /** The pointer has passed the threshold: this is a drag, not a click. */
  onStart: () => void;
  /** Always called once a drag ends. `undefined` when it moved less than one column. */
  onEnd: (edit?: BarEdit) => void;
};

type BarProps = {
  /**
   * The accessible name, and the only handle anything outside this component has on a
   * bar. Bars carry no test hooks and no meaningful classes: `role="button"` plus this
   * is what the end-to-end suite queries, so a bar without one is a bar nothing can
   * reach.
   */
  name: string;
  /**
   * The ticket this bar draws, written onto the element as `data-ticket-id` so a
   * pointer landing on it can be turned back into an id. Absent on a project bar, which
   * is what makes a project bar an invalid end for an arrow rather than a special case
   * spelled out in every gesture.
   */
  ticketId?: string;
  kind: "ticket" | "project";
  state: BarState;
  /** The bar's own text. Kept off the accessible name, which already carries it. */
  label: string;
  start: KansoInstant;
  end: KansoInstant;
  origin: string;
  zoom: Zoom;
  timezone: string;
  done?: boolean;
  /**
   * The pill's colour and the word appended to the accessible name. Absent on a project
   * bar, which has a status of its own that means something else.
   */
  status?: TicketStatus;
  /**
   * This ticket has an incoming dependency the schedule no longer respects — the
   * same fact `row.tsx`'s ⚠ badge reports, computed once there by `overlapNotice`
   * and handed down rather than recomputed here. Tints the bar the drawing's way:
   * pale red in place of the status tint, not a flag of its own to keep in step.
   */
  violated?: boolean;
  /**
   * Minutes of slack before this ticket's own schedule forces something else to
   * move — absent for a ticket with no dependencies, which has none to report.
   * Drawn as a hatched strip appended after the bar; see `bar-style.ts`. Absent on
   * a project bar: slack is a fact about one ticket's place in a chain, not about
   * a band summarising several.
   */
  slackMinutes?: number;
  /**
   * Which of the bar's edges was deduced from the tickets inside rather than posted by
   * anyone. Named per edge rather than as a flag because a bound that was deduced must
   * not be dragged: moving it would be editing a consequence, and a project whose start
   * is explicit and whose end is not has one edge somebody chose and one it was given.
   */
  derived?: "start" | "end" | "both";
  /** The cursor is on this bar. The chart shows it; the keyboard acts on it. */
  selected?: boolean;
  /** Pressing the bar. Runs on pointerdown, before the threshold decides on a drag. */
  onSelect?: () => void;
  /** Absent on a bar that cannot be moved at all — every project bar, today. */
  drag?: BarDrag;
  /** Absent on a bar nothing can depend on — every project bar, today. */
  link?: BarLink;
};

/**
 * How far the pointer must travel before a press becomes a drag.
 *
 * Without it there is no click: a hand never releases a button on the exact pixel it
 * pressed it, so every attempt to put the cursor on a bar would end in a patch. Four
 * pixels is well under one column at every zoom, so the threshold decides only whether
 * a gesture is a drag — never how far it dragged.
 */
const DRAG_THRESHOLD = 4;

/**
 * A resize handle's width, and the narrowest bar that carries two of them.
 *
 * At month zoom a day is three pixels, so most bars are narrower than their own grips.
 * Below the minimum the handles are dropped rather than shrunk: two 6px grips on a 10px
 * bar leave nothing to grab in the middle, and the bar could then be resized but never
 * moved.
 */
const HANDLE = 6;
const HANDLED_MIN = 4 * HANDLE;

/** Where the pointer went down, and what the gesture has become since. */
type Gesture = {
  edge: "start" | "end" | "both";
  fromX: number;
  /** The bar's drawn width, so a resize can be painted without measuring the DOM. */
  width: number;
  /** Columns between the two drawn edges: how far a resize may go before inverting. */
  span: number;
  days: number;
  dragging: boolean;
};

export function TimelineBar({
  name,
  ticketId,
  kind,
  state,
  label,
  start,
  end,
  origin,
  zoom,
  timezone,
  done,
  status,
  violated,
  slackMinutes,
  derived,
  selected,
  onSelect,
  drag,
  link,
}: BarProps) {
  const from = boundLabel(start, timezone);
  const to = boundLabel(end, timezone);
  const left = xOf(start, origin, zoom);
  const width = widthOf(start, end, zoom);
  // A project draws no strip and gets no slack mention either — one gate feeds
  // both the name and the width below.
  const ticketSlack = kind === "ticket" ? slackMinutes : undefined;
  const accessibleName = barAccessibleName({ name, status, state, violated, slackMinutes: ticketSlack });
  const slackPx = slackWidthPx(ticketSlack, PX_PER_DAY[zoom]);

  /*
   * Kind and state together pick the bar's static classes; its colour is dynamic
   * (a status, or `--urgent`) and lives in `ticketTint`/`LATE_STYLE` below instead —
   * see the note there on why that half is inline style, not a Tailwind class.
   *
   * `critical` (no slack left, but nothing broken yet) picks no branch of its own:
   * the drawing shows it exactly like an ordinary bar of the same status, coloured
   * by `ticketTint` below like any other, and lets the chain of arrows leaving it
   * say "critical" instead of a colour every merely-tight ticket would also wear.
   */
  const look =
    kind === "project"
      ? "top-[9px] bottom-[9px] border border-faint bg-accent font-medium text-muted-foreground"
      : "top-1 bottom-1";

  /**
   * The link handle is a sibling of the bar rather than a child of it, so it can sit
   * *outside* the right edge — a bar has `overflow: hidden`, and a grip that protrudes
   * from inside it would be cut in half. Being a sibling is also what keeps it from
   * being mistaken for the resize grip six pixels to its left: one is a full-height
   * strip inside the bar, the other a circle beyond its end.
   *
   * The cost is that it does not follow the bar during a drag, since the drag paints the
   * bar's own element and nothing else. So it is painted too, from this ref.
   */
  const handle = useRef<HTMLSpanElement>(null);

  /**
   * The status pill is a sibling for the same reason the link handle is one — see
   * above — and pays the same cost: the drag writes straight to the bar's element, so a
   * sibling is not carried along by the bar's own transform. It rides the *left* edge,
   * which is the bar's own edge, not the handle's.
   */
  const statusDot = useRef<HTMLSpanElement>(null);

  /**
   * The gesture lives in a ref, and the offset is written straight onto the element.
   *
   * A pointermove is a state update sixty times a second, and this one would re-render
   * the whole chart — every row, the arrow layer, the axis — to move one bar. Nothing
   * else on screen changes while a bar is under the pointer, so nothing else needs to
   * be told; React hears about the drag once, when it ends.
   */
  const gesture = useRef<Gesture | null>(null);

  const paint = (element: HTMLElement, moved: Gesture) => {
    const offset = moved.days * PX_PER_DAY[zoom];
    // `transform`, not `left`: an offset that never touches layout, so dragging a bar
    // across a chart of five hundred rows does not reflow one of them. The left edge
    // needs both — the transform carries it, and the width gives back what it took.
    element.style.transform = moved.edge === "end" ? "" : `translateX(${offset}px)`;
    element.style.width = `${
      moved.edge === "start" ? width - offset : moved.edge === "end" ? width + offset : width
    }px`;
    // The handle rides the bar's *right* edge, which the left grip does not move.
    if (handle.current) {
      handle.current.style.transform = moved.edge === "start" ? "" : `translateX(${offset}px)`;
    }
    // The pill rides the bar's *left* edge — the same one the bar's own transform
    // above moves — so it takes the identical expression rather than the handle's.
    if (statusDot.current) {
      statusDot.current.style.transform = moved.edge === "end" ? "" : `translateX(${offset}px)`;
    }
  };

  /**
   * Back to what React put there, rather than to nothing.
   *
   * `style={{ left, width }}` writes the element's *inline* style, which is the same
   * declaration this drag has been overwriting. Emptying it would leave the bar with no
   * width at all — and React would not restore it, because the next render computes the
   * same number it rendered last time and skips the property.
   */
  const clear = (element: HTMLElement) => {
    element.style.transform = "";
    element.style.width = `${width}px`;
    if (handle.current) handle.current.style.transform = "";
    if (statusDot.current) statusDot.current.style.transform = "";
  };

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    // The secondary button opens a context menu; it must not move the cursor either,
    // so this guard stands ahead of selection rather than folded into the one below.
    if (event.button !== 0) return;

    // Pressing a bar puts the cursor on it whether or not this press can become a drag:
    // an in-scope ticket that is merely not editable — or read from a chart that is
    // read-only altogether — carries no `drag` prop and still has to answer `h`, `l`,
    // `H` and `L`, which all read `selected`. A project bar passes no `onSelect` at all,
    // so the optional call is inert there without a special case.
    onSelect?.();

    // Nothing below this needs doing for a bar that cannot be dragged: no gesture to
    // arm, no pointer to capture, no default to suppress.
    if (!drag) return;

    const grip = (event.target as HTMLElement).closest<HTMLElement>(".tl-handle");
    const edge = grip?.dataset.edge === "start" || grip?.dataset.edge === "end"
      ? (grip.dataset.edge as "start" | "end")
      : "both";

    gesture.current = {
      edge,
      fromX: event.clientX,
      width,
      span: daysBetween(dayKey(start), dayKey(end)),
      days: 0,
      dragging: false,
    };
    // Capture, so the gesture survives the pointer leaving the bar — which it does on
    // the first column, since the bar is not moving out from under it fast enough.
    event.currentTarget.setPointerCapture(event.pointerId);
    // Stops the press from also starting a text selection across the chart.
    event.preventDefault();
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const moved = gesture.current;
    if (!moved || !drag) return;

    const dx = event.clientX - moved.fromX;
    if (!moved.dragging) {
      if (Math.abs(dx) < DRAG_THRESHOLD) return;
      moved.dragging = true;
      drag.onStart();
    }

    const days = snapDays(dx, zoom);
    // Clamped, not refused: the API answers a due before a start with a 400 and a
    // dialog nobody asked for, so an edge dragged past its opposite stops on it and the
    // bar collapses to one column instead of turning inside out.
    moved.days =
      moved.edge === "start"
        ? Math.min(days, moved.span)
        : moved.edge === "end"
          ? Math.max(days, -moved.span)
          : days;
    paint(event.currentTarget, moved);
  };

  const finish = (event: ReactPointerEvent<HTMLDivElement>, commit: boolean) => {
    const moved = gesture.current;
    gesture.current = null;
    if (!moved) return;

    clear(event.currentTarget);
    // Only a gesture that started is ended: a click told nobody it began, and the view
    // is holding nothing that has to be released.
    if (!moved.dragging || !drag) return;

    if (!commit || moved.days === 0) {
      drag.onEnd();
      return;
    }

    const edit: BarEdit = {};
    if (moved.edge !== "end" && drag.bounds.start) edit.start = laterBy(start, moved.days);
    if (moved.edge !== "start" && drag.bounds.end) edit.due = laterBy(end, moved.days);
    drag.onEnd(edit.start || edit.due ? edit : undefined);
  };

  // Wide enough to keep a middle to grab: see HANDLED_MIN.
  const handles = drag && width >= HANDLED_MIN ? drag.handles : { start: false, end: false };

  // The bar's colour: a project keeps its plain `bg-accent` (a Tailwind class, in
  // `look`, since it never varies), a late ticket gets the hatch, and any other
  // ticket gets its status tint — both defined in `bar-style.ts`, the one thing
  // here that is not a fixed class, because the status is one of six and a switch
  // of six bracketed classes would be harder to read than the function computing
  // the same colour once.
  const colorStyle =
    kind === "project"
      ? undefined
      : state === "slipping"
        ? SLIPPING_STYLE
        : ticketTint(status, violated);

  const derivedClass = derivedBorderClass(derived);

  return (
    <>
      {status && (
        <span
          ref={statusDot}
          aria-hidden="true"
          data-status={status}
          className="pointer-events-none absolute top-1/2 z-[2] -ml-1 -mt-1 size-2 rounded-full border border-background"
          style={{ left, background: colourOfKey(status) }}
        />
      )}

      <div
        // `group/bar` for the resize grips inside it; `peer` for the link handle beside
        // it, which reads this element's own `data-selected` through the sibling
        // combinator Tailwind's `peer-*` variant compiles to.
        // `tl-bar` carries no styling of its own any more — every visual rule below is
        // a Tailwind utility — but it stays as a bare hook: `barAt()` in this file and
        // the end-to-end suite both find a bar by `.closest(".tl-bar")`.
        className={`tl-bar group/bar peer absolute z-[1] flex items-center overflow-hidden rounded-md text-11 ${look} ${derivedClass} data-[selected]:outline-2 data-[selected]:outline-primary data-[selected]:outline-offset-1 data-[done]:opacity-55 data-[draggable]:cursor-grab data-[draggable]:active:cursor-grabbing data-[link-target]:outline-2 data-[link-target]:outline-dashed data-[link-target]:outline-primary data-[link-target]:outline-offset-1 group-data-[linking]/chart:cursor-crosshair`}
        role="button"
        aria-label={accessibleName}
        data-ticket-id={ticketId}
        // Not `aria-pressed`: a bar is not a toggle. `aria-current` is what says "this
        // one of the set is the one being worked on", which is exactly what the cursor is.
        aria-current={selected ? "true" : undefined}
        data-kind={kind}
        data-state={state}
        // Valueless attributes: `data-done` is present or it is not, which is what the
        // stylesheet asks and what a `false` string would quietly break.
        data-done={done ? "" : undefined}
        data-derived={derived}
        data-selected={selected ? "" : undefined}
        data-draggable={drag ? "" : undefined}
        style={{ left, width, ...colorStyle }}
        title={`${accessibleName}\n${from} → ${to}${derived ? "\nDeduced from the tickets inside" : ""}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={(event) => finish(event, true)}
        // The system took the pointer away — a touch became a scroll, a window lost
        // focus. Nothing is posted, and the view is told so it can stop holding on.
        onPointerCancel={(event) => finish(event, false)}
      >
        {handles.start && (
          <span
            data-edge="start"
            aria-hidden="true"
            // `tl-handle` is how `onPointerDown` below tells a grip from the rest of the
            // bar; it carries no rule of its own any more.
            className="tl-handle absolute inset-y-0 left-0 w-1.5 cursor-ew-resize bg-[color-mix(in_srgb,currentColor_45%,transparent)] opacity-0 group-hover/bar:opacity-100 group-data-[selected]/bar:opacity-100"
          />
        )}
        {/*
         * `slipping` is the one state whose fill is a two-tone hatch rather than a
         * flat tint (`SLIPPING_STYLE`), and no flat ink clears 4.5:1 against both
         * bands in both themes at once: white holds against the darker band but
         * fails the lighter one in dark mode (3.12:1), and the reverse is true of
         * a dark ink — measured, not assumed, before choosing this. `--urgent`
         * itself is unchanged; only the label moves, onto the plain chart
         * background beside the bar, where a single colour reads reliably in
         * both schemes. Nothing else in the bundle draws a hatched bar — every
         * problem state it does draw (a violated dependency, a critical-path
         * ticket) puts its label on a flat tint, never on a texture — so this is
         * the same move the bundle already makes for every other case, applied
         * to the one state it never faced.
         */}
        {/* Hidden on the hatched state: no flat ink clears 4.5:1 over those stripes. */}
        {state !== "slipping" && <span className="truncate px-1.5">{label}</span>}
        {handles.end && (
          <span
            data-edge="end"
            aria-hidden="true"
            className="tl-handle absolute inset-y-0 right-0 w-1.5 cursor-ew-resize bg-[color-mix(in_srgb,currentColor_45%,transparent)] opacity-0 group-hover/bar:opacity-100 group-data-[selected]/bar:opacity-100"
          />
        )}
      </div>

      {/*
       * The slipping label: a sibling for the same structural reason the link handle
       * and the slack strip are — it sits past the bar's own `overflow: hidden`,
       * at the bar's right edge, and does not need to track a drag repainting only
       * the bar's own element. `aria-hidden`, like those two: the name is already
       * the bar's `aria-label`/`title`, and this is the sighted-only redraw of it.
       */}
      {state === "slipping" && (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute top-1/2 z-[1] -mt-2 max-w-40 truncate pl-1.5 text-11 text-urgent"
          style={{ left: left + width }}
        >
          {label}
        </span>
      )}

      {/*
       * Pointer-only, and `aria-hidden` for the same reason the resize grips are: the
       * keyboard already draws arrows with `d`, which opens the palette on the candidate
       * predecessors, and a button per bar that a screen reader could reach but not
       * usefully operate — the gesture *is* the drag — is a board's worth of tab stops
       * leading nowhere. The tray's chips took the opposite decision because a chip has
       * something to do when pressed; this has not.
       */}
      {link && (
        <span
          ref={handle}
          aria-hidden="true"
          className="absolute top-1/2 z-[1] -mt-1.5 size-[11px] cursor-crosshair touch-none rounded-full border border-background bg-primary opacity-0 group-hover/lane:opacity-100 peer-data-[selected]:opacity-100 hover:scale-125"
          title={`Drag to the ticket that waits for ${name}`}
          style={{ left: left + width }}
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            // Capture on the handle, not on the window: every event of this gesture is
            // then delivered here whatever it passes over, so the drop is resolved from
            // one pointerup that cannot be missed rather than from a listener racing
            // the render that would have attached it.
            event.currentTarget.setPointerCapture(event.pointerId);
            // Nothing to stop propagating — the handle is beside the bar, not inside it,
            // so the press never reaches the move gesture. This only keeps the drag from
            // also starting a text selection across the chart.
            event.preventDefault();
            link.onStart();
          }}
          onPointerUp={(event) => link.onEnd(event.clientX, event.clientY)}
          onPointerCancel={() => link.onCancel()}
        />
      )}

      {/*
       * The slack strip: "la marge est dessinée plutôt que sous-entendue" — drawn
       * rather than left for the reader to infer from two dates. A sibling of the
       * bar for the same reason the link handle and the status pill are: it stands
       * outside the bar's own `overflow: hidden`, immediately past its right edge,
       * and the bar's drag repaints only the bar's own element, so this does not
       * need to follow it — a bar is dragged by its own dates, not by its slack,
       * and the next render draws the strip in the moved bar's new place regardless.
       */}
      {slackMinutes != null && slackPx > 0 && (
        <div
          aria-hidden="true"
          title={slackTitle(slackMinutes)}
          className="pointer-events-none absolute top-1 bottom-1 rounded-r-md border-r border-dashed border-rule"
          style={{ left: left + width, width: slackPx, ...SLACK_STYLE }}
        />
      )}
    </>
  );
}

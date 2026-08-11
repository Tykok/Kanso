"use client";

import { useRef, type PointerEvent as ReactPointerEvent } from "react";
import type { KansoInstant, TicketStatus } from "@/lib/api";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
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

/**
 * The state a bar is drawn in. `late` and `critical` are both red on purpose — a chain
 * that overruns its deadline is a worse case of the same thing — so the stylesheet
 * separates them with a hatch as well, or the difference would vanish for a colour-blind
 * reader and in every greyscale screenshot.
 */
export type BarState = "normal" | "critical" | "late";

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
  const accessibleName = status ? `${name} — ${STATUS_LABELS[status]}` : name;

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
    // The secondary button opens a context menu; taking it as a drag would leave the
    // bar captured with no pointerup coming.
    if (!drag || event.button !== 0) return;

    // Pressing a bar puts the cursor on it, drag or not: everything the keyboard can do
    // to a bar reads `selected`, so a bar that could be dragged but not selected would
    // be editable by hand and invisible to `h`, `l`, `H` and `L`.
    onSelect?.();

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

  return (
    <>
      {status && (
        <span
          ref={statusDot}
          className="tl-status"
          aria-hidden="true"
          data-status={status}
          style={{ left, background: STATUS_COLORS[status] }}
        />
      )}

      <div
        className="tl-bar"
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
        style={{ left, width }}
        title={`${accessibleName}\n${from} → ${to}${derived ? "\nDeduced from the tickets inside" : ""}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={(event) => finish(event, true)}
        // The system took the pointer away — a touch became a scroll, a window lost
        // focus. Nothing is posted, and the view is told so it can stop holding on.
        onPointerCancel={(event) => finish(event, false)}
      >
        {handles.start && <span className="tl-handle" data-edge="start" aria-hidden="true" />}
        <span className="tl-bar-label">{label}</span>
        {handles.end && <span className="tl-handle" data-edge="end" aria-hidden="true" />}
      </div>

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
          className="tl-link"
          aria-hidden="true"
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
    </>
  );
}

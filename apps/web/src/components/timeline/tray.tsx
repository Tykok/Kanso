"use client";

import { useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { TimelineUnscheduled } from "@/lib/api";

/** What the tray needs from the view to put a chip on the chart. */
export type TrayControl = {
  /** The cursor. A chip carries it too: `p` and `d` act on whatever it is on. */
  selectedId?: string;
  onSelect: (ticketId: string) => void;
  /** The pointer is dragging a chip: say where it is, so the column can be painted. */
  onDragMove: (x: number, y: number) => void;
  /** Released after a drag. Schedules if a day is under the pointer, nothing otherwise. */
  onDrop: (ticketId: string, x: number, y: number) => void;
  /** The system took the pointer away, or the drag ended on nothing. */
  onDragEnd: () => void;
};

/**
 * How far the pointer travels before a press on a chip becomes a drag. The same four
 * pixels a bar uses, and for the same reason: a hand does not release on the pixel it
 * pressed, so without a threshold every attempt to select a chip would schedule it.
 */
const DRAG_THRESHOLD = 4;

/**
 * Everything the timeline cannot draw. A ticket with neither a start nor a due date has
 * no column to stand in, so it would simply be absent from the chart — and on the
 * current database that is almost every ticket. The strip is the measure of what is
 * left to plan; without it the first load of the timeline is an empty screen that reads
 * as broken rather than as unplanned.
 */
export function TimelineTray({
  items,
  control,
}: {
  items: TimelineUnscheduled[];
  control: TrayControl;
}) {
  // Open by default: a collapsed strip on first load says as little as no strip at all,
  // and this is the half of the board that has not been planned yet.
  const [open, setOpen] = useState(true);

  /** Where the press began, and whether it has become a drag. In a ref: see `bar.tsx`. */
  const gesture = useRef<{ fromX: number; fromY: number; dragging: boolean } | null>(null);

  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>, ticketId: string) => {
    if (event.button !== 0) return;
    // Pressing a chip puts the cursor on it whether or not a drag follows, exactly as
    // pressing a bar does — `p` schedules whatever the cursor is on, and a chip that
    // could be dragged but not selected would be invisible to the keyboard.
    control.onSelect(ticketId);
    gesture.current = { fromX: event.clientX, fromY: event.clientY, dragging: false };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const moved = gesture.current;
    if (!moved) return;

    if (!moved.dragging) {
      // Both axes: a chip is dragged downwards onto the grid at least as often as
      // sideways, and a horizontal-only threshold would never fire on that gesture.
      const travelled = Math.hypot(event.clientX - moved.fromX, event.clientY - moved.fromY);
      if (travelled < DRAG_THRESHOLD) return;
      moved.dragging = true;
      // Written on the element rather than held in state: the only reader is the
      // stylesheet, and a re-render of the tray mid-gesture would be for nothing.
      event.currentTarget.dataset.dragging = "";
    }
    control.onDragMove(event.clientX, event.clientY);
  };

  const finish = (
    event: ReactPointerEvent<HTMLButtonElement>,
    ticketId: string,
    commit: boolean,
  ) => {
    const moved = gesture.current;
    gesture.current = null;
    delete event.currentTarget.dataset.dragging;
    // A press that never travelled is a click: it selected the chip and asks nothing
    // more. Only a drag can schedule.
    if (!moved?.dragging) return;
    if (commit) control.onDrop(ticketId, event.clientX, event.clientY);
    else control.onDragEnd();
  };

  // Nothing unscheduled is nothing to say. An "Unscheduled · 0" header is a permanent
  // strip of chrome reporting the absence of a problem.
  if (items.length === 0) return null;

  return (
    <div className="tl-tray" data-open={open ? "" : undefined}>
      <button
        type="button"
        className="tl-tray-toggle"
        aria-expanded={open}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
      >
        {/* Decorative: the state is already on the button through `aria-expanded`, and
            a caret read out as "black right-pointing triangle" says it a second time
            and worse. */}
        <span className="tl-tray-caret" aria-hidden="true">
          ▸
        </span>
        {/* The count belongs in the header rather than beside it: collapsed, this line
            is the whole of what the tray reports. */}
        Unscheduled · {items.length}
      </button>

      {open && (
        <ul className="tl-tray-list">
          {items.map((ticket) => (
            <li key={ticket.id}>
              {/*
               * A `<button>` now, where it was an inert `<li>`. The reason the chips were
               * not buttons was that pressing one did nothing, and a board's worth of
               * buttons leading nowhere is a board's worth of tab stops leading nowhere.
               * They lead somewhere now: a chip can be dragged onto a day, and pressing
               * one puts the cursor on its ticket, which is what `p` schedules and what
               * `d` hangs a dependency on. A gesture with no role promising it is
               * reachable by exactly one kind of person.
               *
               * The accessible name is spelled the way a bar's is, so the same ticket
               * reads the same in both halves of the screen.
               */}
              <button
                type="button"
                className="tl-chip"
                aria-label={`${ticket.identifier}: ${ticket.title}`}
                aria-current={ticket.id === control.selectedId ? "true" : undefined}
                data-selected={ticket.id === control.selectedId ? "" : undefined}
                title={`${ticket.identifier}: ${ticket.title}\nDrag onto a day to schedule it`}
                onPointerDown={(event) => onPointerDown(event, ticket.id)}
                onPointerMove={onPointerMove}
                onPointerUp={(event) => finish(event, ticket.id, true)}
                onPointerCancel={(event) => finish(event, ticket.id, false)}
                // The pointer already selected on the way down; this is the keyboard's
                // way in, and re-selecting the same id changes nothing.
                onClick={() => control.onSelect(ticket.id)}
              >
                <span className="tl-chip-id">{ticket.identifier}</span>
                <span className="tl-chip-title">{ticket.title}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

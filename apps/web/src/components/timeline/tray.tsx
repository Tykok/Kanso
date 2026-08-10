"use client";

import { useState } from "react";
import type { TimelineUnscheduled } from "@/lib/api";

/**
 * Everything the timeline cannot draw. A ticket with neither a start nor a due date has
 * no column to stand in, so it would simply be absent from the chart — and on the
 * current database that is almost every ticket. The strip is the measure of what is
 * left to plan; without it the first load of the timeline is an empty screen that reads
 * as broken rather than as unplanned.
 *
 * Read-only here. The chips become the drag source for scheduling in a later task,
 * which is why each carries the same `identifier: title` accessible name a bar does.
 */
export function TimelineTray({ items }: { items: TimelineUnscheduled[] }) {
  // Open by default: a collapsed strip on first load says as little as no strip at all,
  // and this is the half of the board that has not been planned yet.
  const [open, setOpen] = useState(true);

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
            /*
             * The accessible name is the only handle anything outside this file has on
             * a chip — no test hooks, no meaningful classes — and it is spelled the way
             * a bar's is, so the same ticket reads the same in both halves of the
             * screen. Not a `<button>`: a chip does nothing when pressed yet, and a
             * board's worth of inert buttons is a board's worth of tab stops that lead
             * nowhere.
             */
            <li
              className="tl-chip"
              key={ticket.id}
              aria-label={`${ticket.identifier}: ${ticket.title}`}
            >
              <span className="tl-chip-id">{ticket.identifier}</span>
              <span className="tl-chip-title">{ticket.title}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

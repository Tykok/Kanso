"use client";

import { axisTicks, dayKey, daysBetween, today, xOf, type Zoom } from "@/lib/timeline-geometry";
import { TODAY_MARKER_ID } from "@/lib/use-action-ctx";

/**
 * Room a `dd/mm` label needs, in pixels, before it runs into the next one. Measured
 * against the axis font rather than derived: it is the width of the text, and the
 * stylesheet is where that is decided.
 */
const LABEL_WIDTH = 34;

/**
 * The time axis and the rules under it. Both come from the same `axisTicks` call, so a
 * label and the line it belongs to can never end up one column apart.
 *
 * The rules are one absolutely-positioned layer rather than a border per row: a row is
 * a bar's container and knows nothing about the calendar, and giving every row its own
 * set of columns would multiply every tick by the number of rows on screen.
 */
export function TimelineGrid({
  origin,
  dayCount,
  zoom,
}: {
  origin: string;
  dayCount: number;
  zoom: Zoom;
}) {
  const ticks = axisTicks(origin, dayCount, zoom);

  /*
   * Today, and whether it is on the chart at all.
   *
   * `today()` is the reader's local civil day, not `toISOString().slice(0, 10)` — that
   * is UTC's today, and west of Greenwich it would draw the rule one column right for
   * the whole evening, on a chart whose entire premise is that a day is a day.
   *
   * A window that does not contain today draws no rule: the marker names a column, and
   * there is no column to name. `t` then scrolls nowhere, which is the truth — the
   * alternative is a rule pinned to an edge, standing on a day that is not under it.
   */
  const now = today();
  const offset = daysBetween(origin, dayKey(now));
  const onChart = offset >= 0 && offset < dayCount;

  /*
   * The geometry offers a tick per column; the axis prints as many of them as fit. Day
   * zoom is 28 pixels wide and `dd/mm` is not, so at that density the labels would run
   * together into one unbroken string of digits naming no day at all. Every rule is
   * still drawn — it is only the writing that thins out.
   *
   * Measured against the previous *label*, not against a fixed stride, because a month
   * tick's spacing depends on the length of the month.
   */
  let lastLabel = Number.NEGATIVE_INFINITY;
  const labelled = new Set<string>();
  for (const tick of ticks) {
    if (tick.x - lastLabel < LABEL_WIDTH) continue;
    labelled.add(tick.day);
    lastLabel = tick.x;
  }

  return (
    <>
      {/* The whole strip is sticky, so the empty cell over the names column stays put
          in the corner rather than letting the first name scroll up into the axis. */}
      <div className="tl-axis-row">
        <div className="tl-corner" />
        <div className="tl-axis">
          {ticks
            .filter((tick) => labelled.has(tick.day))
            .map((tick) => (
              <span className="tl-tick" key={tick.day} style={{ left: tick.x }}>
                {tick.label}
              </span>
            ))}
        </div>
      </div>
      <div className="tl-rules" aria-hidden="true">
        {ticks.map((tick) => (
          <span className="tl-rule" key={tick.day} style={{ left: tick.x }} />
        ))}
        {/*
         * In the rules layer rather than in a layer of its own: it is the same kind of
         * mark, measured from the same origin, and it has to pass *under* the bars —
         * positioned elements paint in document order, and the rules come before the
         * rows. The id is how `t` reaches it; `use-action-ctx` scrolls to that element
         * rather than keeping a "recentre" flag in the store for the chart to watch and
         * then clear.
         */}
        {onChart && (
          <span
            className="tl-today"
            id={TODAY_MARKER_ID}
            style={{ left: xOf(now, origin, zoom) }}
          />
        )}
      </div>
    </>
  );
}

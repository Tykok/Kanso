"use client";

import type { KansoInstant } from "@/lib/api";
import { boundLabel, widthOf, xOf, type Zoom } from "@/lib/timeline-geometry";

/**
 * The state a bar is drawn in. `late` and `critical` are both red on purpose — a chain
 * that overruns its deadline is a worse case of the same thing — so the stylesheet
 * separates them with a hatch as well, or the difference would vanish for a colour-blind
 * reader and in every greyscale screenshot.
 */
export type BarState = "normal" | "critical" | "late";

type BarProps = {
  /**
   * The accessible name, and the only handle anything outside this component has on a
   * bar. Bars carry no test hooks and no meaningful classes: `role="button"` plus this
   * is what the end-to-end suite queries, so a bar without one is a bar nothing can
   * reach.
   */
  name: string;
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
   * Which of the bar's edges was deduced from the tickets inside rather than posted by
   * anyone. Named per edge rather than as a flag because a later task has to refuse to
   * drag exactly those: moving a derived bound would be editing a consequence, and a
   * project whose start is explicit and whose end is not may still be dragged by one
   * handle.
   */
  derived?: "start" | "end" | "both";
};

export function TimelineBar({
  name,
  kind,
  state,
  label,
  start,
  end,
  origin,
  zoom,
  timezone,
  done,
  derived,
}: BarProps) {
  const from = boundLabel(start, timezone);
  const to = boundLabel(end, timezone);

  return (
    <div
      className="tl-bar"
      role="button"
      aria-label={name}
      data-kind={kind}
      data-state={state}
      // Valueless attributes: `data-done` is present or it is not, which is what the
      // stylesheet asks and what a `false` string would quietly break.
      data-done={done ? "" : undefined}
      data-derived={derived}
      style={{ left: xOf(start, origin, zoom), width: widthOf(start, end, zoom) }}
      title={`${name}\n${from} → ${to}${derived ? "\nDeduced from the tickets inside" : ""}`}
    >
      <span className="tl-bar-label">{label}</span>
    </div>
  );
}

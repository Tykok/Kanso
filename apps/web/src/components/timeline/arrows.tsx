"use client";

import { useLayoutEffect, useMemo, useRef, useState, type RefObject } from "react";
import type { Row } from "./view";
import type { TimelineDependency } from "@/lib/api";
import { widthOf, xOf, type Zoom } from "@/lib/timeline-geometry";

/**
 * The dependency layer: one `<svg>` over the whole chart, aligned with `.tl-rules` so an
 * arrow and the rule behind it cannot disagree about where a day is.
 *
 * Every path is routed — out, across, in — rather than drawn as a straight diagonal. A
 * diagonal across a dense chart crosses rows it has nothing to do with, and the eye
 * cannot follow it to the bar it ends on.
 */

/** How far the arrow runs out of a bar before it turns. */
const GUTTER = 12;

/** The length of a stub: enough to read as an arrow, short enough not to name a day. */
const STUB = 18;

/** Where a bar's two edges are, and which lane it is in. */
type Anchor = { lane: number; left: number; right: number; identifier: string };

type Arrow = {
  key: string;
  d: string;
  violated: boolean;
  /** One end of this edge has no bar on the chart, so only a stump is drawn. */
  stub: boolean;
  title: string;
};

/**
 * Out, across, in. The vertical hop sits one gutter short of the target so the arrow
 * always arrives travelling rightwards into the successor's left edge — which is what
 * makes the head readable as a direction rather than as a decoration.
 *
 * When the successor starts before its predecessor ends there is no room for that
 * between the two bars, so the across leg moves down into the gutter between two rows
 * and the path grows two segments. It could have stayed at three by running backwards
 * along the predecessor's own row, but that line would lie over the middle of the
 * predecessor's bar — and a path answers the pointer, so it would swallow the drag that
 * moves the bar it is attached to.
 */
const route = (
  from: { x: number; y: number },
  to: { x: number; y: number },
  detourY: number,
): string =>
  to.x - from.x >= GUTTER
    ? `M ${from.x} ${from.y} H ${to.x - GUTTER} V ${to.y} H ${to.x}`
    : `M ${from.x} ${from.y} H ${from.x + GUTTER} V ${detourY} ` +
      `H ${to.x - GUTTER} V ${to.y} H ${to.x}`;

/**
 * The lane and edges of every ticket that has a bar, keyed by id.
 *
 * Project rows are skipped and still counted: a dependency joins tickets, but the lanes
 * it spans include the project headings between them.
 */
function anchorsOf(rows: Row[], origin: string, zoom: Zoom): Map<string, Anchor> {
  const anchors = new Map<string, Anchor>();

  rows.forEach((row, lane) => {
    if (row.kind !== "ticket") return;
    const { ticket } = row;
    // The same fallback `row.tsx` draws with, or an arrow would leave from an edge the
    // bar does not have: a ticket known only by its due date is still a square.
    const start = ticket.start ?? ticket.due;
    const end = ticket.due ?? ticket.start;
    if (!start || !end) return;

    const left = xOf(start, origin, zoom);
    anchors.set(ticket.id, {
      lane,
      left,
      right: left + widthOf(start, end, zoom),
      identifier: ticket.identifier,
    });
  });

  return anchors;
}

/**
 * Where the other end of a stub went. `outOfScope` is the server's word for "absent from
 * this response"; an edge can be in scope and still have no bar, because a ticket with no
 * dates is in the response's `unscheduled` list rather than on the chart.
 */
const elsewhere = (dep: TimelineDependency) =>
  dep.outOfScope ? "outside this view" : "in the unscheduled tray";

function build(
  deps: TimelineDependency[],
  anchors: Map<string, Anchor>,
  rowHeight: number,
): Arrow[] {
  // A row is `--row-height` tall including its bottom border, and its bar is centred in
  // what is left. Both halves of that are the stylesheet's, which is why the height is
  // measured rather than repeated here.
  const centre = (lane: number) => lane * rowHeight + (rowHeight - 1) / 2;
  // The line between two lanes, on the side the arrow is travelling. A bar is inset from
  // its lane, so a detour along that line passes between the bars rather than over one.
  const boundary = (from: number, to: number) =>
    to > from ? (from + 1) * rowHeight - 0.5 : from * rowHeight + 0.5;
  const arrows: Arrow[] = [];

  for (const dep of deps) {
    const from = anchors.get(dep.predecessorId);
    const to = anchors.get(dep.successorId);
    const key = `${dep.predecessorId}->${dep.successorId}`;
    // Said in full rather than as one word: "violated" names the state, and the sentence
    // is what tells the reader which of the two ends the schedule contradicts.
    const overrun = (predecessor: string, successor: string) =>
      dep.violated ? ` · violated: ${predecessor} ends after ${successor} started` : "";

    if (from && to) {
      arrows.push({
        key,
        d: route(
          { x: from.right, y: centre(from.lane) },
          { x: to.left, y: centre(to.lane) },
          boundary(from.lane, to.lane),
        ),
        violated: dep.violated,
        stub: false,
        title: `${from.identifier} → ${to.identifier}${overrun(from.identifier, to.identifier)}`,
      });
      continue;
    }

    // Only one end is on the chart. The stub leaves that bar pointing away from it, and
    // keeps the head on the successor's side so the direction still reads. The absent
    // ticket is not named: `TimelineDependency` carries ids and nothing else, so naming
    // it would mean guessing.
    if (from) {
      const y = centre(from.lane);
      arrows.push({
        key,
        d: `M ${from.right} ${y} H ${from.right + STUB}`,
        violated: dep.violated,
        stub: true,
        title: `A ticket ${elsewhere(dep)} depends on ${from.identifier}${overrun(from.identifier, "it")}`,
      });
      continue;
    }

    if (to) {
      const y = centre(to.lane);
      arrows.push({
        key,
        d: `M ${to.left - STUB} ${y} H ${to.left}`,
        violated: dep.violated,
        stub: true,
        title: `${to.identifier} depends on a ticket ${elsewhere(dep)}${overrun("it", to.identifier)}`,
      });
    }
    // Neither end has a bar: both are unscheduled, or one is and the other is out of
    // scope. There is nothing on screen to hang the arrow off, so it is not drawn.
  }

  return arrows;
}

/**
 * `--row-height` in pixels, read off the layer itself.
 *
 * The SVG's own coordinates are pixels, so the lane a bar sits in has to be turned into
 * one — and the height is 38 or 30 depending on the density the reader chose. Copying
 * either number into TypeScript would be a second place to change it; the observer is
 * what notices when the setting flips, since a shorter row makes the layer shorter too.
 */
function useRowHeight(ref: RefObject<SVGSVGElement | null>): number {
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    const layer = ref.current;
    if (!layer) return;

    const read = () => {
      const px = Number.parseFloat(getComputedStyle(layer).getPropertyValue("--row-height"));
      setHeight(Number.isFinite(px) ? px : 0);
    };

    read();
    const observer = new ResizeObserver(read);
    observer.observe(layer);
    return () => observer.disconnect();
  }, [ref]);

  return height;
}

export function TimelineArrows({
  rows,
  deps,
  origin,
  zoom,
}: {
  rows: Row[];
  deps: TimelineDependency[];
  origin: string;
  zoom: Zoom;
}) {
  const layer = useRef<SVGSVGElement>(null);
  const rowHeight = useRowHeight(layer);

  const anchors = useMemo(() => anchorsOf(rows, origin, zoom), [rows, origin, zoom]);
  // Nothing is drawn before the first measurement, which lands in a layout effect — so
  // the pass that draws no arrows is never painted, rather than showing them at the top
  // of the chart for a frame.
  const arrows = useMemo(
    () => (rowHeight > 0 ? build(deps, anchors, rowHeight) : []),
    [deps, anchors, rowHeight],
  );

  return (
    <svg className="tl-arrows" ref={layer}>
      <defs>
        {/*
         * `markerUnits="userSpaceOnUse"` so the head keeps its size when a violated edge
         * thickens its stroke: a head that grew with the line would say the arrow points
         * somewhere more precise than it does.
         */}
        <marker
          id="tl-arrowhead"
          viewBox="0 0 6 6"
          refX="6"
          refY="3"
          markerWidth="6"
          markerHeight="6"
          markerUnits="userSpaceOnUse"
          orient="auto"
        >
          <path className="tl-arrowhead" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
        <marker
          id="tl-arrowhead-violated"
          viewBox="0 0 6 6"
          refX="6"
          refY="3"
          markerWidth="6"
          markerHeight="6"
          markerUnits="userSpaceOnUse"
          orient="auto"
        >
          <path className="tl-arrowhead" data-violated="" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
      </defs>

      {arrows.map((arrow) => (
        <path
          key={arrow.key}
          className="tl-arrow"
          d={arrow.d}
          data-violated={arrow.violated ? "" : undefined}
          data-stub={arrow.stub ? "" : undefined}
          markerEnd={`url(#${arrow.violated ? "tl-arrowhead-violated" : "tl-arrowhead"})`}
        >
          <title>{arrow.title}</title>
        </path>
      ))}
    </svg>
  );
}

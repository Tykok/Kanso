"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { barAt } from "./bar";
import type { Row } from "./rows";
import type { TimelineDependency } from "@/lib/api";
import { useRowMetrics } from "@/lib/row-metrics";
import { widthOf, xOf, type Zoom } from "@/lib/timeline-geometry";
import { useUi } from "@/store/ui";

/**
 * The dependency layer: one `<svg>` over the whole chart, positioned off the same
 * three custom properties the grid's rules read — set once, on the scroll
 * container in `view.tsx`, and simply cascading down from there:
 * --tl-names: the name column's width.
 * --tl-axis: the axis strip's height.
 * --tl-chart: the day grid's width.
 * So an arrow and the rule behind it can never disagree about where a day is.
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
  /** Broken and still repairable — the amber case, distinct from `violated`. */
  overlap: boolean;
  /** One end of this edge has no bar on the chart, so only a stump is drawn. */
  stub: boolean;
  title: string;
  /** The dependency this line stands for — what erasing it deletes. */
  dep: TimelineDependency;
  /** Where the `×` sits when this arrow is the selected one: on the line, mid-route. */
  erase: { x: number; y: number };
};

/**
 * The vertical middle of a lane. A row is `--row-h` tall including its bottom
 * border, and its bar is centred in what is left — both halves of that are the
 * stylesheet's, which is why the height is measured rather than repeated here.
 */
const laneCentre = (lane: number, rowHeight: number) => lane * rowHeight + (rowHeight - 1) / 2;

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
  const centre = (lane: number) => laneCentre(lane, rowHeight);
  // The line between two lanes, on the side the arrow is travelling. A bar is inset from
  // its lane, so a detour along that line passes between the bars rather than over one.
  const boundary = (from: number, to: number) =>
    to > from ? (from + 1) * rowHeight - 0.5 : from * rowHeight + 0.5;
  const arrows: Arrow[] = [];

  for (const dep of deps) {
    const from = anchors.get(dep.predecessorId);
    const to = anchors.get(dep.successorId);
    const key = `${dep.predecessorId}->${dep.successorId}`;
    // Said in full rather than as one word: naming the state is what tells the reader
    // which of the two ends the schedule contradicts. Violated and overlap get different
    // sentences because they are different news — one the cascade gave up on, the other
    // still fixable by moving a bar — and "broken" alone would erase that distinction.
    const overrun = (predecessor: string, successor: string) =>
      dep.violated
        ? ` · violated: ${predecessor} ends after ${successor} started`
        : dep.overlap
          ? ` · ${successor} starts before ${predecessor} ends`
          : "";

    if (from && to) {
      const head = { x: from.right, y: centre(from.lane) };
      const tail = { x: to.left, y: centre(to.lane) };
      const detour = boundary(from.lane, to.lane);
      arrows.push({
        key,
        d: route(head, tail, detour),
        violated: dep.violated,
        overlap: dep.overlap,
        stub: false,
        title: `${from.identifier} → ${to.identifier}${overrun(from.identifier, to.identifier)}`,
        dep,
        // On the leg that is longest and least likely to lie over a bar: the vertical
        // hop when the route has room for one, the detour when it does not.
        erase:
          tail.x - head.x >= GUTTER
            ? { x: tail.x - GUTTER, y: (head.y + tail.y) / 2 }
            : { x: (head.x + tail.x) / 2, y: detour },
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
        overlap: dep.overlap,
        stub: true,
        title: `A ticket ${elsewhere(dep)} depends on ${from.identifier}${overrun(from.identifier, "it")}`,
        dep,
        // A stub is a whole dependency seen from one end, so it erases like any other.
        erase: { x: from.right + STUB, y },
      });
      continue;
    }

    if (to) {
      const y = centre(to.lane);
      arrows.push({
        key,
        d: `M ${to.left - STUB} ${y} H ${to.left}`,
        violated: dep.violated,
        overlap: dep.overlap,
        stub: true,
        title: `${to.identifier} depends on a ticket ${elsewhere(dep)}${overrun("it", to.identifier)}`,
        dep,
        erase: { x: to.left - STUB, y },
      });
    }
    // Neither end has a bar: both are unscheduled, or one is and the other is out of
    // scope. There is nothing on screen to hang the arrow off, so it is not drawn.
  }

  return arrows;
}

/** Somewhere a Backspace means "delete a character", not "delete a dependency". */
const isTyping = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  (target.tagName === "INPUT" ||
    target.tagName === "TEXTAREA" ||
    target.tagName === "SELECT" ||
    target.isContentEditable);

export function TimelineArrows({
  rows,
  deps,
  origin,
  zoom,
  linking,
  onErase,
}: {
  rows: Row[];
  deps: TimelineDependency[];
  origin: string;
  zoom: Zoom;
  /** An arrow being drawn out of a bar's link handle, or nothing. */
  linking?: { fromId: string };
  onErase: (dep: TimelineDependency) => void;
}) {
  const layer = useRef<SVGSVGElement>(null);
  /**
   * The SVG's own coordinates are pixels, so a lane has to be turned into one — and the
   * same hook answers the chart itself, which is what positions the lanes now that they
   * are virtualised. One reader of `--row-h`, because a lane drawn at one height and an
   * arrow drawn into another is an arrow that misses its bar by a whole row.
   */
  const { height: rowHeight } = useRowMetrics(layer);
  const overlay = useUi((state) => state.overlay);
  const dialog = useUi((state) => state.dialog);

  const anchors = useMemo(() => anchorsOf(rows, origin, zoom), [rows, origin, zoom]);
  // Nothing is drawn before the first measurement, which lands in a layout effect — so
  // the pass that draws no arrows is never painted, rather than showing them at the top
  // of the chart for a frame.
  const arrows = useMemo(
    () => (rowHeight > 0 ? build(deps, anchors, rowHeight) : []),
    [deps, anchors, rowHeight],
  );

  /**
   * Which arrow the cursor is on, by key rather than by object.
   *
   * Looked back up in `arrows` on every render, so an edge that the answer to a refetch
   * no longer carries — the one just erased, most of the time — simply stops being
   * found. Holding the arrow itself would keep a line on screen that the chart no longer
   * draws, with a `×` offering to delete it a second time.
   */
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const selected = arrows.find((arrow) => arrow.key === selectedKey);

  /** The rubber band, and the bar it is currently over. Both painted, never rendered. */
  const rubber = useRef<SVGPathElement>(null);
  const target = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const sheet = layer.current;
    const source = linking ? anchors.get(linking.fromId) : undefined;
    if (!linking || !sheet) return;

    /*
     * One listener, painting two things and re-rendering neither. A pointermove is sixty
     * events a second and this component draws every arrow on the chart; putting the
     * pointer's position in state would rebuild all of them to move one line.
     *
     * `window`, not the layer: the pointer is captured by the handle that started the
     * gesture, so every move is delivered there and bubbles up — including the ones over
     * a different row, which is where the interesting drops are.
     */
    const move = (event: PointerEvent) => {
      const box = sheet.getBoundingClientRect();
      if (rubber.current && source) {
        rubber.current.setAttribute(
          "d",
          `M ${source.right} ${laneCentre(source.lane, rowHeight)} ` +
            `L ${event.clientX - box.left} ${event.clientY - box.top}`,
        );
      }

      // What the arrow would land on if released here. Drawn on the bar itself, because
      // `:hover` does not move during a pointer capture — the whole gesture hovers the
      // handle it started from, so the stylesheet alone cannot say where it ends.
      const hit = barAt(event.clientX, event.clientY);
      const over = hit && hit.id !== linking.fromId ? hit.element : null;
      if (over !== target.current) {
        target.current?.removeAttribute("data-link-target");
        over?.setAttribute("data-link-target", "");
        target.current = over;
      }
    };

    window.addEventListener("pointermove", move);
    return () => {
      window.removeEventListener("pointermove", move);
      target.current?.removeAttribute("data-link-target");
      target.current = null;
    };
  }, [linking, anchors, rowHeight]);

  // Pressing anywhere that is not an arrow puts the cursor down. Without it the `×`
  // stays out over a chart nobody is looking at any more, offering one deletion.
  useEffect(() => {
    if (!selectedKey) return;
    const onPointerDown = (event: PointerEvent) => {
      if ((event.target as Element | null)?.closest(".tl-arrow, .tl-erase")) return;
      setSelectedKey(null);
    };
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, [selectedKey]);

  useEffect(() => {
    // An overlay or a dialog owns its own keys, exactly as the list does not react
    // behind them: an arrow selected before the composer opened must not be deleted by
    // a Backspace meant for the form on top of it.
    if (!selected || overlay !== "none" || dialog.kind !== "none") return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Backspace" && event.key !== "Delete") return;
      if (isTyping(event.target)) return;
      event.preventDefault();
      setSelectedKey(null);
      onErase(selected.dep);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [selected, onErase, overlay, dialog]);

  return (
    <svg
      ref={layer}
      className="pointer-events-none absolute z-0 overflow-visible left-[var(--tl-names)] top-[var(--tl-axis)] h-[calc(100%_-_var(--tl-axis))] w-[var(--tl-chart)]"
    >
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
          <path className="fill-faint" d="M 0 0 L 6 3 L 0 6 z" />
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
          <path className="fill-urgent" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
        <marker
          id="tl-arrowhead-overlap"
          viewBox="0 0 6 6"
          refX="5"
          refY="3"
          markerWidth="6"
          markerHeight="6"
          orient="auto"
          markerUnits="userSpaceOnUse"
        >
          <path className="fill-warning" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
        {/* The head of the line being drawn, which is neither of the other two: it says
            where the pointer is, not what the schedule says. */}
        <marker
          id="tl-arrowhead-drawing"
          viewBox="0 0 6 6"
          refX="6"
          refY="3"
          markerWidth="6"
          markerHeight="6"
          markerUnits="userSpaceOnUse"
          orient="auto"
        >
          <path className="fill-primary" d="M 0 0 L 6 3 L 0 6 z" />
        </marker>
      </defs>

      {arrows.map((arrow) => {
        // One lookup rather than several attribute-selector rules stacked on the same
        // path: `data-violated` and `data-selected` can both be true at once, and which
        // wins would otherwise depend on the order Tailwind happens to emit them in.
        const selectedHere = arrow.key === selectedKey;
        const strokeClass = selectedHere
          ? "stroke-primary [stroke-width:2.5px]"
          : arrow.violated
            ? "stroke-urgent [stroke-width:2px]"
            : arrow.overlap
              ? "stroke-warning [stroke-width:1.5px]"
              : "stroke-faint [stroke-width:1.5px]";

        return (
          <path
            key={arrow.key}
            // `tl-arrow` carries no rule of its own — the pointerdown handler above
            // finds a line to keep selected by `.closest(".tl-arrow, .tl-erase")`.
            className={`tl-arrow pointer-events-auto fill-none focus-visible:stroke-primary focus-visible:[stroke-width:2.5px] focus-visible:outline-none ${strokeClass}`}
            d={arrow.d}
            data-stub={arrow.stub ? "" : undefined}
            style={arrow.stub ? { strokeDasharray: "3 3" } : undefined}
            markerEnd={`url(#${
              arrow.violated
                ? "tl-arrowhead-violated"
                : arrow.overlap
                  ? "tl-arrowhead-overlap"
                  : "tl-arrowhead"
            })`}
            /*
             * A line is not a button, but erasing one has to be reachable, and `<title>`
             * is what names an SVG element to a screen reader. Focusable as well as
             * clickable: the chart has no key that walks the arrows, so without a tab stop
             * the only way to select one — and therefore the only way to delete one at all
             * — would be a mouse.
             */
            role="button"
            tabIndex={0}
            onClick={() => setSelectedKey(arrow.key)}
            onFocus={() => setSelectedKey(arrow.key)}
          >
            <title>{arrow.title}</title>
          </path>
        );
      })}

      {/*
       * The rubber band. Rendered empty and given its `d` by the pointer: there is no
       * second point to draw until the pointer has moved, and a line to nowhere for one
       * frame would be a flicker at the start of every gesture.
       */}
      {linking && (
        <path
          ref={rubber}
          className="fill-none stroke-primary [stroke-dasharray:4_3] [stroke-width:2px] pointer-events-none"
          markerEnd="url(#tl-arrowhead-drawing)"
        />
      )}

      {selected && (
        <g
          // `tl-erase` is the same kind of bare hook as `tl-arrow`: the pointerdown
          // handler treats a press on either as "still on a line", not a deselect.
          className="tl-erase group cursor-pointer pointer-events-auto"
          transform={`translate(${selected.erase.x} ${selected.erase.y})`}
          role="button"
          tabIndex={0}
          onClick={() => {
            setSelectedKey(null);
            onErase(selected.dep);
          }}
        >
          <title>{`Remove this dependency (${selected.title.split(" · ")[0]})`}</title>
          <circle
            r="8"
            className="fill-urgent stroke-background [stroke-width:1.5px] group-focus-visible:stroke-primary group-focus-visible:[stroke-width:2.5px]"
          />
          <path
            d="M -3 -3 L 3 3 M 3 -3 L -3 3"
            className="fill-none stroke-white [stroke-width:1.6px] [stroke-linecap:round]"
          />
        </g>
      )}
    </svg>
  );
}

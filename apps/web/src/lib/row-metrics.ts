"use client";

import { useLayoutEffect, useState, type RefObject } from "react";

/**
 * `--row-h` and `--row-gap` in pixels, read off the element itself.
 *
 * A virtualiser needs a row's height as a number, and so does the timeline's arrow layer
 * — but the height is 36px or 27px depending on the density the reader chose, and 44px
 * again under a coarse pointer. Copying any of those into TypeScript would be a second
 * place to change them, and the timeline is where that bites hardest: it positions a lane
 * at `lane × height` and draws the arrow into that lane at `lane × height`, so two
 * readers of the same token disagreeing by a pixel is an arrow that misses its bar.
 * One hook, therefore, and both of them ask it.
 *
 * Measured in a layout effect and re-measured by a `ResizeObserver` — the effect catches
 * the first paint, the observer catches the density preference being flipped, which
 * changes the element's own height along with the token.
 *
 * Zero until the first measurement lands. Callers render no rows at zero rather than
 * guessing a height: a guess is a paint in the wrong place, and a layout effect runs
 * before the browser paints at all, so the pass that knows nothing is never seen.
 */
export type RowMetrics = {
  /** `--row-h`: how tall one row is drawn. */
  height: number;
  /** `--row-gap`: the air between two of them, which is a list's only separator. */
  gap: number;
};

export function useRowMetrics(ref: RefObject<Element | null>): RowMetrics {
  /**
   * The element, in state rather than read straight off the ref.
   *
   * A ref is not something an effect can depend on: it holds no identity React watches,
   * so an effect keyed on it runs once and never again. Every caller here renders its
   * scroller only once it has something to put in it — the list draws an empty state
   * until the query answers, the chart a "Loading…" — so on the render that matters the
   * ref is still null, and a measurement that only ever tried once would stay at zero
   * for the life of the page. Which is exactly what it did: rows in the DOM, forty-five
   * in the header, and nothing drawn.
   *
   * This runs on every render and does nothing but compare two references, so the effect
   * below still only re-attaches its observer when the element genuinely changes.
   */
  const [element, setElement] = useState<Element | null>(null);
  // The rule offers `[ref]`, which is the bug described above: a ref never changes
  // identity, so the effect would run once, find null, and never look again. The updater
  // compares before it writes, so a render that finds the same element schedules nothing
  // and the chain the rule warns about cannot start.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    setElement((current) => (current === ref.current ? current : ref.current));
  });

  const [metrics, setMetrics] = useState<RowMetrics>({ height: 0, gap: 0 });

  useLayoutEffect(() => {
    if (!element) return;

    const read = () => {
      const style = getComputedStyle(element);
      const px = (name: string) => {
        const value = Number.parseFloat(style.getPropertyValue(name));
        return Number.isFinite(value) ? value : 0;
      };
      const next = { height: px("--row-h"), gap: px("--row-gap") };
      // Only on a real change: a `ResizeObserver` fires on every scroll-driven size
      // change, and setting state to an equal object each time would re-render every
      // row in the list for nothing.
      setMetrics((current) =>
        current.height === next.height && current.gap === next.gap ? current : next,
      );
    };

    read();
    const observer = new ResizeObserver(read);
    observer.observe(element);
    return () => observer.disconnect();
  }, [element]);

  return metrics;
}
